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
 * [BottomEdge] places both apps' bottom windows the same way: a slim chip, lifted
 * with the panels above the ride app's navigation pill while a route is navigated.
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
 * Where bottom-anchored windows sit. The chip is a slim bar so it covers only the
 * edge of the last field row. While navigating, the ride app shrinks its pages for a
 * pill at the bottom (≈ y 712–775 of 800 on a Karoo 2): chip and panels stay above it.
 */
object BottomEdge {
    /** ≈ 28 px on a Karoo 2 (density 1.875). */
    const val CHIP_DP = 15

    /** Clear of the page dots, as the chip always sat. */
    const val EDGE_OFFSET_FRACTION = 0.03

    /** The navigation pill and its margin: the bar's bottom at ≈ y 706. */
    const val NAV_GAP_DP = 50

    fun chipHeightPx(density: Float): Int = (CHIP_DP * density).roundToInt()

    fun chipOffsetPx(navigating: Boolean, density: Float, screenH: Int): Int =
        if (navigating) navGapPx(density) else (screenH * EDGE_OFFSET_FRACTION).toInt()

    /** Bottom offset of a bottom-anchored drawer / full page. */
    fun panelOffsetPx(navigating: Boolean, density: Float): Int = if (navigating) navGapPx(density) else 0

    private fun navGapPx(density: Float): Int = (NAV_GAP_DP * density).roundToInt()
}
