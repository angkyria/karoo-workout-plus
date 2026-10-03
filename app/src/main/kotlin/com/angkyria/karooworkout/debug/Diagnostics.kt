package com.angkyria.karooworkout.debug

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live view of what Karoo actually streams, shown in the settings screen. The
 * workout fields' units and enum codes are undocumented in karoo-ext, so this is
 * how a rider (or developer) checks them on a real workout. Same process as the
 * extension service, so a plain singleton is enough.
 */
object Diagnostics {
    /** Raw workout stream fields, plus the sensor streams behind the output (data type id -> field id -> value). */
    val workoutStreams = MutableStateFlow<Map<String, Map<String, Double>>>(emptyMap())

    /** Data type ids on the ride page currently on screen, and whether it counts as the workout page. */
    val page = MutableStateFlow<Pair<List<String>, Boolean>?>(null)

    val hardware = MutableStateFlow<String?>(null)
}
