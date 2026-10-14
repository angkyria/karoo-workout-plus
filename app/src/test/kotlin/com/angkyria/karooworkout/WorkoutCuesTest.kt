package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.Beep
import com.angkyria.karooworkout.data.Flash
import com.angkyria.karooworkout.data.IntervalRecord
import com.angkyria.karooworkout.data.Target
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.TargetStatus
import com.angkyria.karooworkout.data.WorkoutCues
import com.angkyria.karooworkout.data.WorkoutUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutCuesTest {

    private fun rec(index: Int, actualMs: Long = 60_000, natural: Boolean = false) = IntervalRecord(
        index = index, plannedMs = 60_000, actualMs = actualMs, kind = TargetKind.POWER,
        startLevel = 210.0, endLevel = 210.0, inRangeMs = actualMs, underMs = 0, overMs = 0,
        avgOutput = 212.0, targetMin = 200.0, targetMax = 220.0, endedNaturally = natural,
    )

    private fun target(status: TargetStatus) =
        Target(TargetKind.POWER, 210.0, 200.0, 220.0, implied = false, ramp = false, output = 210.0, status = status)

    private fun shown(
        index: Int,
        remaining: Long?,
        planned: Long = 60_000,
        paused: Boolean = false,
        status: TargetStatus = TargetStatus.IN_RANGE,
        history: List<IntervalRecord> = (0..index).map { rec(it, natural = it < index) },
        complete: Boolean = false,
    ) = WorkoutUiState.Shown(
        stepIndex = index, stepCount = 5, stepRemainingMs = remaining, stepPlannedMs = planned,
        totalRemainingMs = null, elapsedMs = 0, primary = target(status), secondary = null, scalePercent = null,
        history = history, workoutInRangePercent = null, paused = paused, complete = complete,
    )

    private val cues = WorkoutCues()

    private fun WorkoutCues.at(
        state: WorkoutUiState,
        now: Long,
        beeps: Boolean = true,
        offTarget: Boolean = true,
        recap: Boolean = true,
    ) = update(state, now, beeps, offTarget, recap)

    /** Countdown from [from] s to [to] s on step [index], one frame a second; returns beeps per second left. */
    private fun countdown(index: Int, from: Int, to: Int, planned: Long = 60_000): Map<Int, List<Beep>> =
        (from downTo to).associateWith { sec -> cues.at(shown(index, sec * 1000L, planned), (100 - sec) * 1000L).beeps }

    @Test
    fun doubleBeepAtTenThenFiveToOne() {
        val beeps = countdown(0, 15, 1).filterValues { it.isNotEmpty() }
        assertEquals(
            mapOf(
                10 to listOf(Beep.WARNING),
                5 to listOf(Beep.TICK), 4 to listOf(Beep.TICK), 3 to listOf(Beep.TICK),
                2 to listOf(Beep.TICK), 1 to listOf(Beep.TICK),
            ),
            beeps,
        )
    }

    @Test
    fun longBeepAndRecapWhenTheNextIntervalStarts() {
        countdown(0, 3, 1)
        val frame = cues.at(shown(1, 60_000), 100_000)
        assertEquals(listOf(Beep.START), frame.beeps)
        assertEquals(0, frame.recap!!.index)
        assertNotNull(cues.at(shown(1, 59_000), 107_000).recap)
        assertNull(cues.at(shown(1, 51_000), 108_100).recap)
    }

    @Test
    fun aSkipBeepsNothingAndHasNoRecap() {
        cues.at(shown(0, 40_000), 0)
        // the step was skipped with 40 s left: not ended naturally
        val frame = cues.at(shown(1, 60_000, history = listOf(rec(0), rec(1))), 1_000)
        assertTrue(frame.beeps.isEmpty())
        assertNull(frame.recap)
    }

    @Test
    fun aJumpOverThresholdsDoesNotBurst() {
        cues.at(shown(0, 12_000), 0)
        assertTrue(cues.at(shown(0, 3_000), 1_000).beeps.isEmpty())
    }

    @Test
    fun twoFramesInTheSameSecondBeepOnce() {
        cues.at(shown(0, 6_000), 0)
        assertEquals(listOf(Beep.TICK), cues.at(shown(0, 5_000), 1_000).beeps)
        assertTrue(cues.at(shown(0, 5_000), 1_250).beeps.isEmpty())
        assertTrue(cues.at(shown(0, 5_000), 1_500).beeps.isEmpty())
    }

    @Test
    fun pausedOpenAndShortStepsStayQuiet() {
        cues.at(shown(0, 6_000, paused = true), 0)
        assertTrue(cues.at(shown(0, 5_000, paused = true), 1_000).beeps.isEmpty())

        val open = WorkoutCues()
        open.at(shown(0, null), 0)
        assertTrue(open.at(shown(0, null), 1_000).beeps.isEmpty())

        val short = WorkoutCues()
        short.at(shown(0, 11_000, planned = 10_000), 0)
        assertTrue(short.at(shown(0, 10_000, planned = 10_000), 1_000).beeps.isEmpty())
    }

    @Test
    fun countdownSettingOffSilencesButStillPulses() {
        cues.at(shown(0, 6_000), 0, beeps = false)
        val frame = cues.at(shown(0, 5_000), 1_000, beeps = false)
        assertTrue(frame.beeps.isEmpty())
        assertTrue(frame.pulse)
        assertEquals(1_400L, frame.nextChangeAtMs) // the flash ends first, then the pulse
        assertFalse(cues.at(shown(0, 5_000), 1_520, beeps = false).pulse)
    }

    @Test
    fun workoutEndGetsTheLongBeepAndNoRecap() {
        cues.at(shown(4, 1_000), 0)
        val frame = cues.at(shown(4, 0, complete = true), 1_000)
        assertEquals(listOf(Beep.START), frame.beeps)
        assertNull(frame.recap)
        assertTrue(cues.at(shown(4, 0, complete = true), 2_000).beeps.isEmpty())
    }

    @Test
    fun recapSettingOffHidesTheCardButKeepsTheBeep() {
        cues.at(shown(0, 1_000), 0, recap = false)
        val frame = cues.at(shown(1, 60_000), 1_000, recap = false)
        assertEquals(listOf(Beep.START), frame.beeps)
        assertNull(frame.recap)
    }

    @Test
    fun offTargetAfterFifteenSecondsThenEveryThirty() {
        val alerts = mutableListOf<Pair<Int, Beep>>()
        for (sec in 0..80) {
            // interval well past its first 10 s
            val s = shown(0, 300_000L - sec * 1000, status = TargetStatus.UNDER,
                history = listOf(rec(0, actualMs = 20_000L + sec * 1000)))
            cues.at(s, sec * 1000L).beeps.forEach { alerts += sec to it }
        }
        assertEquals(listOf(15 to Beep.UNDER, 45 to Beep.UNDER, 75 to Beep.UNDER), alerts)
    }

    @Test
    fun offTargetStreakResetsWhenBackInRangeAndWaitsOutTheIntervalStart() {
        var beeps = 0
        for (sec in 0..40) {
            val status = if (sec == 10) TargetStatus.IN_RANGE else TargetStatus.OVER
            val s = shown(0, 300_000L - sec * 1000, status = status,
                history = listOf(rec(0, actualMs = 20_000L + sec * 1000)))
            beeps += cues.at(s, sec * 1000L).beeps.count { it == Beep.OVER }
        }
        assertEquals(1, beeps) // at 25 s: the streak restarted at 11

        val fresh = WorkoutCues()
        var early = 0
        for (sec in 0..20) {
            val s = shown(0, 300_000L - sec * 1000, status = TargetStatus.OVER,
                history = listOf(rec(0, actualMs = sec * 1000L)))
            early += fresh.at(s, sec * 1000L).beeps.size
        }
        assertEquals(0, early) // the streak only counts from 10 s into the interval
    }

    @Test
    fun offTargetIsOffWhenTheSettingIs() {
        var beeps = 0
        for (sec in 0..40) {
            val s = shown(0, 300_000L - sec * 1000, status = TargetStatus.UNDER,
                history = listOf(rec(0, actualMs = 20_000L + sec * 1000)))
            beeps += cues.at(s, sec * 1000L, offTarget = false).beeps.size
        }
        assertEquals(0, beeps)
    }

    @Test
    fun countdownFlashesAmberAndTheStartGreenEvenWithoutBeeps() {
        cues.at(shown(0, 6_000), 0, beeps = false)
        val tick = cues.at(shown(0, 5_000), 1_000, beeps = false)
        assertEquals(Flash.COUNTDOWN, tick.flash)
        assertNull(cues.at(shown(0, 5_000), 1_450, beeps = false).flash)
        cues.at(shown(0, 1_000), 5_000, beeps = false)
        val start = cues.at(shown(1, 60_000), 6_000, beeps = false)
        assertEquals(Flash.GO, start.flash)
        assertFalse(start.pulse)
        assertEquals(6_400L, start.nextChangeAtMs)
    }

    @Test
    fun aSkipDoesNotFlash() {
        cues.at(shown(0, 40_000), 0)
        assertNull(cues.at(shown(1, 60_000, history = listOf(rec(0), rec(1))), 1_000).flash)
    }

    @Test
    fun hiddenResetsEverything() {
        cues.at(shown(0, 6_000), 0)
        cues.at(WorkoutUiState.Hidden, 500)
        assertTrue(cues.at(shown(0, 5_000), 1_000).beeps.isEmpty())
    }
}
