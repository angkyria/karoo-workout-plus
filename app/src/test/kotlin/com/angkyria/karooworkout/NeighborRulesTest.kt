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
    fun `the chip is a slim bar`() {
        assertEquals(28, BottomEdge.chipHeightPx(1.875f))
    }

    @Test
    fun `without navigation the chip sits at the bottom`() {
        assertEquals(24, BottomEdge.chipOffsetPx(false, 1.875f, 800))
    }

    @Test
    fun `while navigating the chip clears the pill`() {
        val offset = BottomEdge.chipOffsetPx(true, 1.875f, 800)
        assertEquals(94, offset)
        // the pill's top is at about y 712 of 800
        assertTrue(800 - offset <= 712)
    }

    @Test
    fun `without navigation panels reach the bottom`() {
        assertEquals(0, BottomEdge.panelOffsetPx(false, 1.875f))
    }

    @Test
    fun `while navigating panels stop above the pill`() {
        assertEquals(94, BottomEdge.panelOffsetPx(true, 1.875f))
    }
}
