package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.PlanMatcher
import com.angkyria.karooworkout.data.PowerAverage
import com.angkyria.karooworkout.data.RunObservation
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.TargetStatus
import com.angkyria.karooworkout.data.WorkoutEngine
import com.angkyria.karooworkout.data.WorkoutStreams
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.debug.DemoWorkout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo goes through the real parse + engine path, end to end. */
class DemoWorkoutTest {

    @Test
    fun demoRunsThroughTheRealPipeline() {
        val demo = DemoWorkout(ftp = 250)
        val engine = WorkoutEngine()
        val statuses = mutableSetOf<TargetStatus>()
        val kinds = mutableSetOf<TargetKind>()
        var maxIndex = 0
        var last: WorkoutUiState.Shown? = null
        // the demo workout is 530 s long: stay inside one lap of it
        repeat(500) { second ->
            val state = engine.update(WorkoutStreams.snapshot(demo.raw()), second * 1000L, false, true)
            val shown = state as WorkoutUiState.Shown
            shown.primary?.let {
                statuses += it.status
                kinds += it.kind
            }
            shown.secondary?.let { kinds += it.kind }
            maxIndex = maxOf(maxIndex, shown.stepIndex)
            last = shown
            demo.tick(1000)
        }
        assertTrue(statuses.containsAll(listOf(TargetStatus.UNDER, TargetStatus.IN_RANGE, TargetStatus.OVER)))
        assertTrue(kinds.containsAll(listOf(TargetKind.POWER, TargetKind.CADENCE)))
        assertTrue(maxIndex >= 8)
        // history accounts for every demo second ridden so far
        val shown = last!!
        assertEquals(shown.stepIndex + 1, shown.history.size)
        assertEquals(499_000L, shown.elapsedMs)
    }

    @Test
    fun demoPowerFollowsTheChosenAverage() {
        val demo = DemoWorkout(ftp = 250)
        repeat(30) { demo.tick(1000) }
        fun primary(avg: PowerAverage) = (
            WorkoutEngine().update(WorkoutStreams.snapshot(demo.raw(), avg), 0, false, true, avg)
                as WorkoutUiState.Shown
            ).primary!!
        val five = primary(PowerAverage.S5)
        val ten = primary(PowerAverage.S10)
        assertEquals(5, five.averageSeconds)
        assertEquals(10, ten.averageSeconds)
        assertTrue(five.output != ten.output)
        assertEquals(null, primary(PowerAverage.S3).averageSeconds)
    }

    @Test
    fun `demo library workout matches the demo run`() {
        val demo = DemoWorkout(ftp = 250)
        val engine = WorkoutEngine()
        val states = (0 until 5).map { second ->
            val s = engine.update(WorkoutStreams.snapshot(demo.raw()), second * 1000L, false, true) as WorkoutUiState.Shown
            demo.tick(1000)
            s
        }
        fun run(s: WorkoutUiState.Shown, watchedStart: Boolean) = RunObservation(
            stepCount = s.stepCount,
            stepIndex = s.stepIndex,
            stepRemainingMs = s.stepRemainingMs,
            totalRemainingMs = s.totalRemainingMs,
            elapsedMs = s.elapsedMs,
            watchedStart = watchedStart,
            targetMin = s.primary?.min,
            targetMax = s.primary?.max,
            targetImplied = s.primary?.implied ?: false,
            scalePercent = s.scalePercent,
        )
        val library = listOf(demo.libraryWorkout())
        // the brief's case: 5 s in, a watched start (late read falls back to the late rules)
        assertTrue(PlanMatcher.match(library, run(states[4], watchedStart = true), 0L) != null)
        assertTrue(PlanMatcher.match(library, run(states[1], watchedStart = true), 0L) != null)
        // and a run picked up mid-way matches as well
        assertTrue(PlanMatcher.match(library, run(states[4], watchedStart = false), 0L) != null)
    }
}
