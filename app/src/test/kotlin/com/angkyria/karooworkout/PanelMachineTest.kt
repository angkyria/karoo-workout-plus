package com.angkyria.karooworkout

import com.angkyria.karooworkout.overlay.PanelMachine
import com.angkyria.karooworkout.overlay.PanelMachine.Size
import com.angkyria.karooworkout.settings.PageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelMachineTest {

    private val replace = PageMode.REPLACE_WORKOUT_PAGE
    private val every = PageMode.EVERY_PAGE

    // pages as the ride app reports them (identity = their data fields)
    private val workoutPage = listOf("TYPE_WORKOUT_ID")
    private val dataPage = listOf("TYPE_SPEED_ID", "TYPE_POWER_ID")
    private val mapPage = listOf("TYPE_ELEVATION_ID")

    private fun PanelMachine.show(page: List<String>, mode: PageMode) =
        onPageChanged(page, workoutPage = page == workoutPage, mode = mode)

    // ------------------------------------------------------------ every page

    @Test
    fun everyPageModeCoversEveryPage() {
        val m = PanelMachine()
        m.show(dataPage, every)
        m.onWorkoutStarted(every, autoOpenDrawer = false)
        assertEquals(Size.FULL, m.size)
        m.show(workoutPage, every)
        assertEquals(Size.FULL, m.size)
        m.show(mapPage, every)
        assertEquals(Size.FULL, m.size)
        assertTrue(m.takeover)
    }

    @Test
    fun backShowsThePageUntilThePageChanges() {
        val m = PanelMachine()
        m.show(dataPage, every)
        m.onWorkoutStarted(every, autoOpenDrawer = false)
        m.collapse(every)
        assertEquals(Size.CHIP, m.size)
        m.show(dataPage, every) // Karoo re-reports the same page now and then
        assertEquals(Size.CHIP, m.size)
        m.show(mapPage, every)
        assertEquals(Size.FULL, m.size)
    }

    // ---------------------------------------------------- workout page only

    @Test
    fun workoutPageIsTakenOverAndReleased() {
        val m = PanelMachine()
        m.onWorkoutStarted(replace, autoOpenDrawer = true)
        assertEquals(Size.CHIP, m.size)

        m.show(workoutPage, replace)
        assertEquals(Size.FULL, m.size)
        assertTrue(m.takeover)

        m.show(dataPage, replace)
        assertEquals(Size.CHIP, m.size)
        assertFalse(m.takeover)
    }

    @Test
    fun workoutStartingWhileOnTheWorkoutPageTakesItOver() {
        val m = PanelMachine()
        m.show(workoutPage, replace)
        m.onWorkoutStarted(replace, autoOpenDrawer = false)
        assertEquals(Size.FULL, m.size)
        assertTrue(m.takeover)
    }

    @Test
    fun freshWorkoutTakesOverWithoutAPageEvent() {
        // Karoo 2 jumps to its workout page when the workout starts, silently
        val m = PanelMachine()
        m.show(dataPage, replace)
        m.onWorkoutStarted(replace, autoOpenDrawer = false, justStarted = true)
        assertEquals(Size.FULL, m.size)
        assertTrue(m.takeover)
        // and swiping away from it is still heard
        m.show(mapPage, replace)
        assertEquals(Size.CHIP, m.size)
    }

    @Test
    fun workoutPickedUpMidWayFollowsTheReportedPage() {
        val m = PanelMachine()
        m.show(dataPage, replace)
        m.onWorkoutStarted(replace, autoOpenDrawer = false, justStarted = false)
        assertEquals(Size.CHIP, m.size)
    }

    @Test
    fun theWorkoutPageCannotBeMinimized() {
        val m = PanelMachine()
        m.onWorkoutStarted(replace, autoOpenDrawer = true)
        m.show(workoutPage, replace)
        assertTrue(m.pinned(replace))
        m.collapse(replace)
        assertEquals(Size.FULL, m.size)
        // other pages still minimize
        m.show(dataPage, replace)
        assertEquals(Size.CHIP, m.size)
        m.expand(replace)
        m.collapse(replace)
        assertEquals(Size.CHIP, m.size)
    }

    @Test
    fun minimizeIsOneStepAndTheChipRestoresTheFullLayout() {
        val m = PanelMachine()
        m.show(dataPage, every)
        m.onWorkoutStarted(every, autoOpenDrawer = false)
        m.collapse(every)
        assertEquals(Size.CHIP, m.size)
        m.expand(every) // tap the chip
        assertEquals(Size.FULL, m.size)
        assertTrue(m.takeover) // and it keeps covering through page changes
        m.show(mapPage, every)
        assertEquals(Size.FULL, m.size)
    }

    // ------------------------------------------------------- drawer only

    @Test
    fun drawerOnlyModeGoesThroughTheDrawer() {
        val m = PanelMachine()
        m.onWorkoutStarted(PageMode.DRAWER_ONLY, autoOpenDrawer = true)
        assertEquals(Size.DRAWER, m.size)
        m.show(workoutPage, PageMode.DRAWER_ONLY)
        assertEquals(Size.DRAWER, m.size)
        m.expand(PageMode.DRAWER_ONLY)
        assertEquals(Size.FULL, m.size)
        m.collapse(PageMode.DRAWER_ONLY)
        assertEquals(Size.CHIP, m.size)
        m.expand(PageMode.DRAWER_ONLY)
        assertEquals(Size.DRAWER, m.size)
    }

    @Test
    fun workoutEndReturnsToTheChip() {
        val m = PanelMachine()
        m.onWorkoutStarted(every, autoOpenDrawer = false)
        m.onWorkoutEnded()
        assertEquals(Size.CHIP, m.size)
        assertFalse(m.takeover)
    }
}
