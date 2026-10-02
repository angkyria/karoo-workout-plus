package com.angkyria.karooworkout.overlay

import com.angkyria.karooworkout.data.TargetStatus

/** Karoo OS workout palette, sampled from the native Karoo workout drawer. */
object WorkoutColors {
    /** Workout drawer purple. */
    const val PANEL = 0xFF671890.toInt()

    /** Bar tracks, cards and the graph background. */
    const val TRACK = 0xFF45145D.toInt()

    /** Target band inside a track. */
    const val BAND = 0xFF7A3AA0.toInt()

    /** Progress fill and secondary text. */
    const val LILAC = 0xFFEFC7FF.toInt()
    const val LILAC_DIM = 0xFFC9A3DC.toInt()

    /** Round pause / resume button. */
    const val BUTTON = 0xFF401058.toInt()

    /** Workout glyph green. */
    const val ACCENT = 0xFF24C78A.toInt()

    const val IN_RANGE = 0xFF4BDDA3.toInt()
    const val UNDER = 0xFF3D9BF0.toInt()
    const val OVER = 0xFFEE4D5A.toInt()
    const val NO_DATA = 0xFF8C6A9E.toInt()

    /** Countdown color for the last seconds of an interval. */
    const val ENDING = 0xFFFFC94A.toInt()

    const val TEXT_DARK = 0xFF1A0526.toInt()

    /** Karoo workout-graph zone colors, Z1..Z7 (HR zones use the first five). */
    val ZONES = intArrayOf(
        0xFF13795B.toInt(),
        0xFF5DE8B0.toInt(),
        0xFFF8F000.toInt(),
        0xFFF89068.toInt(),
        0xFFEE4D5A.toInt(),
        0xFFD0021B.toInt(),
        0xFF9B1B6E.toInt(),
    )

    /** Graph bar color when the zone is unknown (no FTP / HR zones in the profile). */
    const val ZONE_UNKNOWN = 0xFFB98AD4.toInt()

    fun status(status: TargetStatus): Int = when (status) {
        TargetStatus.IN_RANGE -> IN_RANGE
        TargetStatus.UNDER -> UNDER
        TargetStatus.OVER -> OVER
        TargetStatus.NO_DATA -> NO_DATA
    }

    /** Text drawn on top of a [status] fill. */
    fun onStatus(status: TargetStatus): Int =
        if (status == TargetStatus.IN_RANGE) TEXT_DARK else 0xFFFFFFFF.toInt()

    fun zone(index: Int?): Int =
        if (index == null) ZONE_UNKNOWN else ZONES[index.coerceIn(0, ZONES.size - 1)]
}
