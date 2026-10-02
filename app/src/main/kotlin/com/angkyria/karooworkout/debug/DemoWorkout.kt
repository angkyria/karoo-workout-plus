package com.angkyria.karooworkout.debug

import com.angkyria.karooworkout.data.CoreHeat
import com.angkyria.karooworkout.data.WorkoutStreams
import io.hammerhead.karooext.models.DataType
import kotlin.math.sin

/**
 * A synthetic structured workout for on-desk testing. It emits the same raw
 * stream fields Karoo does, so demo data runs through the real parsing and
 * engine path. Output wanders around the target to show all three colors.
 */
class DemoWorkout(private val ftp: Int = 250) {

    /**
     * [cadence]: optional secondary target range in rpm. [ramp]: the target climbs
     * from low to high across the step (streamed like Karoo: value = current point,
     * min / max = the ramp's ends).
     */
    private data class Step(
        val seconds: Int,
        val lowPct: Int,
        val highPct: Int,
        val cadence: IntRange? = null,
        val ramp: Boolean = false,
    )

    private val steps: List<Step> = buildList {
        add(Step(90, 50, 70, ramp = true))
        add(Step(45, 75, 85))
        repeat(4) {
            add(Step(40, 105, 120, cadence = 95..105))
            add(Step(25, 45, 55))
        }
        add(Step(60, 60, 60)) // single-value target: Karoo's implied +/-5 % band
        add(Step(75, 40, 50))
    }

    private val totalMs = steps.sumOf { it.seconds } * 1000L
    private var positionMs = 0L

    var paused = false
        private set

    fun togglePause() {
        paused = !paused
    }

    /** Back to the first interval, running — like loading the workout afresh. */
    fun restart() {
        positionMs = 0
        paused = false
    }

    /** Advance demo time; loops back to the first interval at the end. */
    fun tick(dtMs: Long) {
        if (!paused) positionMs = (positionMs + dtMs) % totalMs
    }

    /**
     * A CORE sensor plus CORE Heat's index, so the core heat strip shows on the desk:
     * core warms through the workout, the index drifts across heat zones 2-3.
     */
    fun coreHeat(): Map<String, Map<String, Double>> {
        val t = positionMs / 1000.0
        val progress = positionMs.toDouble() / totalMs
        return mapOf(
            DataType.Type.CORE_TEMP to mapOf(DataType.Field.CORE_TEMP to 37.4 + 1.1 * progress),
            DataType.Type.SKIN_TEMP to mapOf(DataType.Field.SKIN_TEMP to 34.2 + 0.6 * sin(t / 50.0)),
            CoreHeat.HEAT_STRAIN to mapOf(DataType.Field.SINGLE to 2.6 + 1.2 * sin(t / 40.0)),
        )
    }

    fun raw(): Map<String, Map<String, Double>> {
        var startMs = 0L
        var index = 0
        while (startMs + steps[index].seconds * 1000L <= positionMs) {
            startMs += steps[index].seconds * 1000L
            index++
        }
        val step = steps[index]
        val stepMs = step.seconds * 1000L
        val stepRemaining = startMs + stepMs - positionMs
        val low = ftp * step.lowPct / 100.0
        val high = ftp * step.highPct / 100.0
        val center = if (step.ramp) {
            low + (high - low) * (positionMs - startMs) / stepMs
        } else {
            (low + high) / 2
        }
        val t = positionMs / 1000.0
        // two slow waves: drifts below, through and above the band
        val power = center * (1 + 0.11 * sin(t / 6.0) + 0.04 * sin(t / 1.7))
        val cadence = 88 + 9 * sin(t / 9.0)

        val target = { value: Double, lo: Double, hi: Double, out: Double ->
            mapOf(
                DataType.Field.WORKOUT_TARGET_TYPE to 0.0,
                DataType.Field.WORKOUT_TARGET_VALUE to value,
                // single-value steps stream no range, like Karoo
                DataType.Field.WORKOUT_TARGET_MIN_VALUE to if (lo == hi) 0.0 else lo,
                DataType.Field.WORKOUT_TARGET_MAX_VALUE to if (lo == hi) 0.0 else hi,
                DataType.Field.WORKOUT_TARGET_OUTPUT_VALUE to out,
                DataType.Field.WORKOUT_TARGET_OUTPUT_VALUE_SMOOTHED to out,
                DataType.Field.WORKOUT_TARGET_RAMP_TYPE to 0.0,
            )
        }
        return buildMap {
            put(
                WorkoutStreams.GATE,
                mapOf(
                    DataType.Field.WORKOUT_CURRENT_STEP to index.toDouble(),
                    DataType.Field.WORKOUT_STEP_COUNT to steps.size.toDouble(),
                    DataType.Field.WORKOUT_STATE to if (paused) 2.0 else 1.0,
                ),
            )
            put(
                DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
                mapOf(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH to stepRemaining.toDouble()),
            )
            put(
                DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION,
                mapOf(DataType.Field.WORKOUT_REMAINING_TIME to (totalMs - positionMs).toDouble()),
            )
            put(DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE, target(center, low, high, power))
            put(
                DataType.Type.WORKOUT_POWER_TARGET,
                mapOf(
                    DataType.Field.WORKOUT_TARGET_VALUE to center,
                    DataType.Field.WORKOUT_DIFFICULTY to 1.0,
                ),
            )
            step.cadence?.let { rpm ->
                put(
                    DataType.Type.WORKOUT_SECONDARY_TARGET_OUTPUT_VALUE,
                    target((rpm.first + rpm.last) / 2.0, rpm.first.toDouble(), rpm.last.toDouble(), cadence),
                )
                put(
                    DataType.Type.WORKOUT_CADENCE_TARGET,
                    mapOf(DataType.Field.WORKOUT_TARGET_VALUE to (rpm.first + rpm.last) / 2.0),
                )
            }
        }
    }
}
