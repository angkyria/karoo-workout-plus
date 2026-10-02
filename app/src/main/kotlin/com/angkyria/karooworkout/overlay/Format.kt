package com.angkyria.karooworkout.overlay

import com.angkyria.karooworkout.data.Target
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

    /** A system stream value (SI units, times in ms) for a page-field cell. */
    fun field(format: FieldFormat, values: Map<String, Double>?, imperial: Boolean): String {
        val raw = values?.let { it[DataType.Field.SINGLE] ?: it.values.firstOrNull() } ?: return "--"
        return when (format) {
            FieldFormat.INTEGER -> "${raw.roundToInt()}"
            FieldFormat.NUMBER -> "%.1f".format(raw)
            FieldFormat.NUMBER2 -> "%.2f".format(raw)
            FieldFormat.SPEED -> "%.1f".format(raw * if (imperial) 2.23694 else 3.6)
            FieldFormat.DISTANCE -> "%.1f".format(raw / if (imperial) 1609.344 else 1000.0)
            FieldFormat.DURATION -> elapsed(abs(raw).toLong())
            FieldFormat.DERIVED -> "--"
        }
    }
}
