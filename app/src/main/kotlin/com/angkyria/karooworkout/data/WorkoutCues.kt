package com.angkyria.karooworkout.data

/** A sound the Karoo plays; [tones] are (frequency Hz or null for silence, duration ms). */
enum class Beep(val tones: List<Pair<Int?, Int>>) {
    /** 10 s left: a double beep. */
    WARNING(listOf(1000 to 100, null to 80, 1000 to 100)),

    /** 5, 4, 3, 2, 1 s left. */
    TICK(listOf(880 to 150)),

    /** The next interval (or the end of the workout) is here. */
    START(listOf(1320 to 600)),

    /** Out of range, under the target, for a while: low double. */
    UNDER(listOf(520 to 160, null to 90, 520 to 160)),

    /** Out of range, over the target: high double. */
    OVER(listOf(1600 to 160, null to 90, 1600 to 160)),
}

/** A screen flash with the beeps: amber while counting down, green as the next interval starts. */
enum class Flash { COUNTDOWN, GO }

/** What one frame asks for: sounds to play now, and what the view draws. */
data class CueFrame(
    val beeps: List<Beep> = emptyList(),
    /** The interval bar flashes (countdown seconds and the 10 s warning). */
    val pulse: Boolean = false,
    /** The screen flashes (frame on the page / drawer, the chip's background). */
    val flash: Flash? = null,
    /** The interval that just ran out, for the recap card. */
    val recap: IntervalRecord? = null,
    /** When the drawn cues change by themselves (pulse off, recap gone): render again then. */
    val nextChangeAtMs: Long? = null,
)

/**
 * Interval cues from successive [WorkoutUiState]s: the countdown (a double beep at
 * 10 s, a beep at 5..1, a long one as the next interval starts), the bar pulse, the
 * recap of the interval that ran out, and the off-target alert. Pure: the
 * extension feeds it every render and plays what comes back.
 *
 * Renders come up to 4x a second and the countdown moves about once a second, so
 * every cue fires on a *crossing* between two frames, never on an exact value; a
 * countdown jump bigger than [MAX_STEP_MS] (skip, scrubbing) crosses nothing.
 */
class WorkoutCues {

    private var lastIndex: Int? = null
    private var lastRemaining: Long? = null
    private var lastNowMs: Long? = null
    private var lastComplete = false
    private var pulseUntilMs = 0L
    private var flash: Flash? = null
    private var flashUntilMs = 0L
    private var recap: IntervalRecord? = null
    private var recapUntilMs = 0L

    private var offStatus: TargetStatus? = null
    private var offMs = 0L
    private var nextAlertMs = OFF_TARGET_AFTER_MS

    fun reset() {
        lastIndex = null
        lastRemaining = null
        lastNowMs = null
        lastComplete = false
        pulseUntilMs = 0
        flash = null
        flashUntilMs = 0
        recap = null
        recapUntilMs = 0
        resetOffTarget()
    }

