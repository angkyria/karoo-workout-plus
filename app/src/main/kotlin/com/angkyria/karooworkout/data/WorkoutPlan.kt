package com.angkyria.karooworkout.data

/**
 * A target as the Karoo workout library stores it: single [value] or a [min]..[max]
 * range, [ramp] when the step ramps between its ends.
 */
data class LibraryTarget(
    val kind: TargetKind,
    val value: Double?,
    val min: Double?,
    val max: Double?,
    val ramp: Boolean,
)

/** One entry of a library workout's structure: a step, or a repeated group of steps. */
sealed interface LibraryEntry {
    /** [lengthMs] is null for an open step or any length that is not a time in seconds. */
    data class Step(val name: String?, val lengthMs: Long?, val primary: LibraryTarget?) : LibraryEntry

    data class Repeat(val times: Int, val steps: List<Step>) : LibraryEntry
}

/** A workout from the library; [plannedDateMs] / [updatedAtMs] pick today's among several. */
data class LibraryWorkout(
    val id: String,
    val name: String,
    val plannedDateMs: Long?,
    val updatedAtMs: Long?,
    val structure: List<LibraryEntry>,
)

/** One interval of the ride as the plan lists it; [index] is 0-based, repeats unrolled. */
data class PlannedInterval(
    val index: Int,
    val name: String?,
    val lengthMs: Long?,
    val target: LibraryTarget?,
)

/** The plan's intervals in ride order, repeats unrolled. */
fun LibraryWorkout.expand(): List<PlannedInterval> {
    val steps = structure.flatMap { entry ->
        when (entry) {
            is LibraryEntry.Step -> listOf(entry)
            is LibraryEntry.Repeat -> List(entry.times) { entry.steps }.flatten()
        }
    }
    return steps.mapIndexed { i, s -> PlannedInterval(i, s.name, s.lengthMs, s.primary) }
}

/** Planned time of the whole workout; null when any interval has no fixed length. */
val List<PlannedInterval>.totalMs: Long?
    get() = if (any { it.lengthMs == null }) null else sumOf { it.lengthMs!! }
