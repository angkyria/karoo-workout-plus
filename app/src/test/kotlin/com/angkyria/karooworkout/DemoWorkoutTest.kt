package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.PowerAverage
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
}
