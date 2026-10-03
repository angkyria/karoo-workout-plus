package com.angkyria.karooworkout.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.angkyria.karooworkout.R
import com.angkyria.karooworkout.data.CoreHeat
import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.Target
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.TargetStatus
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.overlay.PanelMachine.Size
import com.angkyria.karooworkout.settings.OverlayAnchor
import com.angkyria.karooworkout.settings.Settings
import com.angkyria.karooworkout.settings.TargetDisplay
import com.angkyria.karooworkout.settings.WorkoutField
import io.hammerhead.karooext.models.DataType
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-drawn workout overlay in the Karoo OS workout-drawer look, three sizes:
 *  - CHIP: output vs target (colored) + interval countdown
 *  - DRAWER: primary target bar, interval countdown with interval + workout progress
 *  - FULL: the workout page — targets, interval + workout (countdown, interval graph),
 *    CORE row and up to four data fields;
 *    shown by itself over the ride app's workout page (takeover, see [PanelMachine])
 *
 * Gestures: tap chip = open; tap drawer = full page; tap the top handle, or swipe
 * down = minimize to the chip; tap the target = visual/numeric; horizontal swipe
 * on the full page = change ride page underneath. No pause button: karoo-ext can
 * only pause the ride, not the workout. The window never takes key focus, so every
 * hardware button keeps its native ride-app action.
 */
@SuppressLint("ViewConstructor")
class WorkoutOverlayView(context: Context, private val machine: PanelMachine) : View(context) {

    private var state: WorkoutUiState.Shown? = null
    private var settings = Settings()
    private var profile = RiderProfile()
    private var sysValues: Map<String, Map<String, Double>> = emptyMap()

    /** Tapping the target flips visual/numeric for this ride; null = the setting. */
    private var displayOverride: TargetDisplay? = null

    /** Controller hook: the window must change size or flags. */
    var onRelayoutNeeded: (() -> Unit)? = null

    /** Controller hook: a horizontal swipe asks the ride app for the next / previous page. */
    var onPageSwipe: ((next: Boolean) -> Unit)? = null

    // hit areas, recorded while drawing
    private val targetHit = RectF()
    private val minimizeHit = RectF()

    // slow vertical drags count as swipes too (Karoo 2 touch under gloves is no flinger)
    private var downX = 0f
    private var downY = 0f
    private var gestureHandled = false

    private val display: TargetDisplay get() = displayOverride ?: settings.targetDisplay

