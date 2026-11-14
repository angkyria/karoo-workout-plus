package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryTarget
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.overlay.Format
import com.angkyria.karooworkout.overlay.ListScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListScrollTest {

    @Test
    fun `drag clamps to the content`() {
        val scroll = ListScroll()
        scroll.drag(-5000f, 900f)
        assertEquals(900f, scroll.offsetPx)
        scroll.drag(5000f, 900f)
        assertEquals(0f, scroll.offsetPx)
        // finger up 120 px: the content follows it
        scroll.drag(-120f, 900f)
        assertEquals(120f, scroll.offsetPx)
    }

    @Test
    fun `returns home 10 s after the last touch`() {
        val scroll = ListScroll()
        scroll.touched(0)
        scroll.drag(-400f, 900f)
        assertFalse(scroll.step(9_999, 3, 60_000))
        assertEquals(400f, scroll.offsetPx)
        assertTrue(scroll.step(10_000, 3, 60_000))
        assertEquals(0f, scroll.offsetPx)
        // home already: nothing more to change
        assertFalse(scroll.step(10_500, 3, 59_500))
    }

    @Test
    fun `interval change sends the page home`() {
        val scroll = ListScroll()
        scroll.touched(0)
        scroll.drag(-400f, 900f)
        assertFalse(scroll.step(100, 3, 60_000))
        // paused: the clock of the countdown doesn't move, the touch was a moment ago
        assertTrue(scroll.step(200, 4, null))
        assertEquals(0f, scroll.offsetPx)
    }

    @Test
    fun `warning beep sends the page home`() {
        val scroll = ListScroll()
        scroll.touched(0)
        scroll.drag(-400f, 900f)
        assertFalse(scroll.step(100, 3, 10_400))
        assertTrue(scroll.step(600, 3, 9_900))
        assertEquals(0f, scroll.offsetPx)
    }

    @Test
    fun `a countdown jump past the warning crosses nothing`() {
        val scroll = ListScroll()
        scroll.touched(0)
        scroll.drag(-400f, 900f)
        assertFalse(scroll.step(100, 3, 20_000))
        assertFalse(scroll.step(200, 3, 5_000))
        assertEquals(400f, scroll.offsetPx)
    }

    @Test
    fun `a fling coasts, slows and stops at the end`() {
        val scroll = ListScroll()
        // finger flung upward: the content moves on down the list
        scroll.fling(-3000f, 900f, 0)
        assertTrue(scroll.step(16, 3, 60_000))
        val early = scroll.offsetPx
        assertTrue(early > 0f)
        assertTrue(scroll.step(32, 3, 60_000))
        assertTrue(scroll.offsetPx - early < early) // slowing down
        var t = 32L
        while (scroll.moving && t < 10_000) {
            t += 16
            scroll.step(t, 3, 60_000)
        }
        assertFalse(scroll.moving)
        assertTrue(scroll.offsetPx in 0f..900f)
        // a long fling runs into the end of the content and stops there
        val long = ListScroll()
        long.fling(-20_000f, 300f, 0)
        t = 0
        while (long.moving && t < 10_000) {
            t += 16
            long.step(t, 3, 60_000)
        }
        assertEquals(300f, long.offsetPx)
    }

    @Test
    fun `a touch stops the coast`() {
        val scroll = ListScroll()
        scroll.fling(-3000f, 900f, 0)
        scroll.step(16, 3, 60_000)
        scroll.touched(20)
        assertFalse(scroll.moving)
        val at = scroll.offsetPx
        assertFalse(scroll.step(40, 3, 60_000))
        assertEquals(at, scroll.offsetPx)
    }

    @Test
    fun `starts in reads as a countdown or after the lap`() {
        assertEquals("in 12:30", Format.startsIn(750_000))
        assertEquals("in 0:01", Format.startsIn(400))
        assertEquals("in 1:02:30", Format.startsIn(3_750_000))
        assertEquals("after lap", Format.startsIn(null))
    }

    @Test
    fun `planned targets read with their unit`() {
        assertEquals("220–232 W", Format.plannedTarget(LibraryTarget(TargetKind.POWER, null, 220.4, 231.6, false)))
        assertEquals("137→187 W", Format.plannedTarget(LibraryTarget(TargetKind.POWER, null, 137.0, 187.0, true)))
        assertEquals("140–150 bpm", Format.plannedTarget(LibraryTarget(TargetKind.HEART_RATE, null, 140.0, 150.0, false)))
        assertEquals("85–95 rpm", Format.plannedTarget(LibraryTarget(TargetKind.CADENCE, null, 85.0, 95.0, false)))
        assertEquals("150 W", Format.plannedTarget(LibraryTarget(TargetKind.POWER, 150.0, null, null, false)))
        assertEquals("—", Format.plannedTarget(null))
        assertEquals("—", Format.plannedTarget(LibraryTarget(TargetKind.POWER, null, null, null, false)))
    }
}
