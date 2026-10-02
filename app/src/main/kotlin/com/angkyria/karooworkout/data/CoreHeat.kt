package com.angkyria.karooworkout.data

import io.hammerhead.karooext.models.DataType
import kotlin.math.roundToInt

/**
 * Heat data from a CORE body temperature sensor: core and skin temperature are the
 * Karoo's own streams; the Heat Strain Index, heat zone, training load and adaptation
 * come from the CORE Heat extension (github.com/angkyria/karoo-core), which streams
 * them under its own type ids. Zones and colors follow CORE Heat, i.e. CORE's own.
 */
object CoreHeat {

    private const val EXTENSION = "coreheat"

    val HEAT_STRAIN: String = DataType.dataTypeId(EXTENSION, "heatStrain")
    val HEAT_ZONE: String = DataType.dataTypeId(EXTENSION, "heatZone")
    val HEAT_LOAD: String = DataType.dataTypeId(EXTENSION, "heatLoad")
    val HEAT_ADAPTATION: String = DataType.dataTypeId(EXTENSION, "heatAdaptation")

    /** What the core-heat strip streams: the two temperatures and the index that colors them. */
    val STRIP_STREAMS: Set<String> = setOf(DataType.Type.CORE_TEMP, DataType.Type.SKIN_TEMP, HEAT_STRAIN)

    /**
     * Heat Zone 1-4 for [hsi]: 0-0.9, 1.0-2.9, 3.0-6.9 and 7.0 up — decided on the tenth
     * that is printed, so a 2.96 drawn as "3.0" is zone 3, color included.
     */
    fun zone(hsi: Double): Int {
        val shown = (hsi * 10).roundToInt() / 10.0
        return when {
            shown < 1.0 -> 1
            shown < 3.0 -> 2
            shown < 7.0 -> 3
            else -> 4
        }
    }

    // CORE's zone colors: no / moderate / high / extremely high heat strain
    private val zonePalette = intArrayOf(
        0xFF37CA94.toInt(),
        0xFFFFC655.toInt(),
        0xFFFFA06A.toInt(),
        0xFFF35264.toInt(),
    )

    fun zoneColor(zone: Int): Int? = zonePalette.getOrNull(zone - 1)

    fun hsiColor(hsi: Double): Int? = if (hsi.isFinite()) zoneColor(zone(hsi)) else null

    // CORE's adaptation levels (Thermal Rookie, Heat Accustomed, Heat Adapted, Heat Champion)
    private val levelFloors = doubleArrayOf(0.0, 25.0, 50.0, 90.0)
    private val levelPalette = intArrayOf(
        0xFFA2DBED.toInt(),
        0xFF73C9E4.toInt(),
        0xFF45B8DB.toInt(),
        0xFF1C8DB5.toInt(),
    )

    fun adaptationColor(score: Double): Int? {
        if (!score.isFinite()) return null
        val shown = (score * 10).roundToInt() / 10.0
        return levelPalette[levelFloors.indexOfLast { shown >= it }.coerceAtLeast(0)]
    }

    /** Core, skin and index as one reading, from the latest stream values. */
    data class Reading(val core: Double?, val skin: Double?, val hsi: Double?) {
        /** A CORE sensor is streaming. */
        val present: Boolean get() = core != null || skin != null

        /** Temperatures take the color of the current heat zone (CORE defines it on both). */
        val color: Int? get() = hsi?.let(::hsiColor)
    }

    fun reading(values: Map<String, Map<String, Double>>): Reading = Reading(
        // the temperature streams carry a data-quality flag too: read the named field
        core = values[DataType.Type.CORE_TEMP]?.get(DataType.Field.CORE_TEMP),
        skin = values[DataType.Type.SKIN_TEMP]?.get(DataType.Field.SKIN_TEMP),
        hsi = values[HEAT_STRAIN]?.get(DataType.Field.SINGLE),
    )
}
