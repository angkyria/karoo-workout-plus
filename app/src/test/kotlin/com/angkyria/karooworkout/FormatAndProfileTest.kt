package com.angkyria.karooworkout

import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.overlay.Format
import com.angkyria.karooworkout.settings.FieldFormat
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatAndProfileTest {

    @Test
    fun countdownRoundsUpElapsedRoundsDown() {
        assertEquals("19:53", Format.countdown(1_193_000))
        assertEquals("0:01", Format.countdown(400))
        assertEquals("0:00", Format.countdown(0))
        assertEquals("1:00:00", Format.countdown(3_600_000))
        assertEquals("0:00", Format.elapsed(999))
        assertEquals("1:05", Format.elapsed(65_400))
    }

    @Test
    fun fieldValuesFromSiUnits() {
        val single = { v: Double -> mapOf(DataType.Field.SINGLE to v) }
        assertEquals("212", Format.field(FieldFormat.INTEGER, single(211.6), imperial = false))
        assertEquals("36.0", Format.field(FieldFormat.SPEED, single(10.0), imperial = false))
        assertEquals("22.4", Format.field(FieldFormat.SPEED, single(10.0), imperial = true))
        assertEquals("1:24:30", Format.field(FieldFormat.DURATION, single(5_070_000.0), imperial = false))
        assertEquals("--", Format.field(FieldFormat.INTEGER, null, imperial = false))
    }

    @Test
    fun zonesFromTheProfileOrCogganBands() {
        val withZones = RiderProfile(powerZones = listOf(0..137, 138..187, 188..225, 226..262, 263..300, 301..375, 376..2000))
        assertEquals(0, withZones.zoneOf(TargetKind.POWER, 120.0))
        assertEquals(3, withZones.zoneOf(TargetKind.POWER, 250.0))
        assertEquals(6, withZones.zoneOf(TargetKind.POWER, 5000.0))

        val ftpOnly = RiderProfile(ftp = 250)
        assertEquals(1, ftpOnly.zoneOf(TargetKind.POWER, 175.0)) // 70 % -> Z2
        assertEquals(4, ftpOnly.zoneOf(TargetKind.POWER, 280.0)) // 112 % -> Z5
        assertEquals(6, ftpOnly.zoneOf(TargetKind.POWER, 400.0)) // 160 % -> Z7

        assertNull(RiderProfile().zoneOf(TargetKind.POWER, 200.0))
        assertNull(ftpOnly.zoneOf(TargetKind.CADENCE, 90.0))
    }
}
