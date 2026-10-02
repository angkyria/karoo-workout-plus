package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.WorkoutStreams
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutStreamsTest {

    private val raw = mapOf(
        WorkoutStreams.GATE to mapOf(
            DataType.Field.WORKOUT_CURRENT_STEP to 2.0,
            DataType.Field.WORKOUT_STEP_COUNT to 9.0,
            DataType.Field.WORKOUT_STATE to 1.0,
        ),
        DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION to mapOf(
            DataType.Field.WORKOUT_TIME_TO_STEP_FINISH to 83_000.0,
        ),
        DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION to mapOf(
            DataType.Field.WORKOUT_REMAINING_TIME to 1_200_000.0,
        ),
        DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE to mapOf(
            DataType.Field.WORKOUT_TARGET_TYPE to 4.0,
            DataType.Field.WORKOUT_TARGET_VALUE to 112.5,
            DataType.Field.WORKOUT_TARGET_MIN_VALUE to 100.0,
            DataType.Field.WORKOUT_TARGET_MAX_VALUE to 125.0,
            DataType.Field.WORKOUT_TARGET_OUTPUT_VALUE to 104.0,
            DataType.Field.WORKOUT_TARGET_OUTPUT_VALUE_SMOOTHED to 108.0,
        ),
        DataType.Type.WORKOUT_POWER_TARGET to mapOf(
            DataType.Field.WORKOUT_TARGET_VALUE to 112.5,
            DataType.Field.WORKOUT_DIFFICULTY to 1.05,
        ),
    )

    @Test
    fun foldsTheStreamsIntoOneSnapshot() {
        val s = WorkoutStreams.snapshot(raw)
        assertTrue(s.loaded)
        assertEquals(2, s.stepIndex)
        assertEquals(9, s.stepCount)
        assertEquals(1, s.state)
        assertEquals(83_000L, s.stepRemainingMs)
        assertEquals(1_200_000L, s.totalRemainingMs)
        assertEquals(4, s.primary!!.type)
        assertEquals(100.0, s.primary!!.min!!, 1e-9)
        assertEquals(108.0, s.primary!!.outputSmoothed!!, 1e-9)
        assertNull(s.secondary)
        assertEquals(105, s.scalePercent)
        assertEquals(mapOf(TargetKind.POWER to 112.5), s.kindValues)
    }

    @Test
    fun targetStillShowsWhileTheSensorStreamIsSearching() {
        // Karoo 2: the target+output stream waits for the power meter; the plain one doesn't
        val noSensor = raw - DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE + (
            DataType.Type.WORKOUT_PRIMARY_TARGET to mapOf(
                DataType.Field.WORKOUT_TARGET_TYPE to 4.0,
                DataType.Field.WORKOUT_TARGET_VALUE to 137.0,
                DataType.Field.WORKOUT_TARGET_MIN_VALUE to 130.0,
                DataType.Field.WORKOUT_TARGET_MAX_VALUE to 144.0,
            )
            )
        val primary = WorkoutStreams.snapshot(noSensor).primary!!
        assertEquals(137.0, primary.value!!, 1e-9)
        assertEquals(130.0, primary.min!!, 1e-9)
        assertNull(primary.output)
    }

    @Test
    fun bothPrimaryStreamsSilentFallsBackToThePowerTargetStream() {
        // seen on a Karoo 2 with the power meter off: only the per-kind streams carry the target
        val silent = raw - DataType.Type.WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE + (
            DataType.Type.WORKOUT_POWER_TARGET to mapOf(
                DataType.Field.WORKOUT_TARGET_VALUE to 137.0,
                DataType.Field.WORKOUT_TARGET_MIN_VALUE to 137.0,
                DataType.Field.WORKOUT_TARGET_MAX_VALUE to 187.0,
                DataType.Field.WORKOUT_DIFFICULTY to 1.0,
            )
            )
        val s = WorkoutStreams.snapshot(silent)
        val primary = s.primary!!
        assertEquals(137.0, primary.value!!, 1e-9)
        assertEquals(187.0, primary.max!!, 1e-9)
        assertNull(primary.output)
        assertEquals(mapOf(TargetKind.POWER to 137.0), s.kindValues)
    }

    @Test
    fun outputComesFromTheOutputStreamWhenBothStream() {
        val both = raw + (
            DataType.Type.WORKOUT_PRIMARY_TARGET to mapOf(
                DataType.Field.WORKOUT_TARGET_VALUE to 112.5,
                DataType.Field.WORKOUT_TARGET_MIN_VALUE to 100.0,
                DataType.Field.WORKOUT_TARGET_MAX_VALUE to 125.0,
            )
            )
        val primary = WorkoutStreams.snapshot(both).primary!!
        assertEquals(104.0, primary.output!!, 1e-9)
        assertEquals(4, primary.type) // only the output stream carried the type
    }

    @Test
    fun noGateMeansNoWorkout() {
        assertFalse(WorkoutStreams.snapshot(emptyMap()).loaded)
        val zero = mapOf(WorkoutStreams.GATE to mapOf(DataType.Field.WORKOUT_STEP_COUNT to 0.0))
        assertFalse(WorkoutStreams.snapshot(zero).loaded)
    }

    @Test
    fun negativeStepCountdownIsAnOpenStep() {
        val open = raw + (
            DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION to
                mapOf(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH to -1.0)
            )
        assertNull(WorkoutStreams.snapshot(open).stepRemainingMs)
    }

    @Test
    fun workoutTotalInSecondsIsNormalizedToMilliseconds() {
        val seconds = raw + (
            DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION to
                mapOf(DataType.Field.WORKOUT_REMAINING_TIME to 1_200.0)
            )
        assertEquals(1_200_000L, WorkoutStreams.snapshot(seconds).totalRemainingMs)
    }

    @Test
    fun scaleArrivesAsFactorOrPercent() {
        assertEquals(105, WorkoutStreams.scalePercent(1.05))
        assertEquals(95, WorkoutStreams.scalePercent(95.0))
        assertNull(WorkoutStreams.scalePercent(0.0))
    }
}
