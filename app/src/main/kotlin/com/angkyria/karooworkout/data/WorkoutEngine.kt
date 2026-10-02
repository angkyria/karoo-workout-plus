package com.angkyria.karooworkout.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure workout logic: turns stream snapshots into [WorkoutUiState] and builds
 * the interval history Karoo never hands out (karoo-ext streams only the
 * current step). Time is accounted from the step countdown itself, so ride
 * pauses and auto-pause never count as workout time.
 */
class WorkoutEngine {

    private class Rec(
        val index: Int,
        var plannedMs: Long?,
        var actualMs: Long = 0,
        var kind: TargetKind? = null,
        var startLevel: Double? = null,
        var endLevel: Double? = null,
        var inRangeMs: Long = 0,
        var underMs: Long = 0,
        var overMs: Long = 0,
    ) {
        /** The interval ramps: its target value moves between min and max. */
        var ramp = false

        /** Ramp seen from one end, so [endLevel] is the far end, not the latest value. */
        var rampEndsKnown = false
        private var lastValue: Double? = null
        private var lastEnds: Pair<Double?, Double?>? = null

        fun snapshot() = IntervalRecord(
            index, plannedMs, actualMs, kind, startLevel, endLevel, inRangeMs, underMs, overMs,
        )

        fun restart(remainingMs: Long) {
            actualMs = 0
            inRangeMs = 0
            underMs = 0
            overMs = 0
            plannedMs = remainingMs
            startLevel = null
            endLevel = null
            ramp = false
            rampEndsKnown = false
            lastValue = null
            lastEnds = null
        }

        /**
         * Ramps: Karoo streams a ramp's *current* target as the value and its two ends
         * as min / max (a 137 -> 187 W warm-up starts as 137 in 137-187). A value on
         * one end, or one moving while the ends stay put, marks a ramp.
         */
        fun observe(raw: RawTarget) {
            val v = raw.value?.takeIf { it > 0 }
            val lo = raw.min
            val hi = raw.max
            val ends = lo to hi
            if (lastEnds != null && ends != lastEnds) {
                // The target itself changed inside the step: Karoo moves the step index a
                // tick before the target streams catch up, so what was seen so far was the
                // previous interval's target. Start the level history over.
                startLevel = null
                endLevel = null
                ramp = false
                rampEndsKnown = false
                lastValue = null
            }
            val bounded = v != null && lo != null && hi != null && hi > lo
            if (bounded && (v!! <= lo!! || v >= hi!!)) {
                ramp = true
                if (startLevel == null) {
                    // first seen at one end: it runs to the other
                    startLevel = v
                    endLevel = if (v <= lo) hi else lo
                    rampEndsKnown = true
                }
            }
            val previous = lastValue
            if (bounded && previous != null && abs(v!! - previous) > 0.5 && ends == lastEnds) ramp = true
            lastValue = v
            lastEnds = ends
        }
    }

    private val records = sortedMapOf<Int, Rec>()
    private var stepCount = 0
    private var lastIndex: Int? = null
    private var lastStepRemaining: Long? = null
    private var lastTickMs: Long? = null
    private var lastStatus: TargetStatus? = null
    private var workoutInRangeMs = 0L
    private var workoutTrackedMs = 0L

    /** Karoo's numeric target type -> kind, learned whenever a per-kind stream matches. */
    private val learnedKinds = mutableMapOf<Int, TargetKind>()

    fun reset() {
        records.clear()
        stepCount = 0
        lastIndex = null
        lastStepRemaining = null
        lastTickMs = null
        lastStatus = null
        workoutInRangeMs = 0
        workoutTrackedMs = 0
    }

    fun update(
        s: WorkoutSnapshot,
        nowMs: Long,
        paused: Boolean,
        smoothedOutput: Boolean,
    ): WorkoutUiState {
        if (!s.loaded) {
            reset()
            return WorkoutUiState.Hidden
        }
        val index = s.stepIndex!!.coerceIn(0, s.stepCount - 1)
        val prevIndex = lastIndex
        if (s.stepCount != stepCount ||
            (prevIndex != null && prevIndex - index > 1)
        ) {
            // different step count, or back to the start from deep inside: a new workout
            reset()
        }
        stepCount = s.stepCount

        val last = lastIndex
        if (last != null && index != last) {
            if (index < last) {
                // rewind: the steps from here on are ridden again
                records.keys.filter { it >= index }.forEach { records.remove(it) }
            } else {
                // a step that ran out naturally still had its final partial second to credit
                val prev = records[last]
                val lastRem = lastStepRemaining
                if (prev != null && lastRem != null && lastRem <= NATURAL_END_MS) {
                    credit(prev, lastRem, lastStatus)
                }
            }
        }

        val rec = records.getOrPut(index) { Rec(index, plannedMs = s.stepRemainingMs) }
        val lastRem = lastStepRemaining
        val rem = s.stepRemainingMs
        if (index == last && rem != null && lastRem != null && rem > lastRem + RESTART_SLACK_MS) {
            // the countdown jumped back up: the same interval restarted
            rec.restart(rem)
        }

        // Karoo: the primary target is HR or power, the secondary HR, power or cadence
        s.primary?.let { rec.observe(it) }
        val primaryKind = s.primary?.let { resolveKind(it, s.kindValues, exclude = setOf(TargetKind.CADENCE)) }
        val primary = s.primary?.let { resolve(it, primaryKind ?: TargetKind.UNKNOWN, smoothedOutput, rec.ramp) }
        val secondary = s.secondary?.let {
            resolve(it, resolveKind(it, s.kindValues, exclude = setOfNotNull(primaryKind)), smoothedOutput)
        }

        val delta = tickDelta(index == last, s.stepRemainingMs, nowMs, paused)
        if (delta > 0) credit(rec, delta, primary?.status)
        s.stepRemainingMs?.let { rem -> rec.plannedMs = max(rec.plannedMs ?: 0L, rec.actualMs + rem) }
        primary?.let {
            if (rec.startLevel == null) rec.startLevel = it.value
            if (!rec.rampEndsKnown) rec.endLevel = it.value
            if (rec.kind == null || rec.kind == TargetKind.UNKNOWN) rec.kind = it.kind
        }

        lastIndex = index
        lastStepRemaining = s.stepRemainingMs
        lastTickMs = nowMs
        lastStatus = primary?.status

        val history = records.values.map { it.snapshot() }
        val onLastStep = index == s.stepCount - 1
        return WorkoutUiState.Shown(
            stepIndex = index,
            stepCount = s.stepCount,
            stepRemainingMs = s.stepRemainingMs,
            stepPlannedMs = rec.plannedMs,
            totalRemainingMs = s.totalRemainingMs,
            elapsedMs = s.elapsedMs ?: history.sumOf { it.actualMs },
            primary = primary,
            secondary = secondary,
            scalePercent = s.scalePercent,
            history = history,
            workoutInRangePercent =
                if (workoutTrackedMs <= 0) null else (workoutInRangeMs * 100 / workoutTrackedMs).toInt(),
            paused = paused,
            complete = onLastStep && s.stepRemainingMs == 0L && (s.totalRemainingMs ?: 0L) == 0L,
        )
    }

