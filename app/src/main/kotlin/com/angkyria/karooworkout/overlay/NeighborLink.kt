package com.angkyria.karooworkout.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Broadcasts our overlay state to Climber+ and listens for its own (see [NeighborRules]).
 * Sent to the other package only; resent every [NeighborRules.RESEND_MS] so a
 * vanished sender goes stale. Main thread only.
 */
class NeighborLink(private val context: Context, private val onChanged: () -> Unit) {

    var other = NeighborState()
        private set

    private var sent: Triple<NeighborPanel, Boolean, OverlayPriority>? = null
    private var sentAtMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.getStringExtra(EXTRA_FROM) != OTHER_PACKAGE) return
            when (intent.action) {
                ACTION_QUERY -> sent?.let { send(it) }
                ACTION_STATE -> {
                    val panel = enumOrNull<NeighborPanel>(intent.getStringExtra(EXTRA_PANEL)) ?: return
                    val next = NeighborState(
                        panel = panel,
                        chipBottom = intent.getBooleanExtra(EXTRA_CHIP_BOTTOM, true),
                        priority = enumOrNull<OverlayPriority>(intent.getStringExtra(EXTRA_PRIORITY)),
                        atMs = System.currentTimeMillis(),
                    )
                    val changed = next.copy(atMs = 0) != other.copy(atMs = 0)
                    other = next
                    if (changed) onChanged()
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_STATE)
            addAction(ACTION_QUERY)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // the other app may already be showing something: ask
        context.sendBroadcast(intent(ACTION_QUERY))
    }

    fun stop() {
        publish(NeighborPanel.NONE, chipBottom = true, priority = sent?.third ?: OverlayPriority.WORKOUT)
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** What the other app shows now (nothing, once it has gone quiet). */
    fun otherNow(nowMs: Long = System.currentTimeMillis()): NeighborState = other.fresh(nowMs)

    /**
     * Tell the other app what we show: on change, and every RESEND_MS while showing
     * something (nothing needs no repeat — it is what a stale state means).
     */
    fun publish(panel: NeighborPanel, chipBottom: Boolean, priority: OverlayPriority) {
        val state = Triple(panel, chipBottom, priority)
        val now = System.currentTimeMillis()
        if (state == sent && (panel == NeighborPanel.NONE || now - sentAtMs < NeighborRules.RESEND_MS)) return
        sentAtMs = now
        send(state)
    }

    private fun send(state: Triple<NeighborPanel, Boolean, OverlayPriority>) {
        sent = state
        context.sendBroadcast(
            intent(ACTION_STATE)
                .putExtra(EXTRA_PANEL, state.first.name)
                .putExtra(EXTRA_CHIP_BOTTOM, state.second)
                .putExtra(EXTRA_PRIORITY, state.third.name),
        )
    }

    private fun intent(action: String) =
        Intent(action).setPackage(OTHER_PACKAGE).putExtra(EXTRA_FROM, context.packageName)

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() }

    companion object {
        const val OTHER_PACKAGE = "com.hazzus.karooclimber"
        const val ACTION_STATE = "com.angkyria.karoo.overlay.STATE"
        const val ACTION_QUERY = "com.angkyria.karoo.overlay.QUERY"
        const val EXTRA_FROM = "from"
        const val EXTRA_PANEL = "panel"
        const val EXTRA_CHIP_BOTTOM = "chip_bottom"
        const val EXTRA_PRIORITY = "priority"
    }
}
