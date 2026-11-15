package com.angkyria.karooworkout.data

import kotlin.math.min
import kotlin.math.pow

/**
 * The workout as ridden, for the finished page: the primary output per interval,
 * averaged into [BUCKET_MS] buckets (a long interval merges its buckets so it never
 * holds more than [MAX_BUCKETS]), and normalized power over everything ridden.
 * Fed with workout time only — the engine credits nothing while the ride is paused.
 */
class WorkoutTrace {

    private class Bucket {
        var ms = 0L
        var valueMs = 0L
        var sum = 0.0

        fun mean(): Float = if (valueMs > 0) (sum / valueMs).toFloat() else Float.NaN
    }

    private class Line {
        var bucketMs = BUCKET_MS
        var buckets = ArrayList<Bucket>()
    }

    private val lines = sortedMapOf<Int, Line>()

    // NP: 1 s samples, a 30 s rolling mean, the mean of its 4th power
    private var secondMs = 0L
    private var secondSum = 0.0
    private val window = ArrayDeque<Double>()
    private var windowSum = 0.0
    private var fourthSum = 0.0
    private var fourthCount = 0

    /**
     * [ms] of workout time in interval [index] at [output] (null: no reading).
     * [npOutput]: instant watts for NP (null: not a power step, or no reading).
     */
    fun add(index: Int, ms: Long, output: Double?, npOutput: Double? = output) {
        if (ms <= 0) return
        val line = lines.getOrPut(index) { Line() }
        var left = ms
        while (left > 0) {
            var bucket = line.buckets.lastOrNull()
            if (bucket == null || bucket.ms >= line.bucketMs) {
                if (line.buckets.size >= MAX_BUCKETS) merge(line)
                bucket = line.buckets.lastOrNull()?.takeIf { it.ms < line.bucketMs } ?: Bucket().also { line.buckets += it }
            }
            val take = min(left, line.bucketMs - bucket.ms)
            bucket.ms += take
            if (output != null) {
                bucket.valueMs += take
                bucket.sum += output * take
            }
            left -= take
        }
        if (npOutput != null) sample(ms, npOutput)
    }

    /** Bucket means of interval [index], oldest first; NaN where nothing was read. */
    fun line(index: Int): List<Float> = lines[index]?.buckets?.map { it.mean() } ?: emptyList()

    /** Forget intervals [index] and later (a rewind or a restarted interval). NP keeps them. */
    fun dropFrom(index: Int) {
        lines.keys.filter { it >= index }.forEach { lines.remove(it) }
    }

    /** Normalized power over every power step ridden; null until 30 s of readings. */
    fun normalizedPower(): Double? = if (fourthCount == 0) null else (fourthSum / fourthCount).pow(0.25)

    fun reset() {
        lines.clear()
        secondMs = 0
        secondSum = 0.0
        window.clear()
        windowSum = 0.0
        fourthSum = 0.0
        fourthCount = 0
    }

    /** Halve the bucket count: neighbours merge, the bucket length doubles. */
    private fun merge(line: Line) {
        val merged = ArrayList<Bucket>(line.buckets.size / 2 + 1)
        line.buckets.chunked(2).forEach { pair ->
            merged += Bucket().apply {
                pair.forEach {
                    ms += it.ms
                    valueMs += it.valueMs
                    sum += it.sum
                }
            }
        }
        line.buckets = merged
        line.bucketMs *= 2
    }

    private fun sample(ms: Long, output: Double) {
        var left = ms
        while (left > 0) {
            val take = min(left, 1000 - secondMs)
            secondMs += take
            secondSum += output * take
            left -= take
            if (secondMs == 1000L) {
                push(secondSum / 1000)
                secondMs = 0
                secondSum = 0.0
            }
        }
    }

    private fun push(second: Double) {
        window.addLast(second)
        windowSum += second
        if (window.size > NP_WINDOW_S) windowSum -= window.removeFirst()
        if (window.size == NP_WINDOW_S) {
            fourthSum += (windowSum / NP_WINDOW_S).pow(4)
            fourthCount++
        }
    }

    companion object {
        const val BUCKET_MS = 5_000L
        const val MAX_BUCKETS = 720

        /**
         * Where each of the [n] buckets of an interval [actualMs] long sits: the middle
         * of the time it covers. Every bucket is full but the last; merging doubled
         * the bucket length until [n] covered the interval.
         */
        fun bucketCenters(actualMs: Long, n: Int): List<Long> {
            if (n <= 0) return emptyList()
            var bucketMs = BUCKET_MS
            while ((actualMs + bucketMs - 1) / bucketMs > n) bucketMs *= 2
            return List(n) { i -> (i * bucketMs + min((i + 1) * bucketMs, actualMs).coerceAtLeast(i * bucketMs)) / 2 }
        }
        private const val NP_WINDOW_S = 30
    }
}
