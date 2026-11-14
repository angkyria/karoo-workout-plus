package com.angkyria.karooworkout.overlay

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
