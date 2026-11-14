package com.angkyria.karooworkout.data

/** One upcoming interval of the NEXT list; [target] is already difficulty-scaled. */
data class UpcomingRow(
    val index: Int,
    val target: LibraryTarget?,
    val lengthMs: Long?,
    /** Time until it starts; null once an open step stands in the way. */
    val startsInMs: Long?,
)

sealed interface NextList {
    data class Planned(val rows: List<UpcomingRow>) : NextList

    /** No trustworthy plan: only how many intervals and how much time are left. */
    data class Unknown(val count: Int, val remainingMs: Long?) : NextList
}

/**
 * Matches a library workout to the running one, keeps checking it against what
 * Karoo runs (two consecutive checked steps that disagree drop the plan), and
 * builds the NEXT rows. Pure; [reset] on a new workout.
 */
class PlanTracker {
    private var library: List<LibraryWorkout>? = null
    private var dropped = false
    private var lastStepCount: Int? = null
    private val checked = HashSet<Int>()
    private var mismatches = 0

    var matched: LibraryWorkout? = null
        private set

    /** The library as read; null when reading it failed. */
    fun onLibrary(workouts: List<LibraryWorkout>?) {
        library = workouts
    }

    fun reset() {
        matched = null
        dropped = false
        lastStepCount = null
        checked.clear()
        mismatches = 0
    }

    fun update(state: WorkoutUiState.Shown, watchedStart: Boolean, todayStartMs: Long): NextList {
        if (lastStepCount != null && lastStepCount != state.stepCount) reset()
        lastStepCount = state.stepCount

        if (matched == null && !dropped) {
            val lib = library
            if (lib != null) {
                val run = RunObservation(
                    stepCount = state.stepCount,
                    stepIndex = state.stepIndex,
                    stepRemainingMs = state.stepRemainingMs,
                    totalRemainingMs = state.totalRemainingMs,
                    elapsedMs = state.elapsedMs,
                    watchedStart = watchedStart,
                    targetMin = state.primary?.min,
                    targetMax = state.primary?.max,
                    targetImplied = state.primary?.implied ?: false,
                    scalePercent = state.scalePercent,
                )
                matched = PlanMatcher.match(lib, run, todayStartMs)
            }
        }
        val plan = matched?.expand()?.also { check(it, state) }
        if (matched == null || plan == null) return unknown(state)
        return NextList.Planned(upcomingRows(plan, state))
    }

    /** One verdict per interval, on its first sample at least 3 s in. */
    private fun check(plan: List<PlannedInterval>, state: WorkoutUiState.Shown) {
        val interval = plan.getOrNull(state.stepIndex) ?: return
        val length = interval.lengthMs ?: return
        val remaining = state.stepRemainingMs ?: return
        if (length - remaining < CHECK_AFTER_MS || !checked.add(state.stepIndex)) return
        val primary = state.primary
        val verdict = PlanMatcher.rangeAgrees(
            interval.target, primary?.min, primary?.max, primary?.implied ?: false, state.scalePercent,
        ) ?: return
        if (verdict) {
            mismatches = 0
        } else if (++mismatches >= DROP_AFTER_MISMATCHES) {
            matched = null
            dropped = true
        }
    }

    private fun unknown(state: WorkoutUiState.Shown): NextList.Unknown {
        val remaining = state.totalRemainingMs
        val step = state.stepRemainingMs
        return NextList.Unknown(
            count = (state.stepCount - state.stepIndex - 1).coerceAtLeast(0),
            remainingMs = if (remaining == null || step == null) null else (remaining - step).coerceAtLeast(0L),
        )
    }

    companion object {
        private const val CHECK_AFTER_MS = 3_000L
        private const val DROP_AFTER_MISMATCHES = 2

        /** The intervals after the current one, power targets scaled by the difficulty. */
        internal fun upcomingRows(plan: List<PlannedInterval>, state: WorkoutUiState.Shown): List<UpcomingRow> {
            val scale = (state.scalePercent ?: 100) / 100.0
            var startsIn = state.stepRemainingMs
            return plan.drop(state.stepIndex + 1).map { interval ->
                val row = UpcomingRow(interval.index, interval.target?.scaled(scale), interval.lengthMs, startsIn)
                val length = interval.lengthMs
                startsIn = if (startsIn == null || length == null) null else startsIn!! + length
                row
            }
        }

        private fun LibraryTarget.scaled(scale: Double): LibraryTarget =
            if (kind != TargetKind.POWER || scale == 1.0) this
            else copy(value = value?.times(scale), min = min?.times(scale), max = max?.times(scale))
    }
}
