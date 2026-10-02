package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.CoreHeat
import com.angkyria.karooworkout.overlay.Format
import com.angkyria.karooworkout.settings.FieldFormat
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreHeatTest {

    @Test
    fun zonesFollowCoreOnThePrintedTenth() {
        assertEquals(1, CoreHeat.zone(0.94)) // prints 0.9
        assertEquals(2, CoreHeat.zone(0.96)) // prints 1.0
        assertEquals(2, CoreHeat.zone(2.94))
        assertEquals(3, CoreHeat.zone(2.96)) // prints 3.0: zone 3, color included
        assertEquals(4, CoreHeat.zone(7.0))
        assertEquals(0xFFFFA06A.toInt(), CoreHeat.hsiColor(3.8))
        assertNull(CoreHeat.zoneColor(5))
    }

    @Test
    fun adaptationLevelsColorTheScore() {
        assertEquals(0xFFA2DBED.toInt(), CoreHeat.adaptationColor(10.0))
        assertEquals(0xFF73C9E4.toInt(), CoreHeat.adaptationColor(24.96)) // prints 25.0
        assertEquals(0xFF1C8DB5.toInt(), CoreHeat.adaptationColor(95.0))
    }

    @Test
    fun readingTakesTheNamedTemperatureNotTheQualityFlag() {
        val values = mapOf(
            DataType.Type.CORE_TEMP to mapOf(
                "FIELD_CORE_DATA_QUALITY_ID" to 3.0,
                DataType.Field.CORE_TEMP to 38.24,
            ),
            DataType.Type.SKIN_TEMP to mapOf(DataType.Field.SKIN_TEMP to 34.1),
            CoreHeat.HEAT_STRAIN to mapOf(DataType.Field.SINGLE to 3.4),
        )
        val r = CoreHeat.reading(values)
        assertEquals(38.24, r.core!!, 1e-9)
        assertTrue(r.present)
        assertEquals(0xFFFFA06A.toInt(), r.color)
        assertEquals("38.2", Format.field(FieldFormat.TEMPERATURE, values[DataType.Type.CORE_TEMP], false, DataType.Field.CORE_TEMP))
        assertEquals("100.8", Format.temperature(38.24, imperial = true))
    }

    @Test
    fun noSensorNoStrip() {
        assertFalse(CoreHeat.reading(emptyMap()).present)
        assertNull(CoreHeat.reading(emptyMap()).color)
    }
}
