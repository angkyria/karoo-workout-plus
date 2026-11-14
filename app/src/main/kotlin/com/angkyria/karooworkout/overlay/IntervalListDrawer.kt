package com.angkyria.karooworkout.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.angkyria.karooworkout.data.IntervalRecord
import com.angkyria.karooworkout.data.NextList
import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.UpcomingRow
import com.angkyria.karooworkout.data.WorkoutUiState
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The interval list under the workout page's first screen: NEXT (upcoming, closest
 * first, "starts in" on the right), then DONE (finished, newest first, average and
 * in-range share). Pure drawing on the view's paints; the view scrolls the canvas.
 * Sized from the first screen ([width] x [height]), so rows read like the page's cells.
 */
internal class IntervalListDrawer(
    private val text: Paint,
    private val label: Paint,
    private val fill: Paint,
    private val glyph: Paint,
) {
    private val tmp = RectF()

    private class Metrics(width: Int, height: Int) {
        val pad = width * 0.035f
        val headerH = height * 0.075f
        val rowH = height * 0.085f
        val gap = height * 0.012f
        val textSize = min(rowH * 0.5f, width * 0.068f)
        val headerSize = min(headerH * 0.42f, width * 0.05f)
    }

    /** The finished intervals for DONE, newest first: the history without the one running. */
    fun done(s: WorkoutUiState.Shown): List<IntervalRecord> =
        s.history.filter { s.complete || it.index != s.stepIndex }.asReversed()

    private fun nextRowCount(next: NextList): Int = when (next) {
        is NextList.Planned -> next.rows.size.coerceAtLeast(1)
        is NextList.Unknown -> 1
    }

    /** Height of the whole list, drawn from the first screen's bottom on. */
    fun height(width: Int, height: Int, s: WorkoutUiState.Shown, next: NextList): Float {
        val m = Metrics(width, height)
        val done = done(s).size
        // a finished workout has nothing next: DONE alone
        var h = if (s.complete) 0f else m.headerH + nextRowCount(next) * (m.rowH + m.gap)
        if (done > 0) h += m.headerH + done * (m.rowH + m.gap)
        return h + m.pad
    }

    /**
     * Draws the list with its top at [top] (canvas coordinates); only the rows that
     * fall inside [visibleTop]..[visibleBottom] are drawn.
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        top: Float,
        visibleTop: Float,
        visibleBottom: Float,
        s: WorkoutUiState.Shown,
        next: NextList,
        profile: RiderProfile,
    ) {
        val m = Metrics(width, height)
        val left = m.pad
        val right = width - m.pad
        var y = top
        fun visible(h: Float) = y + h >= visibleTop && y <= visibleBottom

        if (!s.complete) {
            if (visible(m.headerH)) header(canvas, m, left, right, y, "NEXT", "starts in")
            y += m.headerH
            when (next) {
                is NextList.Unknown -> {
                    if (visible(m.rowH)) note(canvas, m, left, right, y, Format.unknownNext(next.count, next.remainingMs))
                    y += m.rowH + m.gap
                }
                is NextList.Planned -> {
                    if (next.rows.isEmpty()) {
                        if (visible(m.rowH)) note(canvas, m, left, right, y, "last interval")
                        y += m.rowH + m.gap
                    }
                    for (row in next.rows) {
                        if (visible(m.rowH)) nextRow(canvas, m, left, right, y, row, profile)
                        y += m.rowH + m.gap
                    }
                }
            }
        }
        val done = done(s)
        if (done.isEmpty()) return
        if (visible(m.headerH)) header(canvas, m, left, right, y, "DONE", "avg · in range")
        y += m.headerH
        for (rec in done) {
            if (visible(m.rowH)) doneRow(canvas, m, left, right, y, rec)
            y += m.rowH + m.gap
        }
    }

    /** "NEXT ▾" ("DONE ▾" once finished) in the strip under the first screen's fields: there is more below. */
    fun peek(canvas: Canvas, width: Int, height: Int, title: String = "NEXT") {
        val m = Metrics(width, height)
        val size = m.pad * 0.8f
        label.textSize = size
        label.textAlign = Paint.Align.CENTER
        label.color = WorkoutColors.LILAC_DIM
        val chevronW = size * 0.7f
        val textW = label.measureText(title)
        val cx = width / 2f - (chevronW + size * 0.35f) / 2
        val baseline = height - m.pad / 2 + capHeight(size) / 2
        canvas.drawText(title, cx, baseline, label)
        val x0 = cx + textW / 2 + size * 0.35f
        val cy = baseline - capHeight(size) / 2
        glyph.color = WorkoutColors.LILAC_DIM
        glyph.strokeWidth = size * 0.16f
        glyph.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x0, cy - chevronW * 0.25f, x0 + chevronW / 2, cy + chevronW * 0.25f, glyph)
        canvas.drawLine(x0 + chevronW / 2, cy + chevronW * 0.25f, x0 + chevronW, cy - chevronW * 0.25f, glyph)
        glyph.strokeCap = Paint.Cap.BUTT
        glyph.color = Color.WHITE
        label.color = WorkoutColors.LILAC
        label.textAlign = Paint.Align.LEFT
    }

    private fun header(canvas: Canvas, m: Metrics, left: Float, right: Float, top: Float, title: String, hint: String) {
        val baseline = top + m.headerH * 0.72f
        label.textSize = m.headerSize
        label.textAlign = Paint.Align.LEFT
        label.color = WorkoutColors.LILAC
        canvas.drawText(title, left, baseline, label)
        label.textAlign = Paint.Align.RIGHT
        label.color = WorkoutColors.LILAC_DIM
        canvas.drawText(hint, right, baseline, label)
        label.color = WorkoutColors.LILAC
        label.textAlign = Paint.Align.LEFT
    }

    /** The row's card; returns the text baseline. */
    private fun card(canvas: Canvas, m: Metrics, left: Float, right: Float, top: Float): Float {
        tmp.set(left, top, right, top + m.rowH)
        fill.color = WorkoutColors.TRACK
        canvas.drawRoundRect(tmp, m.rowH * 0.12f, m.rowH * 0.12f, fill)
        return top + m.rowH / 2 + capHeight(m.textSize) / 2
    }

    /** n · zone dot · target · length · starts in. */
    private fun nextRow(
        canvas: Canvas,
        m: Metrics,
        left: Float,
        right: Float,
        top: Float,
        row: UpcomingRow,
        profile: RiderProfile,
    ) {
        val baseline = card(canvas, m, left, right, top)
        val inset = m.rowH * 0.2f
        val l = left + inset
        val w = right - inset - l
        cell(canvas, "${row.index + 1}", l, baseline, w * 0.09f, m.textSize, label, WorkoutColors.LILAC, Paint.Align.LEFT)

        val t = row.target
        val level = t?.value ?: t?.min
        fill.color = if (t == null || level == null) WorkoutColors.ZONE_UNKNOWN else WorkoutColors.zone(profile.zoneOf(t.kind, level))
        canvas.drawCircle(l + w * 0.125f, top + m.rowH / 2, m.rowH * 0.12f, fill)

        cell(canvas, Format.plannedTarget(t), l + w * 0.17f, baseline, w * 0.39f, m.textSize, text, Color.WHITE, Paint.Align.LEFT)
        val length = row.lengthMs?.let { Format.countdown(it) } ?: "open"
        cell(canvas, length, l + w * 0.71f, baseline, w * 0.14f, m.textSize, label, WorkoutColors.LILAC, Paint.Align.RIGHT)
        cell(canvas, Format.startsIn(row.startsInMs), l + w, baseline, w * 0.27f, m.textSize, text, Color.WHITE, Paint.Align.RIGHT)
    }

    /** n · length · average · % in range (or "skipped"). */
    private fun doneRow(canvas: Canvas, m: Metrics, left: Float, right: Float, top: Float, rec: IntervalRecord) {
        val baseline = card(canvas, m, left, right, top)
        val inset = m.rowH * 0.2f
        val l = left + inset
        val w = right - inset - l
        cell(canvas, "${rec.index + 1}", l, baseline, w * 0.09f, m.textSize, label, WorkoutColors.LILAC, Paint.Align.LEFT)
        cell(canvas, Format.elapsed(rec.actualMs), l + w * 0.17f, baseline, w * 0.25f, m.textSize, label, WorkoutColors.LILAC, Paint.Align.LEFT)
        val avg = rec.avgOutput?.let { "${it.roundToInt()}${unitOf(rec.kind)}" } ?: "—"
        cell(canvas, avg, l + w * 0.68f, baseline, w * 0.26f, m.textSize, text, Color.WHITE, Paint.Align.RIGHT)
        val pct = rec.inRangePercent
        if (!rec.endedNaturally) {
            cell(canvas, "skipped", l + w, baseline, w * 0.28f, m.textSize, label, WorkoutColors.LILAC_DIM, Paint.Align.RIGHT)
        } else {
            cell(canvas, pct?.let { "$it%" } ?: "—", l + w, baseline, w * 0.28f, m.textSize, text, WorkoutColors.inRange(pct), Paint.Align.RIGHT)
        }
    }

    /** A one-line row: no plan known, or nothing after this interval. */
    private fun note(canvas: Canvas, m: Metrics, left: Float, right: Float, top: Float, note: String) {
        val baseline = card(canvas, m, left, right, top)
        val inset = m.rowH * 0.2f
        cell(canvas, note, left + inset, baseline, right - left - 2 * inset, m.textSize, text, Color.WHITE, Paint.Align.LEFT)
    }

    private fun cell(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        maxW: Float,
        size: Float,
        paint: Paint,
        color: Int,
        align: Paint.Align,
    ) {
        paint.textSize = size
        while (paint.measureText(value) > maxW && paint.textSize > 8f) paint.textSize *= 0.94f
        paint.textAlign = align
        paint.color = color
        canvas.drawText(value, x, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.color = if (paint === label) WorkoutColors.LILAC else Color.WHITE
    }

    private fun unitOf(kind: TargetKind?): String = when (kind) {
        TargetKind.POWER -> " W"
        TargetKind.HEART_RATE -> " bpm"
        TargetKind.CADENCE -> " rpm"
        else -> ""
    }

    /** Visible digit height (cap height) of the overlay font at [size]. */
    private fun capHeight(size: Float): Float = size * 0.72f
}
