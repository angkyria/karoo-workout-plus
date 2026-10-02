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

    /** Pause button hook (the extension knows whether this is a real ride or the demo). */
    var onPauseToggle: (() -> Unit)? = null

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

        // pages that aren't taken over may hide the chip entirely
        val wanted = machine.size != Size.CHIP || settings.chipOnOtherPages
        val current = view
        if (!wanted) {
            removeWindow()
        } else if (current == null) {
            val created = WorkoutOverlayView(service, machine)
            view = created
            // no relayout hook yet: the view is not attached to the window manager
            created.update(state, settings, profile, sysValues)
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
            created.onPauseToggle = { onPauseToggle?.invoke() }
        } else {
            current.update(state, settings, profile, sysValues)
            applyLayoutIfChanged(current, settings)
        }
        fieldsVisible.value = view != null && machine.size == Size.FULL
    }

    fun hide() {
        if (workoutActive) machine.onWorkoutEnded()
        workoutActive = false
        removeWindow()
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
        val settings = lastSettings ?: return
        val v = view
        if (v == null || !v.isAttachedToWindow) return
        if (machine.size == Size.CHIP && !settings.chipOnOtherPages) {
            removeWindow()
            fieldsVisible.value = false
            return
        }
        applyLayoutIfChanged(v, settings)
        v.onPanelChanged()
        fieldsVisible.value = machine.size == Size.FULL
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
                height = (metrics.heightPixels * CHIP_HEIGHT_FRACTION).toInt()
                // size to the content; quantized so a ticking digit doesn't relayout every second
                val minW = (metrics.widthPixels * CHIP_MIN_WIDTH_FRACTION).toInt()
                val maxW = (metrics.widthPixels * CHIP_MAX_WIDTH_FRACTION).toInt()
                val step = (CHIP_WIDTH_STEP_DP * metrics.density).toInt().coerceAtLeast(1)
                val content = v.chipContentWidth(height)
                width = (((content + step - 1) / step) * step).coerceIn(minW, maxW)
            }
            Size.DRAWER -> {
                width = metrics.widthPixels
                height = metrics.heightPixels * settings.heightPercent / 100
            }
            Size.FULL -> {
                // the workout page: everything below the ride header / status bar
                width = metrics.widthPixels
                height = metrics.heightPixels - topInsetPx
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
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            // top-anchored panels start below the status bar, the strip FULL keeps visible
            if (size != Size.FULL && top) y = topInsetPx
            // keep the bottom chip clear of Karoo's own bottom drawers
            if (size == Size.CHIP && !top) {
                y = (metrics.heightPixels * CHIP_EDGE_OFFSET_FRACTION).toInt()
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
        private const val CHIP_HEIGHT_FRACTION = 0.075
        private const val CHIP_EDGE_OFFSET_FRACTION = 0.03

        /** Height of the ride app's header row (ride time, battery, clock). */
        private const val RIDE_HEADER_DP = 32
    }
}
