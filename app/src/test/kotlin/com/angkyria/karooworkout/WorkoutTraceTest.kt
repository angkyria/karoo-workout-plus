package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.WorkoutTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTraceTest {

    @Test
    fun bucketsAverageFiveSeconds() {
        val t = WorkoutTrace()
        repeat(5) { t.add(0, 1000, 100.0) }
        repeat(5) { t.add(0, 1000, 200.0) }
        assertEquals(listOf(100f, 200f), t.line(0))
    }

    @Test
    fun aPartialBucketShowsItsMeanSoFar() {
        val t = WorkoutTrace()
        t.add(0, 2000, 150.0)
        assertEquals(listOf(150f), t.line(0))
    }

    @Test
    fun noReadingIsAGap() {
        val t = WorkoutTrace()
        repeat(5) { t.add(0, 1000, null) }
        assertTrue(t.line(0).single().isNaN())
    }

    @Test
    fun intervalsAreSeparate() {
        val t = WorkoutTrace()
        t.add(0, 5000, 100.0)
        t.add(1, 5000, 300.0)
        assertEquals(listOf(100f), t.line(0))
        assertEquals(listOf(300f), t.line(1))
    }

    @Test
    fun dropFromForgetsLaterIntervals() {
        val t = WorkoutTrace()
        t.add(0, 5000, 100.0)
        t.add(1, 5000, 300.0)
        t.dropFrom(1)
        assertEquals(listOf(100f), t.line(0))
        assertEquals(emptyList<Float>(), t.line(1))
    }

    @Test
    fun aLongIntervalStaysBounded() {
        val t = WorkoutTrace()
        repeat(4 * 3600) { t.add(0, 1000, 200.0) }
        assertTrue(t.line(0).size <= WorkoutTrace.MAX_BUCKETS)
        assertEquals(200f, t.line(0).first())
    }

    @Test
    fun npOfASteadyRideIsItsPower() {
        val t = WorkoutTrace()
        repeat(600) { t.add(0, 1000, 200.0) }
        assertEquals(200.0, t.normalizedPower()!!, 0.5)
    }

    @Test
    fun npWeighsHardBlocksAboveTheAverage() {
        val t = WorkoutTrace()
        repeat(10) {
            repeat(60) { t.add(0, 1000, 100.0) }
            repeat(60) { t.add(0, 1000, 300.0) }
        }
        // the average is 200
        assertTrue(t.normalizedPower()!! in 235.0..255.0)
    }

    @Test
    fun npNeedsThirtySeconds() {
        val t = WorkoutTrace()
        repeat(29) { t.add(0, 1000, 200.0) }
        assertNull(t.normalizedPower())
    }

    @Test
    fun noTimeNoSamples() {
        val t = WorkoutTrace()
        // a paused frame credits 0 ms
        t.add(0, 0, 500.0)
        assertEquals(emptyList<Float>(), t.line(0))
        assertNull(t.normalizedPower())
    }

    @Test
    fun bucketCentersSitInTheTimeTheyCover() {
        // two full 5 s buckets and a 2 s one
        assertEquals(listOf(2_500L, 7_500L, 11_000L), WorkoutTrace.bucketCenters(12_000, 3))
        // a long interval merged to 10 s buckets
        val merged = WorkoutTrace.bucketCenters(7_200_000, 720)
        assertEquals(5_000L, merged.first())
        assertEquals(7_195_000L, merged.last())
    }

    @Test
    fun bucketCentersMatchTheLineForALongInterval() {
        val t = WorkoutTrace()
        repeat(4 * 3600) { t.add(0, 1000, 200.0) }
        assertEquals(t.line(0).size, WorkoutTrace.bucketCenters(4 * 3600 * 1000L, t.line(0).size).size)
    }
}