    // ------------------------------------------------------------------ input

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                gestureHandled = true
                when {
                    machine.size == Size.CHIP -> resize { machine.expand(settings.pageMode) }
                    minimizeHit.contains(e.x, e.y) -> resize { machine.collapse(settings.pageMode) }
                    targetHit.contains(e.x, e.y) -> {
                        displayOverride =
                            if (display == TargetDisplay.VISUAL) TargetDisplay.NUMERIC else TargetDisplay.VISUAL
                        lastSignature = null
                        invalidate()
                    }
                    machine.size == Size.DRAWER -> resize { machine.expand(settings.pageMode) }
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy)) {
                    // full page covers the ride app: keep its horizontal page swipe working
                    if (machine.size != Size.FULL || abs(dx) < SWIPE_MIN_DISTANCE_PX ||
                        abs(velocityX) < SWIPE_MIN_VELOCITY
                    ) {
                        return false
                    }
                    gestureHandled = true
                    onPageSwipe?.invoke(dx < 0)
                    return true
                }
                if (abs(dy) < SWIPE_MIN_DISTANCE_PX || abs(velocityY) < SWIPE_MIN_VELOCITY) return false
                return verticalSwipe(dy)
            }
        },
    )

    /** Swipe away from the anchored edge grows, toward it minimizes. */
    private fun verticalSwipe(dy: Float): Boolean {
        // a top-anchored panel opens downward, so grow/shrink directions invert
        val topAnchored = anchor() == OverlayAnchor.TOP
        val grows = if (topAnchored) dy > 0 else dy < 0
        if (grows) {
            if (machine.size == Size.FULL) return false
            resize { machine.expand(settings.pageMode) }
        } else {
            // the workout page stays covered
            if (machine.size == Size.CHIP || machine.pinned(settings.pageMode)) return false
            resize { machine.collapse(settings.pageMode) }
        }
        gestureHandled = true
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            gestureHandled = false
        }
        val consumed = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !gestureHandled) {
            // a slow drag never reaches fling speed: judge it by distance instead
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dy) >= DRAG_MIN_DISTANCE_PX && abs(dy) > abs(dx) * 1.5f) verticalSwipe(dy)
        }
        return consumed || super.onTouchEvent(event)
    }

    private fun anchor(): OverlayAnchor = when (machine.size) {
        Size.CHIP -> settings.chipAnchor
        Size.DRAWER -> settings.drawerAnchor
        Size.FULL -> OverlayAnchor.BOTTOM
    }

    private inline fun resize(change: () -> Unit) {
        change()
        lastSignature = null
        onRelayoutNeeded?.invoke()
        invalidate()
    }

    // ----------------------------------------------------------------- update

    fun update(
        newState: WorkoutUiState.Shown,
        newSettings: Settings,
        newProfile: RiderProfile,
        newSysValues: Map<String, Map<String, Double>>,
    ) {
        if (newSettings.targetDisplay != settings.targetDisplay) displayOverride = null
        state = newState
        settings = newSettings
        profile = newProfile
        sysValues = newSysValues
        val signature = renderSignature(newState)
        if (signature == lastSignature) return
        lastSignature = signature
        invalidate()
    }

    /** Called by the controller after the window changed size underneath us. */
    fun onPanelChanged() {
        lastSignature = null
        invalidate()
    }

    private var lastSignature: List<Any?>? = null

    /**
     * Fingerprint of what onDraw renders at whole-number precision. Streams tick at
     * slightly different moments, so most updates repaint identical pixels — skip
     * those (an overlay redraw also recomposites everything beneath it; Karoo 2
     * battery). Must mirror onDraw: anything drawn that can change belongs here.
     */
    private fun renderSignature(s: WorkoutUiState.Shown): List<Any?> {
        fun Target?.sig() = this?.let { listOf(it.kind, Format.range(it), Format.output(it), it.status) }
        val common = listOf(
            machine.size, width, height, settings, display,
            s.stepIndex, s.stepCount, s.stepRemainingMs?.let { (it + 999) / 1000 },
            s.primary.sig(), s.paused, s.complete,
        )
        val rest = when (machine.size) {
            Size.CHIP -> emptyList()
            Size.DRAWER -> listOf(
                s.totalRemainingMs?.let { (it + 999) / 1000 },
                s.scalePercent,
                (s.stepProgress?.times(200))?.toInt(),
                (s.workoutProgress?.times(400))?.toInt(),
            )
            Size.FULL -> listOf(
                s.totalRemainingMs?.let { (it + 999) / 1000 },
                s.elapsedMs / 1000,
                s.scalePercent,
                s.secondary.sig(),
                s.history,
                s.workoutInRangePercent,
                profile,
                machine.pinned(settings.pageMode),
                settings.pageFields.map { fieldValue(it, s) to fieldColor(it) },
                coreHeat()?.let { heat ->
                    listOf(
                        Format.temperature(heat.core, profile.imperial),
                        Format.temperature(heat.skin, profile.imperial),
                        heat.hsi?.let { "%.1f".format(it) },
                    )
                },
            )
        }
        return common + rest
    }

    // ---------------------------------------------------------------- drawing

    // Karoo's data-field font (IBM Plex Sans Condensed on Karoo 3); Karoo 2 falls
    // back to the system condensed sans.
    private val font: Typeface = FONT_FILES.firstOrNull { File(it).isFile }
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WorkoutColors.PANEL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pastPaint = Paint().apply { color = 0x73000000 }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WorkoutColors.LILAC
        alpha = 70
        strokeWidth = 2f
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = font
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WorkoutColors.LILAC
        typeface = font
    }
    private val path = Path()
    private val tmp = RectF()

    private val powerIcon: Drawable? = context.getDrawable(R.drawable.ic_power)?.mutate()
    private val heartIcon: Drawable? = context.getDrawable(R.drawable.ic_heart)?.mutate()
    private val cadenceIcon: Drawable? = context.getDrawable(R.drawable.ic_cadence)?.mutate()
    private val workoutIcon: Drawable? = context.getDrawable(R.drawable.ic_workout)?.mutate()

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        targetHit.setEmpty()
        minimizeHit.setEmpty()
        when (machine.size) {
            Size.CHIP -> drawChip(canvas, s)
            Size.DRAWER -> drawDrawer(canvas, s)
            Size.FULL -> drawPage(canvas, s)
        }
    }

    private enum class Section { PRIMARY, SECONDARY, TIMING, CORE_HEAT, FIELDS }

    /** Stack [sections] (with height weights) top to bottom inside [area]. */
    private fun layout(area: RectF, gap: Float, sections: List<Pair<Section, Float>>): List<Pair<Section, RectF>> {
        val total = sections.sumOf { it.second.toDouble() }.toFloat()
        val avail = area.height() - gap * (sections.size - 1)
        var top = area.top
        return sections.map { (section, weight) ->
            val h = avail * weight / total
            val rect = RectF(area.left, top, area.right, top + h)
            top += h + gap
            section to rect
        }
    }

    // ------------------------------------------------------------- full page

    private fun drawPage(canvas: Canvas, s: WorkoutUiState.Shown) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val pad = width * 0.035f
        // no handle on the workout page: it can't be minimized there
        val top = if (machine.pinned(settings.pageMode)) pad * 0.8f else drawHandle(canvas, pad) + pad * 0.35f
        val area = RectF(pad, top, width - pad, height - pad)
        val secondary = s.secondary.takeIf { settings.showSecondary }
        val heat = coreHeat()
        // the data cells (CORE row, fields) must read at a glance: they get the room;
        // fields set to NONE drop out, a row at a time, and the rest grow
        val fieldRows = (shownFields().size + 1) / 2
        val sections = buildList {
            add(Section.PRIMARY to 22f)
            if (secondary != null) add(Section.SECONDARY to 8f)
            add(Section.TIMING to 35f)
            if (heat != null) add(Section.CORE_HEAT to 13f)
            if (fieldRows > 0) add(Section.FIELDS to 15f * fieldRows)
        }
        for ((section, rect) in layout(area, height * 0.014f, sections)) {
            when (section) {
                Section.PRIMARY -> drawPrimary(canvas, rect, s)
                Section.SECONDARY -> secondary?.let { drawSecondary(canvas, rect, it) }
                Section.TIMING -> drawTiming(canvas, rect, s, withGraph = true)
                Section.CORE_HEAT -> heat?.let { drawCoreHeat(canvas, rect, it) }
                Section.FIELDS -> drawFields(canvas, rect, s)
            }
        }
    }

    /** The CORE reading for the strip, or null when the strip is off or no CORE streams. */
    private fun coreHeat(): CoreHeat.Reading? =
        if (!settings.coreHeatStrip) null else CoreHeat.reading(sysValues).takeIf { it.present }

    /**
     * Core heat row, like CORE Heat's HUD: CORE and SKIN temperature colored by the heat
     * zone, the Heat Strain Index on a zone-colored cell between them.
     */
    private fun drawCoreHeat(canvas: Canvas, rect: RectF, heat: CoreHeat.Reading) {
        val gap = width * 0.02f
        val w = (rect.width() - 2 * gap) / 3f
        val tempColor = heat.color ?: Color.WHITE
        val core = Format.temperature(heat.core, profile.imperial)
        drawCell(canvas, rect.left, rect.top, w, rect.height(), "CORE", core, tempColor)
        val hsi = heat.hsi?.let { "%.1f".format(it) } ?: "--"
        val onFill = if (heat.color != null) WorkoutColors.TEXT_DARK else Color.WHITE
        drawCell(canvas, rect.left + w + gap, rect.top, w, rect.height(), "HSI", hsi, onFill, fill = heat.color)
        val skin = Format.temperature(heat.skin, profile.imperial)
        drawCell(canvas, rect.left + 2 * (w + gap), rect.top, w, rect.height(), "SKIN", skin, tempColor)
    }

    /**
     * Bottom-sheet handle with a down chevron, top center: tap it (or swipe down)
     * to minimize to the chip. Returns its bottom.
     */
    private fun drawHandle(canvas: Canvas, pad: Float): Float {
        val cx = width / 2f
        val barW = width * 0.12f
        val barH = max(4f, height * 0.006f)
        val top = pad * 0.3f
        fillPaint.color = WorkoutColors.LILAC_DIM
        tmp.set(cx - barW / 2, top, cx + barW / 2, top + barH)
        canvas.drawRoundRect(tmp, barH / 2, barH / 2, fillPaint)
        val chevronTop = top + barH * 2.2f
        val chevronH = barH * 1.8f
        glyphPaint.color = WorkoutColors.LILAC_DIM
        glyphPaint.strokeWidth = barH * 0.8f
        glyphPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - chevronH * 1.4f, chevronTop, cx, chevronTop + chevronH, glyphPaint)
        canvas.drawLine(cx, chevronTop + chevronH, cx + chevronH * 1.4f, chevronTop, glyphPaint)
        glyphPaint.color = Color.WHITE
        glyphPaint.strokeCap = Paint.Cap.BUTT
        val bottom = chevronTop + chevronH
        // generous: the strip between the target label and its range
        minimizeHit.set(width * 0.3f, 0f, width * 0.7f, bottom + height * 0.06f)
        return bottom
    }

    // ---------------------------------------------------------------- drawer

    private fun drawDrawer(canvas: Canvas, s: WorkoutUiState.Shown) {
        val r = width * 0.04f
        // rounded on the open edge only: square corners against the screen edge
        tmp.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(tmp, r, r, bgPaint)
        if (settings.drawerAnchor == OverlayAnchor.TOP) {
            canvas.drawRect(0f, 0f, width.toFloat(), r, bgPaint)
        } else {
            canvas.drawRect(0f, height - r, width.toFloat(), height.toFloat(), bgPaint)
        }
        val pad = width * 0.035f
        val area = RectF(pad, pad * 0.8f, width - pad, height - pad)
        val sections = listOf(Section.PRIMARY to 40f, Section.TIMING to 60f)
        for ((section, rect) in layout(area, height * 0.03f, sections)) {
            when (section) {
                Section.PRIMARY -> drawPrimary(canvas, rect, s)
                Section.TIMING -> drawTiming(canvas, rect, s, withGraph = false)
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- target

    private fun iconFor(kind: TargetKind?): Drawable? = when (kind) {
        TargetKind.POWER -> powerIcon
        TargetKind.HEART_RATE -> heartIcon
        TargetKind.CADENCE -> cadenceIcon
        else -> workoutIcon
    }

    /** Header row: [icon] LABEL on the left, (target) range on the right. */
    private fun drawTargetHeader(canvas: Canvas, rect: RectF, t: Target?, size: Float) {
        val baseline = rect.top + size * 0.95f
        var x = rect.left
        iconFor(t?.kind)?.let { icon ->
            icon.setTint(WorkoutColors.ACCENT)
            x += drawIcon(canvas, icon, x, baseline, size * 0.95f) + size * 0.25f
        }
        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        canvas.drawText(t?.kind?.label ?: "FREE RIDE", x, baseline, textPaint)
        if (t == null) return
        textPaint.textAlign = Paint.Align.RIGHT
        val range = Format.range(t)
        canvas.drawText(range, rect.right, baseline, textPaint)
        val rangeW = textPaint.measureText(range)
        drawTargetGlyph(canvas, rect.right - rangeW - size * 0.55f, baseline - size * 0.36f, size * 0.36f)
    }

    /** Header text size: capped by the screen width, then shrunk until label and range fit. */
    private fun targetHeaderSize(rect: RectF, t: Target?): Float {
        val label = t?.kind?.label ?: "FREE RIDE"
        val range = t?.let { Format.range(it) }
        fun need(size: Float): Float {
            textPaint.textSize = size
            var w = size * 1.2f + textPaint.measureText(label) // icon + label
            if (range != null) w += size * 1.5f + textPaint.measureText(range) // gap, glyph + range
            return w
        }
        var size = min(rect.height() * 0.24f, width * 0.075f)
        while (need(size) > rect.width() && size > 8f) size *= 0.94f
        return size
    }

    private fun drawPrimary(canvas: Canvas, rect: RectF, s: WorkoutUiState.Shown) {
        val t = s.primary
        val headerSize = targetHeaderSize(rect, t)
        drawTargetHeader(canvas, rect, t, headerSize)
        val bar = RectF(rect.left, rect.top + headerSize * 1.4f, rect.right, rect.bottom)
        targetHit.set(rect)
        if (t == null) {
            fillPaint.color = WorkoutColors.TRACK
            canvas.drawRoundRect(bar, bar.height() * 0.16f, bar.height() * 0.16f, fillPaint)
            return
        }
        if (display == TargetDisplay.VISUAL) drawTargetBar(canvas, bar, t) else drawTargetNumber(canvas, bar, t)
    }

    /**
     * Visual target: a track whose middle third is the target range, and a value box
     * that slides along it — green inside the range, blue (up arrow) below, red
     * (down arrow) above — the native workout-drawer reading.
     */
    private fun drawTargetBar(canvas: Canvas, bar: RectF, t: Target) {
        val r = bar.height() * 0.16f
        fillPaint.color = WorkoutColors.TRACK
        canvas.drawRoundRect(bar, r, r, fillPaint)

        val span = (t.max - t.min).coerceAtLeast(1.0)
        val lo = t.min - span
        val hi = t.max + span
        fun xOf(v: Double): Float = bar.left + ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * bar.width()

        fillPaint.color = WorkoutColors.BAND
        tmp.set(xOf(t.min), bar.top, xOf(t.max), bar.bottom)
        canvas.drawRect(tmp, fillPaint)

        val text = Format.output(t)
        textPaint.textSize = bar.height() * 0.66f
        val arrowW = if (hasArrow(t.status)) textPaint.textSize * 0.5f else 0f
        val gap = if (arrowW > 0) textPaint.textSize * 0.2f else 0f
        val contentW = textPaint.measureText(text) + arrowW + gap
        val boxW = max(bar.width() / 3f, contentW + bar.height() * 0.5f)
        val cx = (t.output?.let { xOf(it) } ?: bar.centerX())
            .coerceIn(bar.left + boxW / 2, bar.right - boxW / 2)
        tmp.set(cx - boxW / 2, bar.top, cx + boxW / 2, bar.bottom)
        fillPaint.color = WorkoutColors.status(t.status)
        canvas.drawRoundRect(tmp, r, r, fillPaint)
        drawValue(canvas, text, t.status, cx - contentW / 2, bar.centerY(), arrowW, gap)
    }

    /** Numeric target: the whole bar in the status color, big number centered. */
    private fun drawTargetNumber(canvas: Canvas, bar: RectF, t: Target) {
        val r = bar.height() * 0.16f
        fillPaint.color = WorkoutColors.status(t.status)
        canvas.drawRoundRect(bar, r, r, fillPaint)
        val text = Format.output(t)
        textPaint.textSize = bar.height() * 0.8f
        val arrowW = if (hasArrow(t.status)) textPaint.textSize * 0.5f else 0f
        val gap = if (arrowW > 0) textPaint.textSize * 0.2f else 0f
        val contentW = textPaint.measureText(text) + arrowW + gap
        drawValue(canvas, text, t.status, bar.centerX() - contentW / 2, bar.centerY(), arrowW, gap)
    }

    private fun hasArrow(status: TargetStatus) = status == TargetStatus.UNDER || status == TargetStatus.OVER

    /** Arrow (if any) then the number, starting at [x], vertically centered on [cy]. */
    private fun drawValue(
        canvas: Canvas,
        text: String,
        status: TargetStatus,
        x: Float,
        cy: Float,
        arrowW: Float,
        gap: Float,
    ) {
        val color = WorkoutColors.onStatus(status)
        if (arrowW > 0) {
            fillPaint.color = color
            drawArrow(canvas, x, cy, arrowW, up = status == TargetStatus.UNDER)
        }
        textPaint.color = color
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, x + arrowW + gap, cy + capHeight(textPaint) / 2, textPaint)
        textPaint.color = Color.WHITE
    }

    /** Solid triangle [w] wide centered on [cy]; up = "push harder". */
    private fun drawArrow(canvas: Canvas, x: Float, cy: Float, w: Float, up: Boolean) {
        val h = w * 0.8f
        path.rewind()
        if (up) {
            path.moveTo(x, cy + h / 2)
            path.lineTo(x + w, cy + h / 2)
            path.lineTo(x + w / 2, cy - h / 2)
        } else {
            path.moveTo(x, cy - h / 2)
            path.lineTo(x + w, cy - h / 2)
            path.lineTo(x + w / 2, cy + h / 2)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    /** Compact secondary target row: label, colored output, range. */
    private fun drawSecondary(canvas: Canvas, rect: RectF, t: Target) {
        val r = rect.height() * 0.22f
        fillPaint.color = WorkoutColors.TRACK
        canvas.drawRoundRect(rect, r, r, fillPaint)
        val size = min(rect.height() * 0.5f, width * 0.065f)
        val inset = rect.height() * 0.3f
        val baseline = rect.centerY() + capHeight(size) / 2
        var x = rect.left + inset
        iconFor(t.kind)?.let { icon ->
            icon.setTint(WorkoutColors.ACCENT)
            x += drawIcon(canvas, icon, x, baseline, size * 0.95f) + size * 0.25f
        }
        labelPaint.textSize = size
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(t.kind.label, x, baseline, labelPaint)

        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.RIGHT
        val range = Format.range(t)
        canvas.drawText(range, rect.right - inset, baseline, textPaint)
        val rangeLeft = rect.right - inset - textPaint.measureText(range)
        drawTargetGlyph(canvas, rangeLeft - size * 0.55f, rect.centerY(), size * 0.36f)

        // output pill, right of center
        val text = Format.output(t)
        textPaint.textSize = size * 1.05f
        val arrowW = if (hasArrow(t.status)) textPaint.textSize * 0.5f else 0f
        val gap = if (arrowW > 0) textPaint.textSize * 0.2f else 0f
        val contentW = textPaint.measureText(text) + arrowW + gap
        val pillW = contentW + rect.height() * 0.6f
        val cx = min(rect.left + rect.width() * 0.56f, rangeLeft - size * 1.2f - pillW / 2)
        tmp.set(cx - pillW / 2, rect.top + rect.height() * 0.12f, cx + pillW / 2, rect.bottom - rect.height() * 0.12f)
        fillPaint.color = WorkoutColors.status(t.status)
        canvas.drawRoundRect(tmp, r, r, fillPaint)
        drawValue(canvas, text, t.status, cx - contentW / 2, rect.centerY(), arrowW, gap)
    }

    // ----------------------------------------------------- interval + workout

    /**
     * Interval and workout in one card: INTERVAL 3 OF 9 with the workout's time left in
     * the header, the interval countdown (PAUSED / OPEN beside it), the interval bar,
     * and under it the interval graph (full page) or a thin workout bar (drawer).
     */
    private fun drawTiming(canvas: Canvas, rect: RectF, s: WorkoutUiState.Shown, withGraph: Boolean) {
        val title = if (s.complete) "WORKOUT" else "INTERVAL"
        val counter = if (s.complete) {
            s.workoutInRangePercent?.let { "IN RANGE $it%" } ?: ""
        } else {
            "${s.stepIndex + 1} OF ${s.stepCount}"
        }
        // once the workout is done the title says WORKOUT: no time left beside it
        val left = if (s.complete) null else s.totalRemainingMs?.let { Format.countdown(it) } ?: "--:--"
        val scale = s.scalePercent?.takeIf { it != 100 && left != null }?.let { "$it%" }

        // capped by the screen width, then shrunk until both halves fit the row
        fun headerWidth(size: Float): Float {
            labelPaint.textSize = size
            var w = labelPaint.measureText("$title $counter") + size
            if (left != null) w += labelPaint.measureText("WORKOUT $left") + size * 0.3f
            if (scale != null) w += labelPaint.measureText(scale) + size * 0.6f
            return w
        }
        var headerSize = min(rect.height() * (if (withGraph) 0.11f else 0.15f), width * 0.055f)
        while (headerWidth(headerSize) > rect.width() && headerSize > 8f) headerSize *= 0.94f
        val headerBaseline = rect.top + headerSize * 0.95f
        labelPaint.textSize = headerSize
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, rect.left, headerBaseline, labelPaint)
        textPaint.textSize = headerSize
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        canvas.drawText(counter, rect.left + labelPaint.measureText("$title "), headerBaseline, textPaint)
        if (left != null) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(left, rect.right, headerBaseline, textPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            var x = rect.right - textPaint.measureText(left) - headerSize * 0.3f
            canvas.drawText("WORKOUT", x, headerBaseline, labelPaint)
            if (scale != null) {
                x -= labelPaint.measureText("WORKOUT") + headerSize * 0.6f
                labelPaint.color = WorkoutColors.ENDING
                canvas.drawText(scale, x, headerBaseline, labelPaint)
                labelPaint.color = WorkoutColors.LILAC
            }
            labelPaint.textAlign = Paint.Align.LEFT
        }

        // bottom up: the graph or the workout bar, then the interval bar
        var bottom = rect.bottom
        if (withGraph) {
            val graph = RectF(rect.left, rect.bottom - rect.height() * 0.32f, rect.right, rect.bottom)
            drawGraph(canvas, graph, s)
            bottom = graph.top - rect.height() * 0.05f
        } else {
            val workoutBar = RectF(rect.left, bottom - rect.height() * 0.035f, rect.right, bottom)
            val done = if (s.complete) 1f else s.workoutProgress ?: 0f
            drawProgressBar(canvas, workoutBar, done, WorkoutColors.LILAC_DIM)
            bottom = workoutBar.top - rect.height() * 0.04f
        }
        val bar = RectF(rect.left, bottom - rect.height() * (if (withGraph) 0.04f else 0.06f), rect.right, bottom)
        drawProgressBar(canvas, bar, if (s.complete) 1f else s.stepProgress ?: 0f)

        // the countdown, as big as its row allows
        val digits = RectF(rect.left, rect.top + headerSize * 1.2f, rect.right, bar.top - rect.height() * 0.035f)
        val d = digits.height()
        val status = when {
            s.complete -> null
            s.paused -> "PAUSED"
            s.stepRemainingMs == null -> "OPEN"
            else -> null
        }
        var reserve = 0f
        if (status != null) {
            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.color = WorkoutColors.ENDING
            canvas.drawText(status, digits.right, digits.centerY() + capHeight(headerSize) / 2, labelPaint)
            reserve = labelPaint.measureText(status) + headerSize * 0.6f
            labelPaint.color = WorkoutColors.LILAC
            labelPaint.textAlign = Paint.Align.LEFT
        }
        val remaining = s.stepRemainingMs
        val text = when {
            s.complete -> "DONE"
            remaining == null -> Format.elapsed(s.current?.actualMs ?: 0)
            else -> Format.countdown(remaining)
        }
        textPaint.color = when {
            s.complete -> WorkoutColors.IN_RANGE
            remaining != null && remaining in 1..ENDING_MS -> WorkoutColors.ENDING
            else -> Color.WHITE
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = d * 1.18f
        fitWidth(textPaint, text, digits.width() - d * 0.5f - reserve)
        val baseline = digits.centerY() + capHeight(textPaint) / 2
        canvas.drawText(text, digits.left, baseline, textPaint)
        textPaint.color = Color.WHITE
        if (!s.complete) {
            val gx = digits.left + textPaint.measureText(text) + d * 0.26f
            drawTimerGlyph(canvas, gx, baseline - d * 0.13f, d * 0.12f)
        }
    }

    private fun drawProgressBar(canvas: Canvas, bar: RectF, fraction: Float, color: Int = WorkoutColors.LILAC) {
        val r = bar.height() / 2
        fillPaint.color = WorkoutColors.TRACK
        canvas.drawRoundRect(bar, r, r, fillPaint)
        if (fraction <= 0f) return
        tmp.set(bar.left, bar.top, bar.left + max(bar.height(), bar.width() * fraction), bar.bottom)
        fillPaint.color = color
        canvas.drawRoundRect(tmp, r, r, fillPaint)
    }

    /**
     * Interval graph of the workout as ridden: completed intervals at their real
     * length (shaded), the current one outlined, and the rest of the workout —
     * which Karoo doesn't reveal through karoo-ext — as a hatched block.
     */
    private fun drawGraph(canvas: Canvas, area: RectF, s: WorkoutUiState.Shown) {
        val r = area.height() * 0.1f
        fillPaint.color = WorkoutColors.TRACK
        canvas.drawRoundRect(area, r, r, fillPaint)

        val history = s.history
        val done = history.dropLast(1).sumOf { it.actualMs }
        val current = history.lastOrNull()
        val currentLen = current?.let { max(it.plannedMs ?: it.actualMs, it.actualMs) } ?: 0L
        val elapsed = done + (current?.actualMs ?: 0L)
        val total = max(done + currentLen + s.upcomingMs, 1L)
        fun xOf(t: Long): Float = area.left + (t.toFloat() / total).coerceIn(0f, 1f) * area.width()

        val levels = history.flatMap { listOfNotNull(it.startLevel, it.endLevel) }
        val ref = max(levels.maxOrNull() ?: 1.0, (profile.ftp ?: 0) * 1.2).coerceAtLeast(1.0)
        fun yOf(level: Double): Float =
            area.bottom - (0.1f + 0.82f * (level / ref).toFloat().coerceIn(0f, 1f)) * area.height()

        canvas.save()
        canvas.clipRect(area)
        var t = 0L
        history.forEachIndexed { i, rec ->
            val isCurrent = i == history.lastIndex
            val len = if (isCurrent) currentLen else rec.actualMs
            if (len <= 0) return@forEachIndexed
            val x0 = xOf(t)
            val x1 = max(xOf(t + len), x0 + 1f)
            val start = rec.startLevel
            path.rewind()
            if (start != null) {
                val end = rec.endLevel ?: start
                path.moveTo(x0, area.bottom)
                path.lineTo(x0, yOf(start))
                path.lineTo(x1, yOf(end))
                path.lineTo(x1, area.bottom)
                fillPaint.color = WorkoutColors.zone(profile.zoneOf(rec.kind, (start + end) / 2))
            } else {
                // free-ride step: a low neutral block
                path.addRect(x0, area.bottom - area.height() * 0.1f, x1, area.bottom, Path.Direction.CW)
                fillPaint.color = WorkoutColors.LILAC_DIM
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            if (isCurrent) canvas.drawPath(path, outlinePaint)
            t += len
        }
        // the ridden part sits in shade; the unknown remainder is hatched
        canvas.drawRect(area.left, area.top, xOf(elapsed), area.bottom, pastPaint)
        val upcomingStart = xOf(done + currentLen)
        if (s.upcomingMs > 0 && upcomingStart < area.right - 2f) {
            val step = area.height() * 0.22f
            var hx = upcomingStart - area.height()
            while (hx < area.right) {
                canvas.drawLine(hx, area.bottom, hx + area.height(), area.top, hatchPaint)
                hx += step
            }
            val left = s.stepCount - s.stepIndex - 1
            labelPaint.textSize = area.height() * 0.32f
            val label = "+$left"
            if (left > 0 && labelPaint.measureText(label) < area.right - upcomingStart - 8f) {
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    label,
                    (upcomingStart + area.right) / 2,
                    area.centerY() + capHeight(labelPaint) / 2,
                    labelPaint,
                )
                labelPaint.textAlign = Paint.Align.LEFT
            }
        }
        canvas.restore()
        val nx = xOf(elapsed)
        canvas.drawLine(nx, area.top, nx, area.bottom, nowPaint)
    }

    // ---------------------------------------------------------------- fields

    /** The page's data fields, without the slots set to [WorkoutField.NONE]. */
    private fun shownFields(): List<WorkoutField> = settings.pageFields.take(4).filter { it != WorkoutField.NONE }

    /** Two fields a row; an odd one out spans the whole row. */
    private fun drawFields(canvas: Canvas, area: RectF, s: WorkoutUiState.Shown) {
        val rows = shownFields().chunked(2)
        if (rows.isEmpty()) return
        val gap = width * 0.02f
        val cellH = (area.height() - gap * (rows.size - 1)) / rows.size
        rows.forEachIndexed { r, row ->
            val cellW = (area.width() - gap * (row.size - 1)) / row.size
            row.forEachIndexed { c, field ->
                val left = area.left + c * (cellW + gap)
                val top = area.top + r * (cellH + gap)
                drawCell(canvas, left, top, cellW, cellH, field.label, fieldValue(field, s), fieldColor(field) ?: Color.WHITE)
            }
        }
    }

    /**
     * One data cell: label top left, value bottom right, each as big as the cell allows
     * and shrunk only to fit its width. [fill] colors the whole cell (dark label on it).
     */
    private fun drawCell(
        canvas: Canvas,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        label: String,
        value: String,
        valueColor: Int,
        fill: Int? = null,
    ) {
        tmp.set(left, top, left + w, top + h)
        fillPaint.color = fill ?: WorkoutColors.TRACK
        canvas.drawRoundRect(tmp, h * 0.12f, h * 0.12f, fillPaint)
        val pad = min(w, h * 1.6f) * 0.06f

        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = h * 0.25f
        fitWidth(labelPaint, label, w - 2 * pad)
        labelPaint.color = if (fill != null) WorkoutColors.TEXT_DARK else WorkoutColors.LILAC
        canvas.drawText(label, left + pad, top + h * 0.29f, labelPaint)
        labelPaint.color = WorkoutColors.LILAC

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = h * 0.6f
        fitWidth(textPaint, value, w - 2 * pad)
        textPaint.color = valueColor
        canvas.drawText(value, left + w - pad, top + h * 0.9f, textPaint)
        textPaint.color = Color.WHITE
    }

    private fun fitWidth(paint: Paint, text: String, maxW: Float) {
        while (paint.measureText(text) > maxW && paint.textSize > 8f) paint.textSize *= 0.94f
    }

    /** CORE's colors for the heat fields: zone colors, adaptation level blues. */
    private fun fieldColor(field: WorkoutField): Int? {
        fun single(typeId: String) = sysValues[typeId]?.get(DataType.Field.SINGLE)
        return when (field) {
            WorkoutField.HEAT_STRAIN -> single(CoreHeat.HEAT_STRAIN)?.let(CoreHeat::hsiColor)
            WorkoutField.HEAT_ZONE -> single(CoreHeat.HEAT_ZONE)?.let { CoreHeat.zoneColor(it.toInt()) }
            WorkoutField.HEAT_ADAPTATION -> single(CoreHeat.HEAT_ADAPTATION)?.let(CoreHeat::adaptationColor)
            WorkoutField.CORE_TEMP, WorkoutField.SKIN_TEMP -> CoreHeat.reading(sysValues).color
            else -> null
        }
    }

    private fun fieldValue(field: WorkoutField, s: WorkoutUiState.Shown): String = when (field) {
        WorkoutField.WORKOUT_IN_RANGE -> s.workoutInRangePercent?.toString() ?: "--"
        WorkoutField.INTERVAL_IN_RANGE -> s.current?.inRangePercent?.toString() ?: "--"
        WorkoutField.WORKOUT_ELAPSED -> Format.elapsed(s.elapsedMs)
        WorkoutField.WORKOUT_REMAINING -> s.totalRemainingMs?.let { Format.countdown(it) } ?: "--"
        WorkoutField.INTERVAL_COUNT -> "${s.stepIndex + 1}/${s.stepCount}"
        WorkoutField.SCALE -> s.scalePercent?.toString() ?: "--"
        else -> field.dataTypeId?.let {
            Format.field(field.format, sysValues[it], profile.imperial, field.valueField)
        } ?: "--"
    }

    // ------------------------------------------------------------------- chip

    /** Chip: [output vs target box] countdown — or "3/9  1:23" when there's no target. */
    private fun chipParts(s: WorkoutUiState.Shown): Pair<Target?, String> {
        val time = when {
            s.complete -> "DONE"
            s.stepRemainingMs == null -> "OPEN"
            else -> Format.countdown(s.stepRemainingMs)
        }
        return s.primary to if (s.primary == null) "${s.stepIndex + 1}/${s.stepCount}  $time" else time
    }

    /** Width the chip window needs at [heightPx] tall (the window sizes to its text). */
    fun chipContentWidth(heightPx: Int): Int {
        val s = state ?: return 0
        val (t, time) = chipParts(s)
        textPaint.textSize = heightPx * CHIP_TEXT_FRACTION
        var w = textPaint.measureText(time) + heightPx * 0.7f
        if (t != null) w += chipBoxWidth(t, heightPx.toFloat()) + heightPx * 0.25f
        return w.toInt()
    }

    private fun chipBoxWidth(t: Target, h: Float): Float {
        textPaint.textSize = h * CHIP_TEXT_FRACTION
        val arrowW = if (hasArrow(t.status)) textPaint.textSize * 0.5f + textPaint.textSize * 0.2f else 0f
        return textPaint.measureText(Format.output(t)) + arrowW + h * 0.4f
    }

    private fun drawChip(canvas: Canvas, s: WorkoutUiState.Shown) {
        val h = height.toFloat()
        tmp.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(tmp, h / 2, h / 2, bgPaint)
        val (t, time) = chipParts(s)
        textPaint.textSize = h * CHIP_TEXT_FRACTION
        val timeW = textPaint.measureText(time)
        val boxW = t?.let { chipBoxWidth(it, h) } ?: 0f
        val boxGap = if (t != null) h * 0.25f else 0f
        var x = (width - (boxW + boxGap + timeW)) / 2f
        if (t != null) {
            val inset = h * 0.15f
            tmp.set(x, inset, x + boxW, h - inset)
            fillPaint.color = WorkoutColors.status(t.status)
            canvas.drawRoundRect(tmp, h * 0.2f, h * 0.2f, fillPaint)
            textPaint.textSize = h * CHIP_TEXT_FRACTION
            val arrowW = if (hasArrow(t.status)) textPaint.textSize * 0.5f else 0f
            val gap = if (arrowW > 0) textPaint.textSize * 0.2f else 0f
            drawValue(canvas, Format.output(t), t.status, x + h * 0.2f, h / 2, arrowW, gap)
            x += boxW + boxGap
        }
        textPaint.textSize = h * CHIP_TEXT_FRACTION
        textPaint.textAlign = Paint.Align.LEFT
        val remaining = s.stepRemainingMs
        textPaint.color = if (remaining != null && remaining in 1..ENDING_MS) WorkoutColors.ENDING else Color.WHITE
        canvas.drawText(time, x, h / 2 + capHeight(textPaint) / 2, textPaint)
        textPaint.color = Color.WHITE
    }

    // ---------------------------------------------------------------- glyphs

    /**
     * Draws [icon] with its bottom on [baseline], scaled to [size] tall.
     * Returns the drawn width (0 if the icon failed to load).
     */
    private fun drawIcon(canvas: Canvas, icon: Drawable, x: Float, baseline: Float, size: Float): Float {
        val w = size * icon.intrinsicWidth / icon.intrinsicHeight
        icon.setBounds(x.toInt(), (baseline - size).toInt(), (x + w).toInt(), baseline.toInt())
        icon.draw(canvas)
        return w
    }

    /** Native "target" glyph: ring + dot. */
    private fun drawTargetGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        glyphPaint.strokeWidth = r * 0.28f
        canvas.drawCircle(cx, cy, r, glyphPaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.42f, fillPaint)
    }

    /** Small stopwatch next to the countdown (native interval-time glyph). */
    private fun drawTimerGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        glyphPaint.color = WorkoutColors.LILAC
        glyphPaint.strokeWidth = r * 0.24f
        canvas.drawCircle(cx, cy, r, glyphPaint)
        canvas.drawLine(cx, cy, cx, cy - r * 0.6f, glyphPaint)
        canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, glyphPaint)
        glyphPaint.color = Color.WHITE
    }

    private fun capHeight(paint: Paint): Float = capHeight(paint.textSize)

    /** Visible digit height (cap height) of the overlay font at [textSize]. */
    private fun capHeight(textSize: Float): Float = textSize * 0.72f

    companion object {
        private val FONT_FILES = listOf(
            "/system/fonts/IBMPlexSansCondensed-Medium.otf",
            "/system/fonts/IBMPlexSansCondensed-SemiBold.otf",
        )

        /** Chip text size as a fraction of the chip height. */
        private const val CHIP_TEXT_FRACTION = 0.56f

        /** Countdown turns amber for the last seconds of an interval. */
        private const val ENDING_MS = 5_000L

        private const val SWIPE_MIN_DISTANCE_PX = 50f
        private const val SWIPE_MIN_VELOCITY = 250f

        /** A vertical drag at least this long minimizes / opens even when slow. */
        private const val DRAG_MIN_DISTANCE_PX = 90f
    }
}
