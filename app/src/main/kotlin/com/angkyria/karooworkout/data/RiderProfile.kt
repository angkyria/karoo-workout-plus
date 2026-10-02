package com.angkyria.karooworkout.data

/** The bits of the Karoo rider profile the workout page needs (zones color the interval graph). */
data class RiderProfile(
    val ftp: Int? = null,
    val maxHr: Int? = null,
    /** Inclusive watt ranges, Z1 first, as configured on the Karoo. */
    val powerZones: List<IntRange> = emptyList(),
    val hrZones: List<IntRange> = emptyList(),
    val imperial: Boolean = false,
) {
    /**
     * 0-based zone of a target [level], or null when unknown. Falls back to the
     * Coggan %FTP bands when the profile carries no power zones.
     */
    fun zoneOf(kind: TargetKind?, level: Double): Int? = when (kind) {
        TargetKind.POWER -> zoneIn(powerZones, level) ?: ftp?.takeIf { it > 0 }?.let { ftp ->
            val pct = level / ftp * 100
            COGGAN_UPPER_PCT.indexOfFirst { pct <= it }.takeIf { it >= 0 } ?: COGGAN_UPPER_PCT.size
        }
        TargetKind.HEART_RATE -> zoneIn(hrZones, level)
        else -> null
    }

    private fun zoneIn(zones: List<IntRange>, level: Double): Int? {
        if (zones.isEmpty()) return null
        val v = level.toInt()
        zones.forEachIndexed { i, z -> if (v <= z.last) return i }
        return zones.size - 1
    }

    companion object {
        /** Upper %FTP bound of Z1..Z6; anything above is Z7. */
        private val COGGAN_UPPER_PCT = listOf(55.0, 75.0, 90.0, 105.0, 120.0, 150.0)
    }
}
