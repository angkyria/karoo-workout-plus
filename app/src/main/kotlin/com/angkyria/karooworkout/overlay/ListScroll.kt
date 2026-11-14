package com.angkyria.karooworkout.overlay

import com.angkyria.karooworkout.data.WorkoutCues
import kotlin.math.abs
import kotlin.math.exp

/**
 * Scroll state of the workout page's interval list. Pure: the view feeds it drags,
 * flings and the clock. [offsetPx] is how far the page is scrolled down (0 = the first
 * screen, the countdown). It goes back to the top by itself — 10 s after the last
 * touch, at every interval change and when the 10 s warning beep fires — so the
 * countdown is in view when it matters.
 */
class ListScroll {
    var offsetPx: Float = 0f

    /** A fling still coasting: the view keeps drawing frames. */
    val moving: Boolean get() = velocity != 0f

    private var lastTouchMs = Long.MIN_VALUE / 2
    private var velocity = 0f // offset px per second
    private var maxPx = 0f
    private var lastFrameMs = 0L
    private var lastStepIndex: Int? = null
    private var lastRemainingMs: Long? = null

    /** The finger moved [dy] px (down positive): the content follows it. */
    fun drag(dy: Float, maxPx: Float) {
        velocity = 0f
        offsetPx = (offsetPx - dy).coerceIn(0f, maxPx.coerceAtLeast(0f))
    }

    /** The finger left at [velocityY] px/s (down positive): coast from there. */
    fun fling(velocityY: Float, maxPx: Float, nowMs: Long) {
        touched(nowMs)
        this.maxPx = maxPx.coerceAtLeast(0f)
        velocity = (-velocityY).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        lastFrameMs = nowMs
    }

    /** A touch: restarts the 10 s before going home and stops a coast. */
    fun touched(nowMs: Long) {
        lastTouchMs = nowMs
        velocity = 0f
    }

    /** Back to the top now. */
    fun home() {
        velocity = 0f
        offsetPx = 0f
    }

    /**
     * Advance to [nowMs] with the workout as it is: coast a fling, or go home.
     * Returns true when [offsetPx] changed.
     */
    fun step(nowMs: Long, stepIndex: Int, stepRemainingMs: Long?): Boolean {
        val before = offsetPx
        val prevIndex = lastStepIndex
        val prevRemaining = lastRemainingMs
        lastStepIndex = stepIndex
        lastRemainingMs = stepRemainingMs

        val newInterval = prevIndex != null && prevIndex != stepIndex
        // the warning beep: the countdown crossed 10 s between two samples (a jump crosses nothing)
        val warning = !newInterval && prevRemaining != null && stepRemainingMs != null &&
            prevRemaining > WorkoutCues.WARNING_MS && stepRemainingMs <= WorkoutCues.WARNING_MS &&
            prevRemaining - stepRemainingMs <= MAX_CROSSING_MS
        val idle = nowMs - lastTouchMs >= RETURN_AFTER_MS
        if (newInterval || warning || (idle && !moving)) {
            home()
        } else if (moving) {
            coast(nowMs)
        }
        return offsetPx != before
    }

    private fun coast(nowMs: Long) {
        val dt = (nowMs - lastFrameMs).coerceIn(0L, 100L) / 1000f
        lastFrameMs = nowMs
        if (dt <= 0f) return
        offsetPx += velocity * dt
        velocity *= exp(-dt / FRICTION_S)
        if (offsetPx <= 0f || offsetPx >= maxPx) {
            offsetPx = offsetPx.coerceIn(0f, maxPx)
            velocity = 0f
        }
        if (abs(velocity) < STOP_VELOCITY) velocity = 0f
    }

    private companion object {
        const val RETURN_AFTER_MS = 10_000L

        /** Countdown samples come ~1 s apart: a bigger jump is a skip or rewind. */
        const val MAX_CROSSING_MS = 3_000L

        const val MAX_VELOCITY = 6_000f
        const val STOP_VELOCITY = 40f

        /** Time constant of the coast's slow-down: a fling glides ~ velocity x this far. */
        const val FRICTION_S = 0.35f
    }
}
