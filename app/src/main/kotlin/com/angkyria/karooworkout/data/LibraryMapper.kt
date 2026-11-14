package com.angkyria.karooworkout.data

import java.lang.reflect.Field
import java.util.Date
import kotlin.math.roundToLong

/**
 * Maps the Karoo library's own `Workout` objects (Hammerhead classes, never linked here)
 * to [LibraryWorkout] by reading fields by name. Pure reflection, no Android.
 * A workout with a missing required field or an entry it cannot read maps to null.
 */
object LibraryMapper {
    fun workout(o: Any): LibraryWorkout? = try {
        val id = field(o, "id")?.toString()
        val name = field(o, "name")?.toString()
        val list = field(o, "structure") as? List<*>
        if (id == null || name == null || list == null) {
            null
        } else {
            val entries = list.map { entry(it ?: return null) ?: return null }
            LibraryWorkout(id, name, ms(field(o, "plannedDate")), ms(field(o, "updatedAt")), entries)
        }
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun entry(o: Any): LibraryEntry? {
        val repeat = findField(o.javaClass, "repeat")
        if (repeat == null) return step(o)
        val times = (repeat.get(o) as? Number)?.toInt() ?: return null
        val steps = (field(o, "steps") as? List<*>) ?: return null
        val mapped = steps.map { s ->
            if (s == null || findField(s.javaClass, "repeat") != null) return null
            step(s)
        }
        return LibraryEntry.Repeat(times.coerceAtLeast(0), mapped)
    }

    private fun step(o: Any): LibraryEntry.Step {
        val open = field(o, "lengthOpen") as? Boolean ?: false
        val seconds = field(o, "lengthType")?.toString() == "SECONDS"
        val length = (field(o, "length") as? Number)?.toDouble()
        val lengthMs = if (open || !seconds || length == null) null else (length * 1000).roundToLong()
        return LibraryEntry.Step(field(o, "name")?.toString(), lengthMs, field(o, "primaryTarget")?.let(::target))
    }

    private fun target(o: Any): LibraryTarget {
        val kind = when (field(o, "type")?.toString()) {
            "POWER" -> TargetKind.POWER
            "HEART_RATE" -> TargetKind.HEART_RATE
            "CADENCE" -> TargetKind.CADENCE
            else -> TargetKind.UNKNOWN
        }
        return LibraryTarget(
            kind,
            number(field(o, "value")),
            number(field(o, "minValue")),
            number(field(o, "maxValue")),
            ramp = field(o, "rampType") != null,
        )
    }

    private fun number(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun ms(v: Any?): Long? = (v as? Date)?.time

    private fun field(o: Any, name: String): Any? = findField(o.javaClass, name)?.get(o)

    private fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        return null
    }
}
