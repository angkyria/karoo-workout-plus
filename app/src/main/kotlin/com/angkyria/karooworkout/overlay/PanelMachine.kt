package com.angkyria.karooworkout.overlay

import com.angkyria.karooworkout.settings.PageMode

/**
 * Overlay size state. The full-screen workout layout can be shown two ways:
 *  - *takeover*: [PageMode.EVERY_PAGE] (every ride page), or the ride app's workout
 *    page in [PageMode.REPLACE_WORKOUT_PAGE] — the pages underneath are covered;
 *  - by hand: the rider grew the chip -> drawer -> full page.
 *
 * The workout page itself can't be minimized. Elsewhere, minimizing reveals the page
 * underneath; changing pages brings the takeover back (on every page, or on the
 * workout page only), and so does tapping the chip.
 */
class PanelMachine {

    enum class Size { CHIP, DRAWER, FULL }

    var size: Size = Size.CHIP
        private set

    /** FULL because the page / mode demands it rather than by a gesture. */
    var takeover: Boolean = false
        private set

    private var onWorkoutPage = false
    private var pageKey: Any? = null

    /**
     * A workout became active: start in the size [mode] asks for. [justStarted]: the
     * workout is at its first interval — Karoo's ride app jumps to its workout page
     * then, and that jump sends no page event, so assume the workout page is up.
     */
    fun onWorkoutStarted(mode: PageMode, autoOpenDrawer: Boolean, justStarted: Boolean = false) {
        when (mode) {
            PageMode.EVERY_PAGE -> takeOver()
            PageMode.REPLACE_WORKOUT_PAGE -> {
                if (justStarted) onWorkoutPage = true
                if (onWorkoutPage) takeOver() else chip()
            }
            PageMode.DRAWER_ONLY -> {
                takeover = false
                size = if (autoOpenDrawer) Size.DRAWER else Size.CHIP
            }
        }
    }

    /**
     * The ride app reported its visible page: [pageKey] identifies the page,
     * [workoutPage] = it is the workout page. Karoo re-reports the same page now and
     * then; only a real change of page counts.
     */
    fun onPageChanged(pageKey: Any?, workoutPage: Boolean, mode: PageMode) {
        val pageChanged = pageKey != this.pageKey
        val workoutChanged = workoutPage != onWorkoutPage
        this.pageKey = pageKey
        onWorkoutPage = workoutPage
        when (mode) {
            // every page shows the layout: a new page undoes a back-button collapse
            PageMode.EVERY_PAGE -> if (pageChanged) takeOver()
            PageMode.REPLACE_WORKOUT_PAGE -> if (workoutChanged) {
                if (workoutPage) takeOver() else if (takeover) chip()
            }
            PageMode.DRAWER_ONLY -> Unit
        }
    }

    /**
     * Open up from a tap or swipe: the chip restores the full layout (the drawer
     * step only exists in [PageMode.DRAWER_ONLY]); the drawer grows to full.
     */
    fun expand(mode: PageMode) {
        when {
            size == Size.CHIP && mode == PageMode.DRAWER_ONLY -> size = Size.DRAWER
            size == Size.CHIP -> takeOver()
            else -> size = Size.FULL
        }
    }

    /**
     * The workout page is always covered: no minimizing there (only in the modes that
     * take pages over — in [PageMode.DRAWER_ONLY] the rider sizes everything by hand).
     */
    fun pinned(mode: PageMode): Boolean = mode != PageMode.DRAWER_ONLY && onWorkoutPage

    /** Minimize (swipe down, the page's handle): straight to the chip, unless [pinned]. */
    fun collapse(mode: PageMode) {
        if (pinned(mode)) {
            takeOver()
            return
        }
        chip()
    }

    fun onWorkoutEnded() = chip()

    private fun takeOver() {
        takeover = true
        size = Size.FULL
    }

    private fun chip() {
        takeover = false
        size = Size.CHIP
    }
}
