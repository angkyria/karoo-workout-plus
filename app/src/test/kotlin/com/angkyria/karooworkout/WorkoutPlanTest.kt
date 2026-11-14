package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryEntry
import com.angkyria.karooworkout.data.LibraryWorkout
import com.angkyria.karooworkout.data.expand
import com.angkyria.karooworkout.data.totalMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutPlanTest {
    @Test
    fun `repeats expand to the ride's nine intervals`() {
        val plan = todayWorkout().expand()
        assertEquals(9, plan.size)
        assertEquals(
            listOf("Warm up", "Climb", "Easy", "Climb", "Easy", "Climb", "Easy", "Endurance", "Skills"),
            plan.map { it.name },
        )
        assertEquals((0..8).toList(), plan.map { it.index })
    }

    @Test
    fun `total is the planned time`() {
        assertEquals(9_900_000L, todayWorkout().expand().totalMs)
    }

    @Test
    fun `open step has no total`() {
        val open = LibraryWorkout(
            "open", "Open", null, null,
            listOf(LibraryEntry.Step("Free ride", null, null)),
        )
        assertNull(open.expand().totalMs)
    }
}
