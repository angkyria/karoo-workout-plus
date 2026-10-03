package com.angkyria.karooworkout.data

import io.hammerhead.karooext.models.DataType
import kotlin.math.roundToInt

/**
 * The karoo-ext workout streams this extension reads, and how their fields fold
 * into one [WorkoutSnapshot]. Karoo exposes the *running* workout only — current
 * step, countdowns, targets, output — never the full interval list, and no
 * workout controls (skip / scale) beyond pausing the ride.
 */
object WorkoutStreams {

    /** Streamed for the whole ride: STEP_COUNT > 0 means a workout is loaded. */
    const val GATE = DataType.Type.WORKOUT_INTERVAL_COUNT

    /** Only consumed while a workout is loaded. */
    val DETAIL = listOf(
        DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
        DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION,
        // target + output: Karoo holds these in SEARCHING until the sensor (power meter,
        // HR strap) streams — so the targets alone come from the plain target streams
        DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE,
        DataType.Type.WORKOUT_SECONDARY_TARGET_OUTPUT_VALUE,
        DataType.Type.WORKOUT_PRIMARY_TARGET,
        DataType.Type.WORKOUT_SECONDARY_TARGET,
        // per-kind target streams: tell power / HR / cadence targets apart and carry the scale
        DataType.Type.WORKOUT_POWER_TARGET,
        DataType.Type.WORKOUT_HEART_RATE_TARGET,
        DataType.Type.WORKOUT_CADENCE_TARGET,
    )

    private val kindStreams = mapOf(
        TargetKind.POWER to DataType.Type.WORKOUT_POWER_TARGET,
        TargetKind.HEART_RATE to DataType.Type.WORKOUT_HEART_RATE_TARGET,
        TargetKind.CADENCE to DataType.Type.WORKOUT_CADENCE_TARGET,
    )

    /** A sensor stream: its data type and the field carrying the value. */
    private class Sensor(val typeId: String, val field: String)

    /**
     * The rider's own sensor streams per kind, instant and 3 s smoothed. Karoo's workout
     * output stream is the first choice, but only these are proven to stream on every
     * Karoo, so they fill in whenever it gives no output.
     */
    private val liveSensors = mapOf(
        TargetKind.POWER to (
            Sensor(DataType.Type.POWER, DataType.Field.POWER) to
                Sensor(DataType.Type.SMOOTHED_3S_AVERAGE_POWER, DataType.Field.SMOOTHED_3S_AVERAGE_POWER)
            ),
        TargetKind.HEART_RATE to (
            Sensor(DataType.Type.HEART_RATE, DataType.Field.HEART_RATE) to
                Sensor(DataType.Type.HEART_RATE, DataType.Field.HEART_RATE)
            ),
        TargetKind.CADENCE to (
            Sensor(DataType.Type.CADENCE, DataType.Field.CADENCE) to
                Sensor(DataType.Type.SMOOTHED_3S_AVERAGE_CADENCE, DataType.Field.SMOOTHED_3S_AVERAGE_CADENCE)
            ),
    )

    /** Every sensor stream [liveStreams] may run (for the diagnostics). */
    val LIVE: Set<String> = liveSensors.values.flatMap { listOf(it.first.typeId, it.second.typeId) }.toSet()

    /** The sensor streams worth running for a workout that targets [kinds]: one per kind. */
    fun liveStreams(kinds: Set<TargetKind>, smoothed: Boolean): Set<String> =
        kinds.mapNotNull { kind ->
            liveSensors[kind]?.let { (instant, smooth) -> if (smoothed) smooth.typeId else instant.typeId }
        }.toSet()

