package com.angkyria.karooworkout

import com.angkyria.karooworkout.overlay.BottomEdge
import com.angkyria.karooworkout.overlay.NeighborPanel.CHIP
import com.angkyria.karooworkout.overlay.NeighborPanel.NONE
import com.angkyria.karooworkout.overlay.NeighborPanel.PANEL
import com.angkyria.karooworkout.overlay.NeighborRules
import com.angkyria.karooworkout.overlay.NeighborState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborRulesTest {

    @Test
    fun `chips both show`() {
        assertFalse(NeighborRules.yields(CHIP, CHIP, ownWins = true))
        assertFalse(NeighborRules.yields(CHIP, CHIP, ownWins = false))
    }

    @Test
    fun `a chip gives way to the other drawer whatever the priority`() {
        assertTrue(NeighborRules.yields(CHIP, PANEL, ownWins = true))
        assertTrue(NeighborRules.yields(CHIP, PANEL, ownWins = false))
    }

    @Test
    fun `two panels - the priority keeps one`() {
        assertFalse(NeighborRules.yields(PANEL, PANEL, ownWins = true))
        assertTrue(NeighborRules.yields(PANEL, PANEL, ownWins = false))
    }

    @Test
    fun `a panel covers the other chip`() {
        assertFalse(NeighborRules.yields(PANEL, CHIP, ownWins = false))
        assertFalse(NeighborRules.yields(PANEL, NONE, ownWins = false))
    }

    @Test
    fun `the winner never hides once the loser reports nothing`() {
        // loser hid and says NONE: the winner keeps showing, no back and forth
        assertFalse(NeighborRules.yields(PANEL, NONE, ownWins = true))
    }

    @Test
    fun `side by side only with both chips at the bottom`() {
        val bottomChip = NeighborState(CHIP, chipBottom = true, atMs = 1)
        assertTrue(NeighborRules.sideBySide(true, bottomChip))
        assertFalse(NeighborRules.sideBySide(false, bottomChip))
        assertFalse(NeighborRules.sideBySide(true, bottomChip.copy(chipBottom = false)))
        assertFalse(NeighborRules.sideBySide(true, bottomChip.copy(panel = PANEL)))
        assertFalse(NeighborRules.sideBySide(true, NeighborState()))
    }

    @Test
    fun `a quiet neighbor goes stale`() {
        val s = NeighborState(PANEL, atMs = 1_000)
        assertEquals(PANEL, s.fresh(1_000 + NeighborRules.STALE_MS).panel)
        assertEquals(NONE, s.fresh(1_001 + NeighborRules.STALE_MS).panel)
    }

    // ------------------------------------------------------- bottom edge

    @Test
    fun `the chip is a pill as tall as the ride app's navigation pill`() {
        // "Rerouting": 63 px on a Karoo 2
        assertEquals(64, BottomEdge.chipHeightPx(1.875f))
    }

    @Test
    fun `the chip sits in the navigation pill's strip`() {
        assertEquals(24, BottomEdge.chipOffsetPx(800))
        // "Rerouting" runs y 712-775: same row
        assertEquals(800 - 24 - 64, 712)
    }

    @Test
    fun `while navigating the chips fit beside the pill`() {
        // "Rerouting" runs x 103-376 on a 480 px screen
        val w = BottomEdge.navSlotWidthPx(480, 1.875f)
        val edge = BottomEdge.slotEdgePx(1.875f)
        assertEquals(88, w)
        assertEquals(8, edge)
        // left slot ends before the pill, right slot starts after it
        assertTrue(edge + w <= 103)
        assertTrue(480 - edge - w >= 376)
    }

    @Test
    fun `without navigation panels reach the bottom`() {
        assertEquals(0, BottomEdge.panelOffsetPx(false, 1.875f))
    }

    @Test
    fun `while navigating panels stop above the pill`() {
        assertEquals(94, BottomEdge.panelOffsetPx(true, 1.875f))
    }

    @Test
    fun `while navigating a panel shrinks by the gap and keeps its top`() {
        val h = BottomEdge.panelHeightPx(736, true, 1.875f)
        assertEquals(642, h)
        assertEquals(64, 800 - BottomEdge.panelOffsetPx(true, 1.875f) - h)
        assertEquals(736, BottomEdge.panelHeightPx(736, false, 1.875f))
    }
}
