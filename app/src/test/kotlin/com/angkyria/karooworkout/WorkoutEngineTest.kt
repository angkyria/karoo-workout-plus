package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.RawTarget
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.TargetStatus
import com.angkyria.karooworkout.data.WorkoutEngine
import com.angkyria.karooworkout.data.WorkoutSnapshot
import com.angkyria.karooworkout.data.WorkoutUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEngineTest {

    private fun power(lo: Double, hi: Double, out: Double?) =
        RawTarget(type = 7, value = (lo + hi) / 2, min = lo, max = hi, output = out, outputSmoothed = out)

    private fun snap(
        index: Int,
        stepRemaining: Long?,
        totalRemaining: Long? = null,
        count: Int = 5,
        primary: RawTarget? = power(200.0, 220.0, 210.0),
        secondary: RawTarget? = null,
        kinds: Map<TargetKind, Double> = mapOf(TargetKind.POWER to 210.0),
    ) = WorkoutSnapshot(
        stepIndex = index,
        stepCount = count,
        stepRemainingMs = stepRemaining,
        totalRemainingMs = totalRemaining,
        primary = primary,
        secondary = secondary,
        kindValues = kinds,
    )

    private fun WorkoutEngine.tick(s: WorkoutSnapshot, now: Long = 0, paused: Boolean = false) =
        update(s, now, paused, smoothedOutput = true) as WorkoutUiState.Shown

    // ------------------------------------------------------------ targets

    @Test
    fun singleValuePowerTargetGetsImpliedFivePercentBand() {
        val t = WorkoutEngine.resolve(RawTarget(value = 200.0, output = 205.0), TargetKind.POWER, true)!!
        assertEquals(190.0, t.min, 1e-9)
        assertEquals(210.0, t.max, 1e-9)
        assertTrue(t.implied)
        assertEquals(TargetStatus.IN_RANGE, t.status)
    }

    @Test
    fun singleValueHeartRateTargetGetsImpliedSevenAndAHalfPercentBand() {
        val t = WorkoutEngine.resolve(RawTarget(value = 160.0, min = 0.0, max = 0.0), TargetKind.HEART_RATE, true)!!
        assertEquals(148.0, t.min, 1e-9)
        assertEquals(172.0, t.max, 1e-9)
    }

    @Test
    fun statusFollowsTheDisplayedRoundedNumbers() {
        // 99.6 shows as "100" — must not be blue next to a "100-125" range
        val t = WorkoutEngine.resolve(RawTarget(min = 100.0, max = 125.0, output = 99.6), TargetKind.POWER, true)!!
        assertEquals(TargetStatus.IN_RANGE, t.status)
        val under = WorkoutEngine.resolve(RawTarget(min = 100.0, max = 125.0, output = 99.4), TargetKind.POWER, true)!!
        assertEquals(TargetStatus.UNDER, under.status)
        val over = WorkoutEngine.resolve(RawTarget(min = 100.0, max = 125.0, output = 126.0), TargetKind.POWER, true)!!
        assertEquals(TargetStatus.OVER, over.status)
    }

    @Test
    fun rampHoldsTheRiderToItsCurrentValueNotTheWholeRange() {
        // Karoo 2, warm-up ramp 137 -> 187 W: value = current point, min / max = ends
        val engine = WorkoutEngine()
        val start = engine.tick(snap(0, 720_000, primary = RawTarget(value = 137.0, min = 137.0, max = 187.0, output = 140.0)))
        val t = start.primary!!
        assertTrue(t.ramp)
        assertEquals(130.15, t.min, 1e-9)
        assertEquals(143.85, t.max, 1e-9)
        assertEquals(TargetStatus.IN_RANGE, t.status)
        // the graph knows the whole ramp from its first sight
        assertEquals(137.0, start.current!!.startLevel!!, 1e-9)
        assertEquals(187.0, start.current!!.endLevel!!, 1e-9)

        // half way up the value sits inside min..max — still a ramp, not a band
        val mid = engine.tick(snap(0, 719_000, primary = RawTarget(value = 162.0, min = 137.0, max = 187.0, output = 140.0)))
        assertEquals(153.9, mid.primary!!.min, 1e-9)
        assertEquals(TargetStatus.UNDER, mid.primary!!.status)
        assertEquals(187.0, mid.current!!.endLevel!!, 1e-9)
    }

    @Test
    fun rangeWithItsValueInTheMiddleStaysARange() {
        val t = WorkoutEngine.resolve(RawTarget(value = 400.0, min = 390.0, max = 410.0, output = 395.0), TargetKind.POWER, true)!!
        assertFalse(t.ramp)
        assertFalse(t.implied)
        assertEquals(390.0, t.min, 1e-9)
        assertEquals(410.0, t.max, 1e-9)
    }

    @Test
    fun missingOutputIsNoDataAndMissingTargetIsNoTarget() {
        val t = WorkoutEngine.resolve(RawTarget(min = 100.0, max = 125.0), TargetKind.POWER, true)!!
        assertEquals(TargetStatus.NO_DATA, t.status)
        assertNull(WorkoutEngine.resolve(RawTarget(value = 0.0, min = 0.0, max = 0.0), TargetKind.POWER, true))
    }

    @Test
    fun smoothedOrInstantOutputPerSetting() {
        val raw = RawTarget(min = 100.0, max = 125.0, output = 90.0, outputSmoothed = 110.0)
        assertEquals(110.0, WorkoutEngine.resolve(raw, TargetKind.POWER, smoothedOutput = true)!!.output!!, 1e-9)
        assertEquals(90.0, WorkoutEngine.resolve(raw, TargetKind.POWER, smoothedOutput = false)!!.output!!, 1e-9)
    }

    @Test
    fun kindsComeFromThePerKindTargetStreams() {
        val engine = WorkoutEngine()
        val state = engine.tick(
            snap(
                0, 60_000,
                primary = power(200.0, 220.0, 210.0),
                secondary = RawTarget(type = 3, value = 90.0, min = 85.0, max = 95.0, output = 88.0),
                kinds = mapOf(TargetKind.POWER to 210.0, TargetKind.CADENCE to 90.0),
            ),
        )
        assertEquals(TargetKind.POWER, state.primary!!.kind)
        assertEquals(TargetKind.CADENCE, state.secondary!!.kind)
    }

    @Test
    fun primaryIsNeverCadenceAndKindsAreRememberedByTypeId() {
        val engine = WorkoutEngine()
        // a 90 W target that happens to equal the 90 rpm cadence target
        val first = engine.tick(
            snap(0, 60_000, primary = power(85.0, 95.0, 90.0), kinds = mapOf(TargetKind.CADENCE to 90.0, TargetKind.POWER to 90.0)),
        )
        assertEquals(TargetKind.POWER, first.primary!!.kind)
        // per-kind stream gone quiet: type id 7 was learned as power
        val later = engine.tick(snap(0, 59_000, primary = power(85.0, 95.0, 90.0), kinds = emptyMap()))
        assertEquals(TargetKind.POWER, later.primary!!.kind)
    }

    // ------------------------------------------------------- time keeping

    @Test
    fun countdownTicksAccrueIntervalTimeAndTimeInRange() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 60_000, 300_000))
        engine.tick(snap(0, 59_000, 299_000, primary = power(200.0, 220.0, 210.0)))
        val s = engine.tick(snap(0, 58_000, 298_000, primary = power(200.0, 220.0, 150.0)))
        val rec = s.current!!
        assertEquals(60_000L, rec.plannedMs)
        assertEquals(2_000L, rec.actualMs)
        assertEquals(1_000L, rec.inRangeMs)
        assertEquals(1_000L, rec.underMs)
        assertEquals(50, rec.inRangePercent)
        assertEquals(50, s.workoutInRangePercent)
        assertEquals(2_000L, s.elapsedMs)
    }

    @Test
    fun aFrozenCountdownWhilePausedAccruesNothing() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 60_000))
        engine.tick(snap(0, 60_000), now = 5_000, paused = true)
        val s = engine.tick(snap(0, 60_000), now = 10_000, paused = true)
        assertEquals(0L, s.current!!.actualMs)
        assertTrue(s.paused)
    }

    @Test
    fun countdownJumpsAreNotCountedAsRiding() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 120_000))
        val s = engine.tick(snap(0, 60_000)) // 60 s gap: missed ticks / skip, not riding
        assertEquals(0L, s.current!!.actualMs)
    }

    @Test
    fun naturalStepEndCreditsTheFinalPartialSecond() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 2_000))
        engine.tick(snap(0, 1_000))
        val s = engine.tick(snap(1, 30_000))
        assertEquals(2, s.history.size)
        assertEquals(2_000L, s.history[0].actualMs) // 1 s ticked + 1 s left at the switch
        assertEquals(30_000L, s.history[1].plannedMs)
        assertEquals(1, s.stepIndex)
    }

    @Test
    fun skippedStepKeepsOnlyTheTimeActuallyRidden() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 60_000))
        engine.tick(snap(0, 50_000))
        val s = engine.tick(snap(1, 30_000))
        assertEquals(10_000L, s.history[0].actualMs)
    }

    @Test
    fun rewindDropsTheIntervalsBeingRiddenAgain() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 3_000))
        engine.tick(snap(0, 2_000))
        engine.tick(snap(1, 30_000))
        engine.tick(snap(1, 29_000))
        val s = engine.tick(snap(0, 3_000))
        assertEquals(1, s.history.size)
        assertEquals(0, s.current!!.index)
        assertEquals(0L, s.current!!.actualMs)
    }

    @Test
    fun restartingTheCurrentIntervalStartsItOver() {
        val engine = WorkoutEngine()
        engine.tick(snap(1, 60_000))
        engine.tick(snap(1, 59_000))
        engine.tick(snap(1, 58_000))
        val s = engine.tick(snap(1, 60_000))
        assertEquals(0L, s.current!!.actualMs)
        assertEquals(60_000L, s.current!!.plannedMs)
        assertEquals(0f, s.stepProgress!!, 1e-6f)
    }

    @Test
    fun aDifferentWorkoutStartsAFreshHistory() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 3_000))
        engine.tick(snap(0, 2_000))
        engine.tick(snap(1, 3_000))
        engine.tick(snap(2, 3_000))
        val s = engine.tick(snap(0, 50_000)) // from step 3 back to 1: a new workout
        assertEquals(1, s.history.size)
        val other = engine.tick(snap(0, 50_000, count = 8)) // different step count
        assertEquals(1, other.history.size)
        assertEquals(8, other.stepCount)
    }

    @Test
    fun openStepCountsWallClockOnlyWhileRiding() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, null), now = 0)
        engine.tick(snap(0, null), now = 1_000)
        engine.tick(snap(0, null), now = 2_000, paused = true)
        val s = engine.tick(snap(0, null), now = 3_000)
        assertEquals(2_000L, s.current!!.actualMs)
        assertNull(s.stepProgress)
    }

    @Test
    fun progressAndUpcomingTime() {
        val engine = WorkoutEngine()
        engine.tick(snap(0, 60_000, 600_000))
        val s = engine.tick(snap(0, 45_000, 585_000))
        assertEquals(0.25f, s.stepProgress!!, 1e-6f)
        assertEquals(15_000L, s.elapsedMs)
        assertEquals(600_000L, s.totalMs)
        assertEquals(15_000f / 600_000f, s.workoutProgress!!, 1e-6f)
        assertEquals(540_000L, s.upcomingMs)
    }

    @Test
    fun completeOnlyWhenTheLastStepRanOut() {
        val engine = WorkoutEngine()
        assertFalse(engine.tick(snap(4, 1_000, 1_000)).complete)
        assertTrue(engine.tick(snap(4, 0, 0)).complete)
    }

    @Test
    fun noWorkoutLoadedIsHidden() {
        val engine = WorkoutEngine()
        val state = engine.update(WorkoutSnapshot(stepCount = 0), 0, false, true)
        assertEquals(WorkoutUiState.Hidden, state)
    }
}
