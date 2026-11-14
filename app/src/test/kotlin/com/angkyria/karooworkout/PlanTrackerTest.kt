package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryEntry
import com.angkyria.karooworkout.data.LibraryWorkout
import com.angkyria.karooworkout.data.NextList
import com.angkyria.karooworkout.data.PlanTracker
import com.angkyria.karooworkout.data.Target
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.TargetStatus
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.data.expand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTrackerTest {
    private val plan = todayWorkout().expand()
    private val lengths = plan.map { it.lengthMs!! }

    /** Range of the planned interval [index], the one Karoo streams when the plan is right. */
    private fun planned(index: Int) = plan[index].target!!.let { it.min!! to it.max!! }

    private fun shown(
        index: Int,
        remainingMs: Long,
        min: Double? = planned(index).first,
        max: Double? = planned(index).second,
        implied: Boolean = false,
        scale: Int = 100,
        count: Int = plan.size,
        totalRemainingMs: Long = remainingMs + lengths.drop(index + 1).sum(),
        elapsedMs: Long = lengths.take(index).sum() + lengths[index] - remainingMs,
    ) = WorkoutUiState.Shown(
        stepIndex = index,
        stepCount = count,
        stepRemainingMs = remainingMs,
        stepPlannedMs = lengths.getOrNull(index),
        totalRemainingMs = totalRemainingMs,
        elapsedMs = elapsedMs,
        primary = if (min == null || max == null) null
        else Target(TargetKind.POWER, (min + max) / 2, min, max, implied, false, null, TargetStatus.NO_DATA),
        secondary = null,
        scalePercent = scale,
        history = emptyList(),
        workoutInRangePercent = null,
        paused = false,
        complete = false,
    )

    private fun tracker(): PlanTracker = PlanTracker().apply { onLibrary(listOf(todayWorkout())) }
    private fun PlanTracker.at(s: WorkoutUiState.Shown) = update(s, watchedStart = false, todayStartMs = TODAY)

    @Test
    fun `no library gives the unknown row`() {
        val t = PlanTracker().apply { onLibrary(null) }
        val s = shown(0, 1_000_000)
        assertEquals(NextList.Unknown(8, s.totalRemainingMs!! - 1_000_000), t.at(s))
    }

    @Test
    fun `rows list every upcoming interval`() {
        val rows = (tracker().at(shown(0, 1_000_000)) as NextList.Planned).rows
        assertEquals(8, rows.size)
        assertEquals(1, rows[0].index)
        assertEquals(900_000L, rows[0].lengthMs)
        assertEquals(1_000_000L, rows[0].startsInMs)
        assertEquals(1_900_000L, rows[1].startsInMs)
    }

    @Test
    fun `skip re-anchors rows`() {
        val t = tracker()
        t.at(shown(0, 1_000_000))
        val rows = (t.at(shown(3, 900_000)) as NextList.Planned).rows
        assertEquals(4, rows[0].index)
        assertEquals(900_000L, rows[0].startsInMs)
    }

    @Test
    fun `open step makes later starts unknown`() {
        val open = listOf(
            LibraryEntry.Step("A", 600_000L, null),
            LibraryEntry.Step("B", 300_000L, null),
            LibraryEntry.Step("C", null, null),
            LibraryEntry.Step("D", 300_000L, null),
            LibraryEntry.Step("E", 300_000L, null),
        ).let { LibraryWorkout("open", "Open", null, null, it).expand() }
        val rows = PlanTracker.upcomingRows(open, shown(0, 500_000))
        assertEquals(4, rows.size)
        assertEquals(500_000L, rows[0].startsInMs)
        assertEquals(800_000L, rows[1].startsInMs)
        assertNull(rows[2].startsInMs)
        assertNull(rows[3].startsInMs)
    }

    @Test
    fun `two mismatched steps drop the plan`() {
        val t = tracker()
        t.at(shown(0, 1_000_000))
        assertTrue(t.at(shown(1, 800_000, min = 100.0, max = 110.0)) is NextList.Planned)
        assertTrue(t.at(shown(2, 200_000, min = 100.0, max = 110.0)) is NextList.Unknown)
        assertNull(t.matched)
    }

    @Test
    fun `an agreeing step resets the mismatch count`() {
        val t = tracker()
        t.at(shown(0, 1_000_000))
        t.at(shown(1, 800_000, min = 100.0, max = 110.0))
        t.at(shown(2, 200_000))
        assertTrue(t.at(shown(3, 800_000, min = 100.0, max = 110.0)) is NextList.Planned)
        assertNotNull(t.matched)
    }

    @Test
    fun `first sample of a step is not judged`() {
        val t = tracker()
        t.at(shown(0, 1_000_000))
        // The new step's first samples still carry the previous target.
        t.at(shown(1, 899_000, min = 151.0, max = 159.0))
        assertTrue(t.at(shown(2, 299_000, min = 220.0, max = 232.0)) is NextList.Planned)
        assertNotNull(t.matched)
    }

    @Test
    fun `difficulty scales the rows`() {
        val t = tracker()
        val rows = (t.at(shown(0, 1_000_000, min = 135.9, max = 143.1, scale = 90)) as NextList.Planned).rows
        assertEquals(198.0, rows[0].target!!.min!!, 0.001)
        assertEquals(208.8, rows[0].target!!.max!!, 0.001)
    }

    @Test
    fun `new workout resets`() {
        val t = tracker()
        t.at(shown(0, 1_000_000))
        assertNotNull(t.matched)
        t.at(shown(0, 1_000_000, count = 5))
        assertNull(t.matched)
    }
}
