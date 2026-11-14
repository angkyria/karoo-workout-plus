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
        elapsedMs = 0, watchedStart = true, targetKind = TargetKind.POWER, targetMin = min, targetMax = max,
        targetImplied = false, scalePercent = scale,
    )

    private val lateTotal = 400_000L + 300_000 + 900_000 + 300_000 + 3_900_000 + 1_200_000

    private fun late(remaining: Long? = 400_000, total: Long? = lateTotal) = RunObservation(
        stepCount = 9, stepIndex = 3, stepRemainingMs = remaining, totalRemainingMs = total,
        elapsedMs = 0, watchedStart = false, targetKind = TargetKind.POWER, targetMin = 220.0, targetMax = 232.0,
        targetImplied = false, scalePercent = 100,
    )

    @Test fun `watched start matches today's workout`() {
        assertEquals(plan, PlanMatcher.match(listOf(plan), watched(), TODAY))
    }

    @Test fun `first length off by more than 2 s rejects`() {
        assertNull(PlanMatcher.match(listOf(plan), watched(remaining = 1_190_000), TODAY))
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

    @Test fun `watched start falls back to the late rules`() {
        // 5 s into step 0: the strict first-length check fails, the late rules agree
        assertEquals(plan, PlanMatcher.match(listOf(plan), watched(remaining = 1_195_000, total = 9_895_000), TODAY))
        // watched, but a later index (late read after a step change)
        val atTwo = late(remaining = 200_000, total = 200_000L + 7_500_000).copy(stepIndex = 2, watchedStart = true, targetMin = 146.0, targetMax = 154.0)
        assertEquals(plan, PlanMatcher.match(listOf(plan), atTwo, TODAY))
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
            elapsedMs = 0, watchedStart = false, targetKind = TargetKind.POWER, targetMin = 151.0, targetMax = 159.0,
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
        assertEquals(true, PlanMatcher.rangeAgrees(planned, TargetKind.POWER, 135.9, 143.1, false, 90))
        assertEquals(plan, PlanMatcher.match(listOf(plan), watched(min = 135.9, max = 143.1, scale = 90), TODAY))
    }

    @Test fun `implied band is not compared`() {
        assertNull(PlanMatcher.rangeAgrees(plan.expand()[0].target, TargetKind.POWER, 147.25, 162.75, implied = true, 100))
    }

    @Test fun `nothing to compare returns null`() {
        val t = plan.expand()[0].target
        assertNull(PlanMatcher.rangeAgrees(null, TargetKind.POWER, 1.0, 2.0, false, 100))
        assertNull(PlanMatcher.rangeAgrees(t?.copy(min = null, max = null), TargetKind.POWER, 1.0, 2.0, false, 100))
        assertNull(PlanMatcher.rangeAgrees(t, TargetKind.POWER, null, null, false, 100))
        assertTrue(PlanMatcher.rangeAgrees(t, TargetKind.POWER, 151.5, 158.5, false, 100) == true)
    }

    @Test fun `non-power targets are not scaled`() {
        val hr = LibraryTarget(TargetKind.HEART_RATE, null, 130.0, 140.0, false)
        assertEquals(true, PlanMatcher.rangeAgrees(hr, TargetKind.HEART_RATE, 130.0, 140.0, false, 90))
    }

    @Test fun `a workout starting on an open step matches`() {
        val open = LibraryWorkout(
            "open-start", "Open start", TODAY, TODAY,
            listOf(
                LibraryEntry.Step("A", null, LibraryTarget(TargetKind.POWER, null, 151.0, 159.0, false)),
                LibraryEntry.Step("B", 300_000L, LibraryTarget(TargetKind.POWER, null, 220.0, 232.0, false)),
            ),
        )
        // an open step streams no countdown and no workout total
        val run = watched(count = 2, remaining = null, total = null)
        assertEquals(open, PlanMatcher.match(listOf(open), run, TODAY))
        assertEquals(open, PlanMatcher.match(listOf(open), run.copy(watchedStart = false), TODAY))
        // a fixed step still needs its countdown
        assertNull(PlanMatcher.match(listOf(plan), watched(remaining = null), TODAY))
    }

    @Test fun `the total remaining is needed only when the total is checked`() {
        // an open step ahead: the late rules skip the total
        val run = RunObservation(
            stepCount = 3, stepIndex = 0, stepRemainingMs = 400_000, totalRemainingMs = null,
            elapsedMs = 0, watchedStart = false, targetKind = TargetKind.POWER, targetMin = 151.0, targetMax = 159.0,
            targetImplied = false, scalePercent = 100,
        )
        assertEquals(openPlan(), PlanMatcher.match(listOf(openPlan()), run, TODAY))
        // all steps fixed: the total is checked, so it must be known
        assertNull(PlanMatcher.match(listOf(plan), late(total = null), TODAY))
    }

    @Test fun `a different target kind disagrees`() {
        val t = plan.expand()[0].target!!
        assertEquals(false, PlanMatcher.rangeAgrees(t, TargetKind.HEART_RATE, 151.0, 159.0, false, 100))
        assertEquals(true, PlanMatcher.rangeAgrees(t, TargetKind.POWER, 151.0, 159.0, false, 100))
        // a kind not known on either side gives no kind verdict: the ranges decide
        assertEquals(true, PlanMatcher.rangeAgrees(t, TargetKind.UNKNOWN, 151.0, 159.0, false, 100))
        assertEquals(true, PlanMatcher.rangeAgrees(t, null, 151.0, 159.0, false, 100))
        assertEquals(true, PlanMatcher.rangeAgrees(t.copy(kind = TargetKind.UNKNOWN), TargetKind.HEART_RATE, 151.0, 159.0, false, 100))
        assertNull(PlanMatcher.match(listOf(plan), watched().copy(targetKind = TargetKind.HEART_RATE), TODAY))
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
