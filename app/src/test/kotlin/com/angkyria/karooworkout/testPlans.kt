package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryEntry
import com.angkyria.karooworkout.data.LibraryTarget
import com.angkyria.karooworkout.data.LibraryWorkout
import com.angkyria.karooworkout.data.TargetKind

/** A fixed "today" (epoch ms) for plan fixtures; the exact value is unimportant. */
const val TODAY: Long = 1_794_607_200_000L

private fun power(min: Double, max: Double) =
    LibraryTarget(TargetKind.POWER, value = null, min = min, max = max, ramp = false)

/** The verified ride of 2026-11-14: nine intervals, 2:45:00 planned. */
fun todayWorkout(
    id: String = "today",
    plannedDateMs: Long? = TODAY,
    updatedAtMs: Long? = TODAY,
): LibraryWorkout = LibraryWorkout(
    id = id,
    name = "Today",
    plannedDateMs = plannedDateMs,
    updatedAtMs = updatedAtMs,
    structure = listOf(
        LibraryEntry.Step("Warm up", 1_200_000L, power(151.0, 159.0)),
        LibraryEntry.Repeat(
            3,
            listOf(
                LibraryEntry.Step("Climb", 900_000L, power(220.0, 232.0)),
                LibraryEntry.Step("Easy", 300_000L, power(146.0, 154.0)),
            ),
        ),
        LibraryEntry.Step("Endurance", 3_900_000L, power(150.0, 175.0)),
        LibraryEntry.Step("Skills", 1_200_000L, power(133.0, 141.0)),
    ),
)
