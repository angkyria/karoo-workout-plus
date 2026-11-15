package com.angkyria.karooworkout.overlay

import kotlin.math.roundToInt

/**
 * Sharing the screen with the sibling overlay app, Climber+ (com.hazzus.karooclimber,
 * the angkyria fork). Both draw a bottom chip in the same spot, so each app
 * broadcasts what it shows ([NeighborLink]) and follows these rules — Climber+
 * carries the same file, keep the two in step.
 *
 *  - two bottom chips sit side by side: Workout+ on the right, Climber+ on the left;
 *  - a chip gives way to the other app's drawer / full page;
 *  - both on a drawer / full page: the rider's [OverlayPriority] (a Workout+
 *    setting, broadcast to Climber+) keeps one; the other hides until it shrinks.
 *
 * [BottomEdge] places both apps' bottom windows the same way: a pill-sized chip in
 * the ride app's navigation-pill strip, beside that pill while a route is navigated.
 */
enum class NeighborPanel { NONE, CHIP, PANEL }

/** Which app keeps its drawer / full page when both want one. Persisted: never rename. */
enum class OverlayPriority(val label: String) {
    WORKOUT("Workout+"),
    CLIMBER("Climber+"),
}

/** What the other app last said it shows. */
data class NeighborState(
    val panel: NeighborPanel = NeighborPanel.NONE,
    val chipBottom: Boolean = true,
    /** Only Workout+ sends one. */
    val priority: OverlayPriority? = null,
    val atMs: Long = 0L,
) {
    /** A sender that went quiet (killed, crashed) stops counting. */
    fun fresh(nowMs: Long): NeighborState =
        if (nowMs - atMs <= NeighborRules.STALE_MS) this else NeighborState()
}

object NeighborRules {
    /** Senders repeat their state this often; twice that unheard = gone. */
    const val RESEND_MS = 10_000L
    const val STALE_MS = 2 * RESEND_MS + 5_000L

    /** Hide our window: the other app's drawer / full page wins over [own]. */
    fun yields(own: NeighborPanel, other: NeighborPanel, ownWins: Boolean): Boolean =
        other == NeighborPanel.PANEL && (own == NeighborPanel.CHIP || (own == NeighborPanel.PANEL && !ownWins))

    /** Our chip moves to its half of the bottom edge: the other app's chip is down there too. */
    fun sideBySide(ownChipBottom: Boolean, other: NeighborState): Boolean =
        ownChipBottom && other.panel == NeighborPanel.CHIP && other.chipBottom
}

/**
 * Where bottom-anchored windows sit. The chip is a pill like the ride app's own
 * navigation pill ("Rerouting": 63 px tall, x 103–376 of 480 on a Karoo 2) and sits
 * in its strip. While a route is navigated that pill takes the middle, so the chips
 * join it in the free space on either side (Climber+ left, Workout+ right), and the
 * drawer / full page stop above it.
 */
object BottomEdge {
    /** ≈ 64 px on a Karoo 2 (density 1.875), the navigation pill's height. */
    const val CHIP_DP = 34

    /** Clear of the page dots: where the navigation pill sits. */
    const val EDGE_OFFSET_FRACTION = 0.03

    /** The navigation pill and its margin: panels stop at ≈ y 706. */
    const val NAV_GAP_DP = 50

    /** The navigation pill's width ("Rerouting", 273 px on a Karoo 2). */
    const val NAV_PILL_DP = 146

    /** Between a side chip and the screen edge / the navigation pill. */
    const val SLOT_MARGIN_DP = 4

    fun chipHeightPx(density: Float): Int = (CHIP_DP * density).roundToInt()

    fun chipOffsetPx(screenH: Int): Int = (screenH * EDGE_OFFSET_FRACTION).toInt()

    /** Width of a chip beside the navigation pill. */
    fun navSlotWidthPx(screenW: Int, density: Float): Int =
        ((screenW - NAV_PILL_DP * density) / 2 - 2 * SLOT_MARGIN_DP * density).toInt()

    /** A side chip's distance from its screen edge. */
    fun slotEdgePx(density: Float): Int = (SLOT_MARGIN_DP * density).roundToInt()

    /** Bottom offset of a bottom-anchored drawer / full page. */
    fun panelOffsetPx(navigating: Boolean, density: Float): Int = if (navigating) navGapPx(density) else 0

    /** A bottom-anchored panel of [heightPx] lifted by the gap keeps its top: it loses the gap. */
    fun panelHeightPx(heightPx: Int, navigating: Boolean, density: Float): Int =
        heightPx - panelOffsetPx(navigating, density)

    private fun navGapPx(density: Float): Int = (NAV_GAP_DP * density).roundToInt()
}