    /** Fold the latest field values (keyed by data type id) into a snapshot. */
    fun snapshot(raw: Map<String, Map<String, Double>>): WorkoutSnapshot {
        val count = raw[GATE].orEmpty()
        val stepRemaining = raw[DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION]
            ?.get(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH)
            ?.toLong()
            ?.takeIf { it >= 0 } // negative / missing: open step (lap button, distance)
        val totalRemaining = raw[DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION]
            ?.get(DataType.Field.WORKOUT_REMAINING_TIME)
            ?.toLong()
            ?.takeIf { it >= 0 }
            ?.let { total ->
                // both are milliseconds on current firmware; the workout total always
                // includes the step, so a smaller total can only be seconds
                if (stepRemaining != null && total < stepRemaining) total * 1000 else total
            }
        val elapsed = raw.values.firstNotNullOfOrNull { it[DataType.Field.WORKOUT_ELAPSED_TIME] }
        val state = count[DataType.Field.WORKOUT_STATE]
            ?: raw.values.firstNotNullOfOrNull { it[DataType.Field.WORKOUT_STATE] }
        val difficulty = kindStreams.values.firstNotNullOfOrNull {
            raw[it]?.get(DataType.Field.WORKOUT_DIFFICULTY)
        }
        return WorkoutSnapshot(
            stepIndex = count[DataType.Field.WORKOUT_CURRENT_STEP]?.toInt(),
            stepCount = count[DataType.Field.WORKOUT_STEP_COUNT]?.toInt() ?: 0,
            state = state?.toInt(),
            stepRemainingMs = stepRemaining,
            totalRemainingMs = totalRemaining,
            elapsedMs = elapsed?.toLong(),
            primary = withKindFallback(
                rawTarget(
                    raw[DataType.Type.WORKOUT_PRIMARY_TARGET],
                    raw[DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE],
                ),
                raw,
            ),
            secondary = rawTarget(
                raw[DataType.Type.WORKOUT_SECONDARY_TARGET],
                raw[DataType.Type.WORKOUT_SECONDARY_TARGET_OUTPUT_VALUE],
            ),
            scalePercent = difficulty?.let(::scalePercent),
            kindValues = kindStreams.mapNotNull { (kind, typeId) ->
                raw[typeId]?.get(DataType.Field.WORKOUT_TARGET_VALUE)
                    ?.takeIf { it > 0 }
                    ?.let { kind to it }
            }.toMap(),
            live = liveSensors.mapNotNull { (kind, sensors) ->
                fun read(sensor: Sensor) = raw[sensor.typeId]?.get(sensor.field)?.takeIf { it.isFinite() }
                val instant = read(sensors.first)
                val smoothed = read(sensors.second)
                if (instant == null && smoothed == null) null else kind to LiveOutput(instant, smoothed)
            }.toMap(),
        )
    }

    /**
     * One target from its two streams: the target fields from whichever carries them
     * (the plain target stream also streams without a sensor), output only from
     * the output stream.
     */
    private fun rawTarget(target: Map<String, Double>?, withOutput: Map<String, Double>?): RawTarget? {
        if (target == null && withOutput == null) return null
        val t = target.orEmpty()
        val o = withOutput.orEmpty()
        fun field(id: String): Double? = t[id] ?: o[id]
        return RawTarget(
            type = field(DataType.Field.WORKOUT_TARGET_TYPE)?.toInt(),
            value = field(DataType.Field.WORKOUT_TARGET_VALUE),
            min = field(DataType.Field.WORKOUT_TARGET_MIN_VALUE),
            max = field(DataType.Field.WORKOUT_TARGET_MAX_VALUE),
            output = o[DataType.Field.WORKOUT_TARGET_OUTPUT_VALUE],
            outputSmoothed = o[DataType.Field.WORKOUT_TARGET_OUTPUT_VALUE_SMOOTHED],
            rampType = field(DataType.Field.WORKOUT_TARGET_RAMP_TYPE)?.toInt(),
        )
    }

    /**
     * Seen on a Karoo 2 without its power meter: both primary-target streams stay
     * silent and only the per-kind target streams carry the target. Take the target
     * from those then (power before heart rate), keeping any output we do have.
     */
    private fun withKindFallback(primary: RawTarget?, raw: Map<String, Map<String, Double>>): RawTarget? {
        if (primary != null && primary.hasTarget) return primary
        val kind = PRIMARY_FALLBACKS.firstNotNullOfOrNull { typeId ->
            raw[typeId]?.takeIf { (it[DataType.Field.WORKOUT_TARGET_VALUE] ?: 0.0) > 0 }
        } ?: return primary
        return RawTarget(
            type = primary?.type,
            value = kind[DataType.Field.WORKOUT_TARGET_VALUE],
            min = kind[DataType.Field.WORKOUT_TARGET_MIN_VALUE],
            max = kind[DataType.Field.WORKOUT_TARGET_MAX_VALUE],
            output = primary?.output,
            outputSmoothed = primary?.outputSmoothed,
            rampType = primary?.rampType,
        )
    }

    private val RawTarget.hasTarget: Boolean
        get() = (value ?: 0.0) > 0 || (max ?: 0.0) > 0

    private val PRIMARY_FALLBACKS = listOf(
        DataType.Type.WORKOUT_POWER_TARGET,
        DataType.Type.WORKOUT_HEART_RATE_TARGET,
    )

    /** Scale arrives as a factor (1.05) or a percentage (105); always return percent. */
    fun scalePercent(raw: Double): Int? = when {
        raw <= 0.0 -> null
        raw <= 5.0 -> (raw * 100).roundToInt()
        else -> raw.roundToInt()
    }
}