    fun update(
        state: WorkoutUiState,
        nowMs: Long,
        countdownBeeps: Boolean,
        offTargetBeeps: Boolean,
        showRecap: Boolean,
    ): CueFrame {
        if (state !is WorkoutUiState.Shown) {
            reset()
            return CueFrame()
        }
        val beeps = mutableListOf<Beep>()
        val index = state.stepIndex
        val rem = state.stepRemainingMs
        val prevIndex = lastIndex
        val prevRem = lastRemaining
        val dt = lastNowMs?.let { (nowMs - it).coerceIn(0, MAX_FRAME_GAP_MS) } ?: 0L

        when {
            state.complete -> {
                // the last interval ran out: one long beep for the end of the workout
                if (!lastComplete && prevRem != null && prevRem <= WorkoutEngine.NATURAL_END_MS) {
                    beeps += Beep.START
                    flashFor(Flash.GO, nowMs)
                }
                recap = null
            }
            prevIndex != null && index > prevIndex -> {
                val ended = state.history.firstOrNull { it.index == prevIndex }
                if (ended?.endedNaturally == true && !state.paused) {
                    beeps += Beep.START
                    flashFor(Flash.GO, nowMs)
                    recap = ended
                    recapUntilMs = nowMs + RECAP_MS
                } else {
                    recap = null
                }
            }
            prevIndex != null && index < prevIndex -> recap = null
            index == prevIndex && rem != null && prevRem != null && !state.paused -> {
                val step = prevRem - rem
                if (step in 1..MAX_STEP_MS) {
                    val crossed = THRESHOLDS.filter { prevRem > it && rem <= it }
                    val last = crossed.minOrNull()
                    if (last != null && rem > 0) {
                        val warning = last == WARNING_MS
                        if (!warning || (state.stepPlannedMs ?: 0L) >= MIN_WARNED_STEP_MS) {
                            beeps += if (warning) Beep.WARNING else Beep.TICK
                            pulseUntilMs = nowMs + PULSE_MS
                            flashFor(Flash.COUNTDOWN, nowMs)
                        }
                    }
                }
            }
        }
        if (index != prevIndex) resetOffTarget()
        offTarget(state, dt)?.let { beeps += it }

        lastIndex = index
        lastRemaining = rem
        lastNowMs = nowMs
        lastComplete = state.complete

        val pulse = nowMs < pulseUntilMs
        val shownFlash = flash?.takeIf { nowMs < flashUntilMs }
        val shownRecap = recap?.takeIf { showRecap && nowMs < recapUntilMs }
        val next = listOfNotNull(
            pulseUntilMs.takeIf { pulse },
            flashUntilMs.takeIf { shownFlash != null },
            recapUntilMs.takeIf { shownRecap != null },
        ).minOrNull()
        return CueFrame(
            beeps = beeps.filter { if (it == Beep.UNDER || it == Beep.OVER) offTargetBeeps else countdownBeeps },
            pulse = pulse,
            flash = shownFlash,
            recap = shownRecap,
            nextChangeAtMs = next,
        )
    }

    private fun flashFor(kind: Flash, nowMs: Long) {
        flash = kind
        flashUntilMs = nowMs + FLASH_MS
    }

    /** Off target without a break for [OFF_TARGET_AFTER_MS], then every [OFF_TARGET_REPEAT_MS]. */
    private fun offTarget(state: WorkoutUiState.Shown, dt: Long): Beep? {
        val status = state.primary?.status
        if (status != TargetStatus.UNDER && status != TargetStatus.OVER || state.complete) {
            resetOffTarget()
            return null
        }
        if (status != offStatus) {
            resetOffTarget()
            offStatus = status
        }
        // ride paused: the streak waits; the interval's opening seconds don't count
        if (state.paused || (state.current?.actualMs ?: 0L) < SETTLE_MS) return null
        offMs += dt
        if (offMs < nextAlertMs) return null
        nextAlertMs = offMs + OFF_TARGET_REPEAT_MS
        return if (status == TargetStatus.UNDER) Beep.UNDER else Beep.OVER
    }

    private fun resetOffTarget() {
        offStatus = null
        offMs = 0
        nextAlertMs = OFF_TARGET_AFTER_MS
    }

    companion object {
        const val WARNING_MS = 10_000L
        val THRESHOLDS = listOf(WARNING_MS, 5_000L, 4_000L, 3_000L, 2_000L, 1_000L)

        /** No 10 s warning on steps too short for it to mean anything. */
        const val MIN_WARNED_STEP_MS = 12_000L

        /** Largest countdown move between two frames that still sounds its cues. */
        const val MAX_STEP_MS = 3_000L
        const val PULSE_MS = 500L
        const val FLASH_MS = 400L
        const val RECAP_MS = 8_000L

        const val OFF_TARGET_AFTER_MS = 15_000L
        const val OFF_TARGET_REPEAT_MS = 30_000L

        /** An interval's first seconds (and a ramp's start) never raise the off-target alert. */
        const val SETTLE_MS = 10_000L

        /** A longer gap between frames (service stalled) counts as this much. */
        private const val MAX_FRAME_GAP_MS = 2_000L
    }
}
