package com.angkyria.karooworkout

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
}
