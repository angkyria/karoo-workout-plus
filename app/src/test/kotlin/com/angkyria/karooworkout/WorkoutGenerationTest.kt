package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryEntry
import com.angkyria.karooworkout.data.NextList
import com.angkyria.karooworkout.data.PlanTracker
import com.angkyria.karooworkout.data.RawTarget
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.WorkoutEngine
import com.angkyria.karooworkout.data.WorkoutGeneration
import com.angkyria.karooworkout.data.WorkoutSnapshot
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.data.expand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutGenerationTest {
    private fun shown(count: Int = 9) = WorkoutUiState.Shown(
        stepIndex = 0, stepCount = count, stepRemainingMs = 60_000, stepPlannedMs = 60_000,
        totalRemainingMs = null, elapsedMs = 0, primary = null, secondary = null, scalePercent = 100,
        history = emptyList(), workoutInRangePercent = null, paused = false, complete = false,
    )

    @Test
    fun `the engine's epoch starts a new generation while a workout stays shown`() {
        val g = WorkoutGeneration()
        assertFalse(g.update(shown(), demoMode = false, engineEpoch = 1))
        assertFalse(g.update(shown(), demoMode = false, engineEpoch = 1))
        assertTrue(g.update(shown(), demoMode = false, engineEpoch = 2))
        assertFalse(g.update(shown(), demoMode = false, engineEpoch = 2))
        // the older triggers still apply
        assertTrue(g.update(shown(count = 5), demoMode = false, engineEpoch = 2))
        assertTrue(g.update(WorkoutUiState.Hidden, demoMode = false, engineEpoch = 2))
    }

    @Test
    fun `the library is read once per generation and only when wanted`() {
        val g = WorkoutGeneration()
        g.update(shown(), demoMode = false, engineEpoch = 1)
        assertFalse(g.needsRead(enabled = false))
        assertTrue(g.needsRead(enabled = true))
        assertFalse(g.needsRead(enabled = true))
        g.update(shown(), demoMode = false, engineEpoch = 2)
        assertTrue(g.needsRead(enabled = true))
    }

    // ---------------------------------------- engine + tracker, as the extension wires them

    private val a = todayWorkout("a")

    /** Same nine steps, another first target: a different workout with the same count. */
    private val b = a.copy(
        id = "b",
        name = "Other",
        structure = a.expand().map {
            LibraryEntry.Step(it.name, it.lengthMs, if (it.index == 0) it.target?.copy(min = 100.0, max = 110.0) else it.target)
        },
    )
    private val lengths = a.expand().map { it.lengthMs!! }

    private val engine = WorkoutEngine()
    private val generation = WorkoutGeneration()
    private val tracker = PlanTracker()

    private fun frame(snap: WorkoutSnapshot, now: Long): NextList? {
        val state = engine.update(snap, now, paused = false, smoothedOutput = true)
        if (generation.update(state, demoMode = false, engineEpoch = engine.epoch)) tracker.newWorkout()
        if (generation.needsRead(enabled = true)) tracker.onLibrary(listOf(a, b))
        return (state as? WorkoutUiState.Shown)?.let { tracker.update(it, watchedStart = false, todayStartMs = TODAY) }
    }

    private fun snap(index: Int, remaining: Long, min: Double, max: Double) = WorkoutSnapshot(
        stepIndex = index,
        stepCount = 9,
        stepRemainingMs = remaining,
        totalRemainingMs = remaining + lengths.drop(index + 1).sum(),
        primary = RawTarget(type = 7, value = (min + max) / 2, min = min, max = max, output = min, outputSmoothed = min),
        kindValues = mapOf(TargetKind.POWER to (min + max) / 2),
    )

    @Test
    fun `a held finished workout followed by one with the same count is matched afresh`() {
        assertTrue(frame(snap(0, 1_100_000, 151.0, 159.0), 0) is NextList.Planned)
        assertEquals("a", tracker.matched?.id)
        frame(snap(8, 2_000, 133.0, 141.0), 1_000)
        frame(snap(8, 1_000, 133.0, 141.0), 2_000)
        // Karoo drops the finished workout: the summary is held, no Hidden frame
        frame(WorkoutSnapshot(stepCount = 0), 3_000)
        assertTrue(frame(snap(0, 1_100_000, 100.0, 110.0), 4_000) is NextList.Planned)
        assertEquals("b", tracker.matched?.id)
    }

    @Test
    fun `back to the start from deep inside is matched afresh`() {
        frame(snap(0, 1_100_000, 151.0, 159.0), 0)
        frame(snap(5, 100_000, 220.0, 232.0), 1_000)
        assertEquals("a", tracker.matched?.id)
        frame(snap(0, 1_100_000, 100.0, 110.0), 2_000)
        assertEquals("b", tracker.matched?.id)
    }
}
