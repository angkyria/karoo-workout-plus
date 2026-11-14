package com.angkyria.karooworkout.overlay

import com.angkyria.karooworkout.data.LibraryTarget
import com.angkyria.karooworkout.data.Target
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.settings.FieldFormat
import io.hammerhead.karooext.models.DataType
import kotlin.math.abs
import kotlin.math.roundToInt

/** Text formatting shared by the overlay and its tests. Overlay text is unitless (native parity). */
object Format {

    /** Countdown: whole seconds rounded up, so the last partial second still reads 0:01. */
    fun countdown(ms: Long): String = clock((ms.coerceAtLeast(0) + 999) / 1000)

    /** Elapsed time: whole seconds rounded down. */
    fun elapsed(ms: Long): String = clock(ms.coerceAtLeast(0) / 1000)

    private fun clock(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "100-125" — the target range as the native drawer shows it. */
    fun range(t: Target): String {
        val lo = t.min.roundToInt()
        val hi = t.max.roundToInt()
        return if (lo == hi) "$lo" else "$lo-$hi"
    }

    fun output(t: Target): String = t.output?.roundToInt()?.toString() ?: "--"

    /**
     * A system stream value (SI units, times in ms) for a page-field cell; [valueField]
     * names the field when the stream carries more than its value.
     */
    fun field(format: FieldFormat, values: Map<String, Double>?, imperial: Boolean, valueField: String? = null): String {
        val raw = values?.let {
            if (valueField != null) it[valueField] else it[DataType.Field.SINGLE] ?: it.values.firstOrNull()
        } ?: return "--"
        return when (format) {
            FieldFormat.INTEGER -> "${raw.roundToInt()}"
            FieldFormat.NUMBER -> "%.1f".format(raw)
            FieldFormat.NUMBER2 -> "%.2f".format(raw)
            FieldFormat.SPEED -> "%.1f".format(raw * if (imperial) 2.23694 else 3.6)
            FieldFormat.DISTANCE -> "%.1f".format(raw / if (imperial) 1609.344 else 1000.0)
            FieldFormat.DURATION -> elapsed(abs(raw).toLong())
            FieldFormat.TEMPERATURE -> temperature(raw, imperial)
            FieldFormat.DERIVED -> "--"
        }
    }

    /** Body temperature: °C with one decimal, °F for imperial. */
    fun temperature(celsius: Double?, imperial: Boolean): String =
        celsius?.let { "%.1f".format(if (imperial) it * 1.8 + 32 else it) } ?: "--"

    // ------------------------------------------------------------ interval list

    /** When an upcoming interval starts: "in 12:30", or "after lap" behind an open step. */
    fun startsIn(ms: Long?): String = ms?.let { "in ${countdown(it)}" } ?: "after lap"

    /** A planned target with its unit: "220–232 W", a ramp "137→187 W", "140–150 bpm"; "—" without one. */
    fun plannedTarget(t: LibraryTarget?): String {
        t ?: return "—"
        val unit = when (t.kind) {
            TargetKind.POWER -> " W"
            TargetKind.HEART_RATE -> " bpm"
            TargetKind.CADENCE -> " rpm"
            else -> ""
        }
        val lo = t.min?.roundToInt()
        val hi = t.max?.roundToInt()
        val text = when {
            lo != null && hi != null && lo != hi -> if (t.ramp) "$lo→$hi" else "$lo–$hi"
            t.value != null -> "${t.value.roundToInt()}"
            else -> lo?.toString() ?: hi?.toString() ?: return "—"
        }
        return text + unit
    }

    /** The NEXT row without a plan: "+8 intervals · 2:24:40". */
    fun unknownNext(count: Int, remainingMs: Long?): String {
        val intervals = if (count == 1) "+1 interval" else "+$count intervals"
        return remainingMs?.let { "$intervals · ${countdown(it)}" } ?: intervals
    }
}
