package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryEntry
import com.angkyria.karooworkout.data.LibraryTarget
import com.angkyria.karooworkout.data.LibraryWorkout
import com.angkyria.karooworkout.data.PlanMatcher
import com.angkyria.karooworkout.data.RunObservation
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.expand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanMatcherTest {
    private val plan = todayWorkout()

    private fun watched(
        count: Int = 9,
        remaining: Long? = 1_200_000,
        total: Long? = 9_900_000,
        min: Double? = 151.0,
        max: Double? = 159.0,
        scale: Int? = 100,
    ) = RunObservation(
        stepCount = count, stepIndex = 0, stepRemainingMs = remaining, totalRemainingMs = total,
        elapsedMs = 0, watchedStart = true, targetMin = min, targetMax = max,
        targetImplied = false, scalePercent = scale,
    )

    private val lateTotal = 400_000L + 300_000 + 900_000 + 300_000 + 3_900_000 + 1_200_000

    private fun late(remaining: Long? = 400_000, total: Long? = lateTotal) = RunObservation(
        stepCount = 9, stepIndex = 3, stepRemainingMs = remaining, totalRemainingMs = total,
        elapsedMs = 0, watchedStart = false, targetMin = 220.0, targetMax = 232.0,
        targetImplied = false, scalePercent = 100,
    )

    @Test fun `watched start matches today's workout`() {
        assertEquals(plan, PlanMatcher.match(listOf(plan), watched(), TODAY))
    }

    @Test fun `first length off by more than 2 s rejects`() {
        assertNull(PlanMatcher.match(listOf(plan), watched(remaining = 1_197_000), TODAY))
    }

    @Test fun `wrong count rejects`() {
        assertNull(PlanMatcher.match(listOf(plan), watched(count = 8), TODAY))
    }

    @Test fun `target range mismatch at step 0 rejects`() {
        assertNull(PlanMatcher.match(listOf(plan), watched(min = 140.0, max = 150.0), TODAY))
    }

    @Test fun `total off by more than 5 s rejects`() {
        assertNull(PlanMatcher.match(listOf(plan), watched(total = 9_890_000), TODAY))
    }

    @Test fun `late join matches from the current index`() {
        assertEquals(plan, PlanMatcher.match(listOf(plan), late(), TODAY))
    }

    @Test fun `late join rejects a remaining longer than the step`() {
        assertNull(PlanMatcher.match(listOf(plan), late(remaining = 950_000), TODAY))
    }

    private fun openPlan() = LibraryWorkout(
        "open", "Open", TODAY, TODAY,
        listOf(
            LibraryEntry.Step("A", 600_000L, LibraryTarget(TargetKind.POWER, null, 151.0, 159.0, false)),
            LibraryEntry.Step("B", null, LibraryTarget(TargetKind.POWER, null, 220.0, 232.0, false)),
            LibraryEntry.Step("C", 300_000L, LibraryTarget(TargetKind.POWER, null, 146.0, 154.0, false)),
        ),
    )

    @Test fun `watched start matches a plan with an open step`() {
        val run = watched(count = 3, remaining = 600_000, total = 5_000_000)
        assertEquals(openPlan(), PlanMatcher.match(listOf(openPlan()), run, TODAY))
        // Count and the first target range still apply.
        assertNull(PlanMatcher.match(listOf(openPlan()), run.copy(stepCount = 4), TODAY))
        assertNull(PlanMatcher.match(listOf(openPlan()), run.copy(targetMin = 100.0, targetMax = 110.0), TODAY))
    }

    @Test fun `late join matches with an open step ahead`() {
        val run = RunObservation(
            stepCount = 3, stepIndex = 0, stepRemainingMs = 400_000, totalRemainingMs = 9_000_000,
            elapsedMs = 0, watchedStart = false, targetMin = 151.0, targetMax = 159.0,
            targetImplied = false, scalePercent = 100,
        )
        assertEquals(openPlan(), PlanMatcher.match(listOf(openPlan()), run, TODAY))
        assertNull(PlanMatcher.match(listOf(openPlan()), run.copy(stepRemainingMs = 700_000), TODAY))
        assertNull(PlanMatcher.match(listOf(openPlan()), run.copy(targetMin = 100.0, targetMax = 110.0), TODAY))
    }

    @Test fun `identical twins pick today then latest update`() {
        val old = todayWorkout("old", plannedDateMs = TODAY - 3 * DAY, updatedAtMs = TODAY + 10)
        val now = todayWorkout("now", plannedDateMs = TODAY, updatedAtMs = TODAY)
        assertEquals("now", PlanMatcher.match(listOf(old, now), watched(), TODAY)?.id)
        assertEquals("now", PlanMatcher.match(listOf(now, old), watched(), TODAY)?.id)

        val a = todayWorkout("a", updatedAtMs = TODAY + 1)
        val b = todayWorkout("b", updatedAtMs = TODAY + 2)
        assertEquals("b", PlanMatcher.match(listOf(a, b), watched(), TODAY)?.id)
    }

    @Test fun `different structures that both fit give no plan`() {
        val other = differentAtFour("other", plannedDateMs = TODAY - 3 * DAY)
        val base = todayWorkout("base", plannedDateMs = TODAY - 3 * DAY)
        // Both fit step 0 and the totals; neither is dated today.
        assertNull(PlanMatcher.match(listOf(base, other), watched(), TODAY))
        // Both dated today.
        val bothToday = listOf(todayWorkout("base"), differentAtFour("other"))
        assertNull(PlanMatcher.match(bothToday, watched(), TODAY))
        // Dated today tells them apart.
        assertEquals("base", PlanMatcher.match(listOf(todayWorkout("base"), other), watched(), TODAY)?.id)
    }

    @Test fun `empty plan and zero total never match`() {
        val empty = LibraryWorkout("e", "Empty", TODAY, TODAY, emptyList())
        assertNull(PlanMatcher.match(listOf(empty), watched(count = 0, remaining = 0, total = 0), TODAY))
    }

    @Test fun `difficulty scales power for the range check`() {
        val planned = plan.expand()[0].target
        assertEquals(true, PlanMatcher.rangeAgrees(planned, 135.9, 143.1, false, 90))
        assertEquals(plan, PlanMatcher.match(listOf(plan), watched(min = 135.9, max = 143.1, scale = 90), TODAY))
    }

    @Test fun `implied band is not compared`() {
        assertNull(PlanMatcher.rangeAgrees(plan.expand()[0].target, 147.25, 162.75, implied = true, 100))
    }

    @Test fun `nothing to compare returns null`() {
        val t = plan.expand()[0].target
        assertNull(PlanMatcher.rangeAgrees(null, 1.0, 2.0, false, 100))
        assertNull(PlanMatcher.rangeAgrees(t?.copy(min = null, max = null), 1.0, 2.0, false, 100))
        assertNull(PlanMatcher.rangeAgrees(t, null, null, false, 100))
        assertTrue(PlanMatcher.rangeAgrees(t, 151.5, 158.5, false, 100) == true)
    }

    @Test fun `non-power targets are not scaled`() {
        val hr = LibraryTarget(TargetKind.HEART_RATE, null, 130.0, 140.0, false)
        assertEquals(true, PlanMatcher.rangeAgrees(hr, 130.0, 140.0, false, 90))
    }

    /** Same as [todayWorkout] but interval 4 (Easy #2 is index 4) targets differently. */
    private fun differentAtFour(id: String, plannedDateMs: Long? = TODAY): LibraryWorkout {
        val base = todayWorkout(id, plannedDateMs)
        val steps = base.expand().map {
            LibraryEntry.Step(it.name, it.lengthMs, if (it.index == 4) it.target?.copy(min = 100.0, max = 110.0) else it.target)
        }
        return base.copy(structure = steps)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
