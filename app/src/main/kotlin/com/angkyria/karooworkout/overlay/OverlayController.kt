package com.angkyria.karooworkout.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import com.angkyria.karooworkout.R
import com.angkyria.karooworkout.WorkoutExtension
import com.angkyria.karooworkout.data.CueFrame
import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.overlay.PanelMachine.Size
import com.angkyria.karooworkout.settings.OverlayAnchor
import com.angkyria.karooworkout.settings.PageMode
import com.angkyria.karooworkout.settings.Settings
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max

/**
 * Owns the floating overlay window (ki2 / Climber+ pattern): SYSTEM_ALERT_WINDOW
 * permission check, foreground-service keepalive, WindowManager add/update/remove,
 * and the [PanelMachine] deciding the window's size — including the full-screen
 * takeover of the ride app's workout page. The window never takes key focus.
 *
 * All methods must be called from the main thread.
 */
class OverlayController(private val service: WorkoutExtension) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val machine = PanelMachine()

    private var view: WorkoutOverlayView? = null
    private var permissionNotified = false
    private var foreground = false
    private var workoutActive = false
    private var lastSettings: Settings? = null
    private var appliedParams: WindowManager.LayoutParams? = null

    /** The last frame [show] drew, redrawn when Climber+ changes what it shows. */
    private var lastFrame: Frame? = null

    /** Our chip shares the bottom edge with Climber+'s: it takes the right half. */
    private var chipBeside = false

    /** A route is navigated: the ride app's pill sits at the bottom, stay above it. */
    private var navigating = false

    private val neighbor = NeighborLink(service) { lastFrame?.let(::draw) }

    private data class Frame(
        val state: WorkoutUiState.Shown,
        val settings: Settings,
        val profile: RiderProfile,
        val sysValues: Map<String, Map<String, Double>>,
        val cues: CueFrame,
    )

    fun start() = neighbor.start()

    fun stop() {
        hide()
        neighbor.stop()
    }

    /**
     * True while the full workout page — the only place system data fields are drawn —
     * is on screen; the extension streams those fields only then.
     */
    val fieldsVisible = MutableStateFlow(false)

    /** The ride app reported its visible page (karoo-ext ActiveRidePage). */
    fun onPageChanged(pageKey: Any?, workoutPage: Boolean, mode: PageMode) {
        machine.onPageChanged(pageKey, workoutPage, mode)
        refresh()
    }

    /** The ride app started or stopped navigating (karoo-ext OnNavigationState). */
    fun setNavigating(navigating: Boolean) {
        if (this.navigating == navigating) return
        this.navigating = navigating
        refresh()
    }

    /** Settings changed the page mode mid-workout: start over in that mode. */
    fun onModeChanged(settings: Settings) {
        if (workoutActive) machine.onWorkoutStarted(settings.pageMode, settings.autoOpenDrawer)
    }

    /**
     * Show (or update) the overlay for [state]. [freshStart]: the extension watched
     * this workout begin (see [PanelMachine.onWorkoutStarted]).
     */
    fun show(
        state: WorkoutUiState.Shown,
        settings: Settings,
        profile: RiderProfile,
        sysValues: Map<String, Map<String, Double>>,
        cues: CueFrame = CueFrame(),
        freshStart: Boolean = false,
    ) {
        if (!workoutActive) {
            workoutActive = true
            machine.onWorkoutStarted(settings.pageMode, settings.autoOpenDrawer, justStarted = freshStart)
        }
        lastSettings = settings
        if (!AndroidSettings.canDrawOverlays(service)) {
            notifyPermissionNeeded()
            return
        }
        if (!foreground) startForeground()
        val frame = Frame(state, settings, profile, sysValues, cues)
        lastFrame = frame
        draw(frame)
    }

    private fun draw(f: Frame) {
        val settings = f.settings
        // pages that aren't taken over may hide the chip entirely
        val wanted = machine.size != Size.CHIP || settings.chipOnOtherPages
        val own = when {
            !wanted -> NeighborPanel.NONE
            machine.size == Size.CHIP -> NeighborPanel.CHIP
            else -> NeighborPanel.PANEL
        }
        val other = neighbor.otherNow()
        val shown = if (NeighborRules.yields(own, other.panel, settings.overlayPriority == OverlayPriority.WORKOUT)) {
            NeighborPanel.NONE
        } else {
            own
        }
        val chipBottom = settings.chipAnchor == OverlayAnchor.BOTTOM
        neighbor.publish(shown, chipBottom, settings.overlayPriority)
        chipBeside = NeighborRules.sideBySide(chipBottom, other)

        val current = view
        if (shown == NeighborPanel.NONE) {
            removeWindow()
        } else if (current == null) {
            val created = WorkoutOverlayView(service, machine)
            view = created
            // no relayout hook yet: the view is not attached to the window manager
            created.update(f.state, settings, f.profile, f.sysValues, f.cues)
            val params = layoutParams(created, settings)
            windowManager.addView(created, params)
            appliedParams = params
            created.onRelayoutNeeded = { refresh() }
            created.onPageSwipe = { next ->
                // the ride app (focused, under the overlay) receives the replayed press
                service.karooSystem.dispatch(
                    if (next) PerformHardwareAction.TopRightPress else PerformHardwareAction.TopLeftPress,
                )
            }
        } else {
            current.update(f.state, settings, f.profile, f.sysValues, f.cues)
            applyLayoutIfChanged(current, settings)
        }
        fieldsVisible.value = view != null && machine.size == Size.FULL
    }

    fun hide() {
        if (workoutActive) machine.onWorkoutEnded()
        workoutActive = false
        lastFrame = null
        removeWindow()
        neighbor.publish(NeighborPanel.NONE, chipBottom = true, lastSettings?.overlayPriority ?: OverlayPriority.WORKOUT)
        fieldsVisible.value = false
        if (foreground) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    private fun removeWindow() {
        view?.let {
            runCatching { windowManager.removeView(it) }
            view = null
        }
        appliedParams = null
    }

    /** Re-apply the window for the machine's current size (gesture, page change). */
    private fun refresh() {
        val v = view
        if (v == null || !v.isAttachedToWindow) return
        // the new size may hide the window or move it beside Climber+'s chip
        val f = lastFrame ?: return
        draw(f)
        view?.onPanelChanged()
    }

    /** Relayouts through WindowManager are costly — skip when nothing changed. */
    private fun applyLayoutIfChanged(v: WorkoutOverlayView, settings: Settings) {
        val params = layoutParams(v, settings)
        val last = appliedParams
        if (last != null &&
            last.width == params.width && last.height == params.height &&
            last.flags == params.flags && last.gravity == params.gravity &&
            last.x == params.x && last.y == params.y &&
            last.alpha == params.alpha
        ) {
            return
        }
        windowManager.updateViewLayout(v, params)
        appliedParams = params
    }

    /**
     * Top strip the full page leaves visible: the ride app's own header (ride time,
     * battery, clock — 32 dp, 60 px on a Karoo 2), or the system status bar
     * elsewhere, whichever is taller.
     */
    private val topInsetPx: Int by lazy {
        val res = service.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id != 0) res.getDimensionPixelSize(id) else 0
        val rideHeader = (RIDE_HEADER_DP * res.displayMetrics.density).toInt()
        max(statusBar, rideHeader).coerceAtMost(res.displayMetrics.heightPixels / 5)
    }

    private fun layoutParams(v: WorkoutOverlayView, settings: Settings): WindowManager.LayoutParams {
        val metrics = service.resources.displayMetrics
        val size = machine.size
        val width: Int
        val height: Int
        when (size) {
            Size.CHIP -> {
                height = BottomEdge.chipHeightPx(metrics.density)
                // size to the content; quantized so a ticking digit doesn't relayout every second
                val minW = (metrics.widthPixels * CHIP_MIN_WIDTH_FRACTION).toInt()
                val maxW = (metrics.widthPixels * CHIP_MAX_WIDTH_FRACTION).toInt()
                val step = (CHIP_WIDTH_STEP_DP * metrics.density).toInt().coerceAtLeast(1)
                val content = v.chipContentWidth(height)
                // beside Climber+'s chip: half the edge, the view shrinks its text to fit
                val cap = if (chipBeside) (metrics.widthPixels * CHIP_BESIDE_WIDTH_FRACTION).toInt() else maxW
                width = (((content + step - 1) / step) * step).coerceIn(minW, cap)
            }
            Size.DRAWER -> {
                width = metrics.widthPixels
                height = metrics.heightPixels * settings.heightPercent / 100
            }
            Size.FULL -> {
                // the workout page: everything below the ride header / status bar
                // (and above the navigation pill)
                width = metrics.widthPixels
                height = metrics.heightPixels - topInsetPx - BottomEdge.panelOffsetPx(navigating, metrics.density)
            }
        }
        // Never take key focus. On a Karoo 2, PerformHardwareAction injects its key
        // into the *focused* window: a focused overlay swallowed every replayed page
        // press. Unfocused, the hardware buttons (pages, lap, back) stay native.
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            val anchor = when (size) {
                Size.CHIP -> settings.chipAnchor
                Size.DRAWER -> settings.drawerAnchor
                Size.FULL -> OverlayAnchor.BOTTOM
            }
            val top = anchor == OverlayAnchor.TOP
            val beside = size == Size.CHIP && chipBeside
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or
                (if (beside) Gravity.RIGHT else Gravity.CENTER_HORIZONTAL)
            if (beside) x = (metrics.widthPixels * CHIP_BESIDE_EDGE_FRACTION).toInt()
            // top-anchored panels start below the status bar, the strip FULL keeps visible
            if (size != Size.FULL && top) y = topInsetPx
            // keep the bottom chip clear of Karoo's own bottom drawers and the navigation pill
            if (size == Size.CHIP && !top) {
                y = BottomEdge.chipOffsetPx(navigating, metrics.density, metrics.heightPixels)
            } else if (!top) {
                y = BottomEdge.panelOffsetPx(navigating, metrics.density)
            }
            // the takeover replaces the page: never let the native page show through
            alpha = if (size == Size.FULL) 1f else settings.opacityPercent / 100f
        }
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS declared; degraded is fine pre-33 grant
    private fun startForeground() {
        val channel = NotificationChannel(CHANNEL_ID, "Workout overlay", NotificationManager.IMPORTANCE_MIN)
        notificationManager.createNotificationChannel(channel)
        val notification = Notification.Builder(service, CHANNEL_ID)
            .setOngoing(true)
            .setContentTitle("Workout+")
            .setContentText("Workout overlay active")
            .setSmallIcon(R.drawable.ic_workout)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        service.startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun notifyPermissionNeeded() {
        if (permissionNotified) return
        permissionNotified = true
        service.karooSystem.dispatch(
            SystemNotification(
                id = "workout-overlay-permission",
                message = "Permission needed for the workout page",
                subText = "Allow Workout+ to draw over other apps",
                style = SystemNotification.Style.ERROR,
                actionIntent = AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "workout-overlay"
        private const val NOTIFICATION_ID = 0x301

        // chip width follows its content, clamped to this range and stepped to damp jitter
        private const val CHIP_MIN_WIDTH_FRACTION = 0.3
        private const val CHIP_MAX_WIDTH_FRACTION = 0.9
        private const val CHIP_WIDTH_STEP_DP = 8

        // beside Climber+'s chip (left half): ours fills the right half, a gap between
        private const val CHIP_BESIDE_WIDTH_FRACTION = 0.47
        private const val CHIP_BESIDE_EDGE_FRACTION = 0.02

        /** Height of the ride app's header row (ride time, battery, clock). */
        private const val RIDE_HEADER_DP = 32
    }
}