    /** Milliseconds of workout time since the previous tick of the same step. */
    private fun tickDelta(sameStep: Boolean, remaining: Long?, nowMs: Long, paused: Boolean): Long {
        if (!sameStep) return 0
        val lastRem = lastStepRemaining
        if (remaining != null && lastRem != null) {
            val d = lastRem - remaining
            return if (d in 1..MAX_TICK_MS) d else 0
        }
        // open step without a countdown: wall clock, but never while paused
        val lastTick = lastTickMs
        if (remaining == null && lastRem == null && !paused && lastTick != null) {
            val d = nowMs - lastTick
            return if (d in 1..MAX_TICK_MS) d else 0
        }
        return 0
    }

    private fun credit(rec: Rec, ms: Long, status: TargetStatus?) {
        rec.actualMs += ms
        when (status) {
            TargetStatus.IN_RANGE -> {
                rec.inRangeMs += ms
                workoutInRangeMs += ms
                workoutTrackedMs += ms
            }
            TargetStatus.UNDER -> {
                rec.underMs += ms
                workoutTrackedMs += ms
            }
            TargetStatus.OVER -> {
                rec.overMs += ms
                workoutTrackedMs += ms
            }
            TargetStatus.NO_DATA, null -> Unit
        }
    }

    /**
     * Which kind a target is: the per-kind target stream carrying the same value
     * wins (and teaches us Karoo's numeric type id); otherwise a type id seen before.
     */
    private fun resolveKind(raw: RawTarget, kindValues: Map<TargetKind, Double>, exclude: Set<TargetKind>): TargetKind {
        val center = raw.value ?: raw.min?.let { lo -> raw.max?.let { hi -> (lo + hi) / 2 } }
        if (center != null && center > 0) {
            val match = kindValues.entries.firstOrNull { (kind, v) ->
                kind !in exclude && abs(v - center) <= max(1.0, center * 0.01)
            }?.key
            if (match != null) {
                raw.type?.let { learnedKinds[it] = match }
                return match
            }
        }
        return raw.type?.let { learnedKinds[it] }?.takeIf { it !in exclude } ?: TargetKind.UNKNOWN
    }

    companion object {
        /** Largest countdown step accepted as one tick; bigger jumps are skips / gaps. */
        const val MAX_TICK_MS = 15_000L

        /** A step that ended with at most this much left on the clock ran out naturally. */
        const val NATURAL_END_MS = 1_500L

        /** A countdown rising by more than this restarted its interval. */
        const val RESTART_SLACK_MS = 1_500L

        /**
         * Resolve a raw target into a range plus status. Single-value targets get
         * Karoo's implied band, and so do ramps: their min / max are the ramp's ends,
         * the rider is held to the current value. Status compares the *displayed*
         * (rounded) numbers so the color never contradicts the digits on screen.
         */
        fun resolve(raw: RawTarget, kind: TargetKind, smoothedOutput: Boolean, ramp: Boolean = false): Target? {
            val lo = raw.min
            val hi = raw.max
            val bounded = lo != null && hi != null && hi > lo && hi > 0
            val value = raw.value?.takeIf { it > 0 }
            val isRamp = ramp || (bounded && value != null && (value <= lo!! || value >= hi!!))
            val hasRange = bounded && !(isRamp && value != null)
            val center = value ?: if (bounded) (lo!! + hi!!) / 2 else return null
            val min = if (hasRange) lo!! else center * (1 - kind.impliedRangeFraction)
            val max = if (hasRange) hi!! else center * (1 + kind.impliedRangeFraction)
            val output = if (smoothedOutput) raw.outputSmoothed ?: raw.output else raw.output ?: raw.outputSmoothed
            val shown = output?.roundToInt()
            val status = when {
                shown == null -> TargetStatus.NO_DATA
                shown < min.roundToInt() -> TargetStatus.UNDER
                shown > max.roundToInt() -> TargetStatus.OVER
                else -> TargetStatus.IN_RANGE
            }
            return Target(
                kind = kind,
                value = center,
                min = min,
                max = max,
                implied = !hasRange,
                ramp = isRamp,
                output = output,
                status = status,
            )
        }
    }
}
