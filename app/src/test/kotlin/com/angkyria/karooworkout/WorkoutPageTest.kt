package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.WorkoutPage
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPageTest {

    private val marker = DataType.dataTypeId("karoo-workout", "workout-page")

    @Test
    fun markerFieldMakesAnyPageTheWorkoutPage() {
        assertTrue(WorkoutPage.isWorkoutPage(listOf(marker), marker))
        assertTrue(
            WorkoutPage.isWorkoutPage(
                listOf(DataType.Type.SPEED, DataType.Type.HEART_RATE, DataType.Type.POWER, marker),
                marker,
            ),
        )
    }

    @Test
    fun pageMadeOfWorkoutFieldsIsTheWorkoutPage() {
        val nativeLike = listOf(
            DataType.Type.WORKOUT_PRIMARY_TARGET,
            DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE,
            DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
            DataType.Type.SMOOTHED_3S_AVERAGE_POWER,
        )
        assertTrue(WorkoutPage.isWorkoutPage(nativeLike, marker))
    }

    @Test
    fun oneIntervalTimerOnAnOrdinaryPageIsNotTakenOver() {
        val ordinary = listOf(
            DataType.Type.SPEED,
            DataType.Type.HEART_RATE,
            DataType.Type.POWER,
            DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
        )
        assertFalse(WorkoutPage.isWorkoutPage(ordinary, marker))
        assertFalse(WorkoutPage.isWorkoutPage(emptyList(), marker))
    }
}
