package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.LibraryMapper
import com.angkyria.karooworkout.data.LibraryTarget
import com.angkyria.karooworkout.data.LibraryEntry
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.expand
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeTarget(
    val type: String,
    val value: Double?,
    val minValue: Double?,
    val maxValue: Double?,
    val rampType: String?,
)

open class FakeBaseStep(val name: String?)

class FakeStep(
    val _class: String,
    val length: Double,
    val lengthOpen: Boolean,
    val lengthType: String,
    name: String?,
    val primaryTarget: FakeTarget?,
) : FakeBaseStep(name)

class FakeRepeat(val repeat: Int, val steps: List<FakeStep>)

class FakeWorkout(
    val id: String,
    val name: String,
    val plannedDate: Date?,
    val updatedAt: Date?,
    val structure: List<Any>,
)

class FakeNoStructure(val id: String, val name: String)

class LibraryMapperTest {
    private fun power(v: Double, lo: Double, hi: Double) = FakeTarget("POWER", v, lo, hi, null)

    private fun step(
        name: String,
        seconds: Double,
        target: FakeTarget? = null,
        open: Boolean = false,
        lengthType: String = "SECONDS",
    ) = FakeStep("ACTIVE", seconds, open, lengthType, name, target)

    private fun todays() = FakeWorkout(
        "67685.workout.x", "Today", Date(1000), Date(2000),
        listOf(
            step("Warm up", 1200.0, power(155.0, 151.0, 159.0)),
            FakeRepeat(
                3,
                listOf(
                    step("Climb", 900.0, power(226.0, 220.0, 232.0)),
                    step("Easy", 300.0, power(150.0, 146.0, 154.0)),
                ),
            ),
            step("Endurance", 3900.0, power(162.0, 150.0, 175.0)),
            step("Skills", 1200.0, power(137.0, 133.0, 141.0)),
        ),
    )

    @Test
    fun `today's workout maps and expands to nine`() {
        val w = LibraryMapper.workout(todays())!!
        val plan = w.expand()
        assertEquals(9, plan.size)
        assertEquals(LibraryTarget(TargetKind.POWER, 155.0, 151.0, 159.0, ramp = false), plan[0].target)
        assertEquals(1_200_000L, plan[0].lengthMs)
        assertEquals(1000L, w.plannedDateMs)
        assertEquals(2000L, w.updatedAtMs)
        assertEquals("Warm up", plan[0].name)
    }

    @Test
    fun `open and distance steps have no length`() {
        val open = FakeWorkout("i", "n", null, null, listOf(step("a", 0.0, open = true)))
        val dist = FakeWorkout("i", "n", null, null, listOf(step("a", 500.0, lengthType = "DISTANCE")))
        assertNull((LibraryMapper.workout(open)!!.structure[0] as LibraryEntry.Step).lengthMs)
        assertNull((LibraryMapper.workout(dist)!!.structure[0] as LibraryEntry.Step).lengthMs)
    }

    @Test
    fun `heart rate target maps its kind`() {
        val w = FakeWorkout("i", "n", null, null, listOf(step("a", 60.0, FakeTarget("HEART_RATE", 140.0, null, null, null))))
        assertEquals(TargetKind.HEART_RATE, (w.let { LibraryMapper.workout(it)!! }.structure[0] as LibraryEntry.Step).primary!!.kind)
    }

    @Test
    fun `unknown target type and a ramp`() {
        val w = FakeWorkout("i", "n", null, null, listOf(step("a", 60.0, FakeTarget("SPEED", 1.0, null, null, "LINEAR"))))
        val t = (LibraryMapper.workout(w)!!.structure[0] as LibraryEntry.Step).primary!!
        assertEquals(TargetKind.UNKNOWN, t.kind)
        assertEquals(true, t.ramp)
    }

    @Test
    fun `negative repeat is clamped`() {
        val w = FakeWorkout("i", "n", null, null, listOf(FakeRepeat(-2, listOf(step("a", 60.0)))))
        assertEquals(0, LibraryMapper.workout(w)!!.expand().size)
    }

    @Test
    fun `missing field maps to null`() {
        assertNull(LibraryMapper.workout(FakeNoStructure("i", "n")))
    }
}
