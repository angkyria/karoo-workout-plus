package com.angkyria.karooworkout.data

/**
 * What a workout target measures. [impliedRangeFraction] is the +/- band Karoo OS
 * adds around single-value targets (power +/-5 %, heart rate +/-7.5 %).
 */
enum class TargetKind(val label: String, val impliedRangeFraction: Double) {
    POWER("POWER", 0.05),
    HEART_RATE("HEART RATE", 0.075),
    CADENCE("CADENCE", 0.05),
    UNKNOWN("TARGET", 0.05),
}

/** Where the rider's output sits against the target range (native colors: blue / green / red). */
enum class TargetStatus { UNDER, IN_RANGE, OVER, NO_DATA }

/** One target of the current interval, as streamed by Karoo (raw, unscaled by us). */
data class RawTarget(
    val type: Int? = null,
    val value: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val output: Double? = null,
    val outputSmoothed: Double? = null,
    val rampType: Int? = null,
)

/** The rider's own sensor reading for one target kind (power meter, HR strap, cadence). */
data class LiveOutput(val instant: Double?, val smoothed: Double?)

/**
 * One coherent read of every workout stream. Times are milliseconds;
 * [stepIndex] is 0-based (Karoo streams the current step that way).
 */
data class WorkoutSnapshot(
    val stepIndex: Int? = null,
    val stepCount: Int = 0,
    val state: Int? = null,
    val stepRemainingMs: Long? = null,
    val totalRemainingMs: Long? = null,
    val elapsedMs: Long? = null,
    val primary: RawTarget? = null,
    val secondary: RawTarget? = null,
    /** Workout scale ("difficulty"), already normalized to percent. */
    val scalePercent: Int? = null,
    /** Target value per kind from the dedicated power / HR / cadence target streams. */
    val kindValues: Map<TargetKind, Double> = emptyMap(),
    /** The rider's sensor streams per kind: the output when Karoo's workout output has none. */
    val live: Map<TargetKind, LiveOutput> = emptyMap(),
) {
    val loaded: Boolean get() = stepCount > 0 && stepIndex != null
}

/** A resolved target: range is always set (implied when the workout gave a single value). */
data class Target(
    val kind: TargetKind,
    val value: Double,
    val min: Double,
    val max: Double,
    val implied: Boolean,
    val ramp: Boolean,
    val output: Double?,
    val status: TargetStatus,
)

/** One interval of the workout as ridden so far. */
data class IntervalRecord(
    val index: Int,
    /** Planned length (countdown at first sight); null for open / lap-button steps. */
    val plannedMs: Long?,
    /** Time actually spent in it (countdown deltas, so ride pauses don't count). */
    val actualMs: Long,
    val kind: TargetKind?,
    /** Primary target center when the interval started and most recently (ramps differ). */
    val startLevel: Double?,
    val endLevel: Double?,
    val inRangeMs: Long,
    val underMs: Long,
    val overMs: Long,
) {
    val trackedMs: Long get() = inRangeMs + underMs + overMs

    /** Share of tracked time spent inside the primary target range, 0..100. */
    val inRangePercent: Int?
        get() = if (trackedMs <= 0) null else (inRangeMs * 100 / trackedMs).toInt()
}

sealed interface WorkoutUiState {
    data object Hidden : WorkoutUiState

    data class Shown(
        val stepIndex: Int,
        val stepCount: Int,
        val stepRemainingMs: Long?,
        val stepPlannedMs: Long?,
        val totalRemainingMs: Long?,
        val elapsedMs: Long,
        val primary: Target?,
        val secondary: Target?,
        val scalePercent: Int?,
        /** Ridden intervals in order; the last one is the current interval. */
        val history: List<IntervalRecord>,
        val workoutInRangePercent: Int?,
        val paused: Boolean,
        val complete: Boolean,
    ) : WorkoutUiState {
        val current: IntervalRecord? get() = history.lastOrNull()

        /** Elapsed + remaining: the whole workout as it currently stands (shrinks on skips). */
        val totalMs: Long? get() = totalRemainingMs?.let { elapsedMs + it }

        /** 0..1 progress through the current interval, null for open steps. */
        val stepProgress: Float?
            get() {
                val planned = stepPlannedMs ?: return null
                val remaining = stepRemainingMs ?: return null
                if (planned <= 0) return null
                return (1f - remaining.toFloat() / planned).coerceIn(0f, 1f)
            }

        /** 0..1 progress through the whole workout. */
        val workoutProgress: Float?
            get() {
                val total = totalMs ?: return null
                if (total <= 0) return null
                return (elapsedMs.toFloat() / total).coerceIn(0f, 1f)
            }

        /** Workout time not yet known in detail: everything after the current interval. */
        val upcomingMs: Long
            get() = ((totalRemainingMs ?: 0L) - (stepRemainingMs ?: 0L)).coerceAtLeast(0L)
    }
}
