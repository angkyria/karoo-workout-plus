package com.angkyria.karooworkout.data

/**
 * Recognizes the ride app's workout page from the data fields on it
 * (karoo-ext `ActiveRidePage` reports the visible page's elements).
 */
object WorkoutPage {

    private const val WORKOUT_TYPE_PREFIX = "TYPE_WORKOUT_"

    /**
     * A page is the workout page when it carries the Workout+ field ([markerId]), or
     * when workout fields make up at least half of it — a single "interval time"
     * field on an ordinary page must not get that page covered.
     */
    fun isWorkoutPage(elementTypeIds: List<String>, markerId: String): Boolean {
        if (markerId in elementTypeIds) return true
        val workoutFields = elementTypeIds.count { it.startsWith(WORKOUT_TYPE_PREFIX) }
        return workoutFields > 0 && workoutFields * 2 >= elementTypeIds.size
    }
}
