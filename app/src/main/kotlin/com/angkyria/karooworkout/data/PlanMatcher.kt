package com.angkyria.karooworkout.data

import kotlin.math.abs

/**
 * What the engine has seen of the running workout, enough to pick its library plan.
 * [watchedStart]: the engine saw step 0 begin, so [stepRemainingMs] is the first
 * countdown and [elapsedMs] + [totalRemainingMs] is the whole workout.
 */
data class RunObservation(
    val stepCount: Int,
    val stepIndex: Int,
    val stepRemainingMs: Long?,
    val totalRemainingMs: Long?,
    val elapsedMs: Long,
    val watchedStart: Boolean,
    val targetMin: Double?,
    val targetMax: Double?,
    val targetImplied: Boolean,
    val scalePercent: Int?,
)

/**
 * Picks the library workout Karoo is running; a wrong plan is worse than none.
 * A watched start (the first countdown and the whole-workout total are known) is
 * checked first and strictly; when it does not fit, the late-join rules apply, since
 * a library read can land well after the start. Open steps (no fixed length) are allowed: the checks that need their length
 * (first length, whole-workout total, remaining total, "length >= remaining")
 * are skipped, while the step count and the current target range still apply.
 */
object PlanMatcher {
    private const val FIRST_LENGTH_TOLERANCE_MS = 2_000L
    private const val TOTAL_TOLERANCE_MS = 5_000L
    private const val RANGE_TOLERANCE = 1.0
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun match(library: List<LibraryWorkout>, run: RunObservation, todayStartMs: Long): LibraryWorkout? {
        val fits = library.filter { fits(it.expand(), run) }
        if (fits.isEmpty()) return null
        // Dated today wins; otherwise any fit is a candidate.
        val today = fits.filter { it.plannedDateMs in todayStartMs until todayStartMs + DAY_MS }
        val pool = today.ifEmpty { fits }
        // Different structures that can't be told apart: no plan. Equal ones are
        // interchangeable, so the latest update wins.
        val shape = pool.first().expand().shape()
        if (pool.any { it.expand().shape() != shape }) return null
        return pool.maxByOrNull { it.updatedAtMs ?: Long.MIN_VALUE }
    }

    private fun List<PlannedInterval>.shape() = map { it.lengthMs to it.target }

    private fun fits(plan: List<PlannedInterval>, run: RunObservation): Boolean {
        if (plan.isEmpty() || plan.size != run.stepCount) return false
        val index = run.stepIndex
        if (index !in plan.indices) return false
        val remaining = run.stepRemainingMs ?: return false
        val totalRemaining = run.totalRemainingMs ?: return false
        val current = plan[index]
        val length = current.lengthMs
        // A watched start is the stricter reading; when it fails (a late library read
        // lands seconds into the workout) the late-join rules still decide.
        val watchedFits = run.watchedStart && index == 0 &&
            (plan.totalMs.let { it == null || it > 0 }) &&
            (length == null || abs(remaining - length) <= FIRST_LENGTH_TOLERANCE_MS) &&
            (plan.totalMs.let { it == null || abs(run.elapsedMs + totalRemaining - it) <= TOTAL_TOLERANCE_MS })
        if (!watchedFits) {
            if (length != null && length < remaining) return false
            val after = plan.drop(index + 1)
            if (after.none { it.lengthMs == null }) {
                val rest = remaining + after.sumOf { it.lengthMs!! }
                if (abs(totalRemaining - rest) > TOTAL_TOLERANCE_MS) return false
            }
        }
        return rangeAgrees(current.target, run.targetMin, run.targetMax, run.targetImplied, run.scalePercent) != false
    }

    /**
     * Whether Karoo's [min]..[max] is the planned range (± 1; power scaled by the
     * difficulty). Null when there is nothing to compare: no planned range, an
     * [implied] band, or no observed range.
     */
    fun rangeAgrees(
        planned: LibraryTarget?,
        min: Double?,
        max: Double?,
        implied: Boolean,
        scalePercent: Int?,
    ): Boolean? {
        if (planned == null || implied || min == null || max == null) return null
        val pMin = planned.min ?: return null
        val pMax = planned.max ?: return null
        val scale = if (planned.kind == TargetKind.POWER && scalePercent != null) scalePercent / 100.0 else 1.0
        return abs(min - pMin * scale) <= RANGE_TOLERANCE && abs(max - pMax * scale) <= RANGE_TOLERANCE
    }
}
