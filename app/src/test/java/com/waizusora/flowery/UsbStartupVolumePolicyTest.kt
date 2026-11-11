package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStartupVolumePolicyTest {
    @Test
    fun choosesMinusFortyDbWithinAdvertisedRange() {
        assertEquals(-10_240, UsbStartupVolumePolicy.targetRaw(-24_576, 0))
    }

    @Test
    fun clampsToMoreAttenuatedMaximumWhenDeviceRangeIsBelowTarget() {
        assertEquals(-12_000, UsbStartupVolumePolicy.targetRaw(-24_576, -12_000))
    }

    @Test
    fun rejectsHardwareRangeThatCannotReachSafeAttenuation() {
        assertNull(UsbStartupVolumePolicy.targetRaw(-7_680, 0))
    }

    @Test
    fun rejectsInvalidVolumeRange() {
        assertNull(UsbStartupVolumePolicy.targetRaw(-10_000, -10_000))
        assertNull(UsbStartupVolumePolicy.targetRaw(0, -10_000))
    }

    @Test
    fun hardwareStartupRequiresAcceptedSetAndReadbackNoLouderThanRequest() {
        assertTrue(UsbStartupVolumePolicy.confirmsHardwareStartup(true, -10_240, -10_240))
        assertTrue(UsbStartupVolumePolicy.confirmsHardwareStartup(true, -10_240, -12_000))
        assertFalse(UsbStartupVolumePolicy.confirmsHardwareStartup(false, -10_240, -10_240))
        assertFalse(UsbStartupVolumePolicy.confirmsHardwareStartup(true, -10_240, -9_000))
        assertFalse(UsbStartupVolumePolicy.confirmsHardwareStartup(true, null, -10_240))
        assertFalse(UsbStartupVolumePolicy.confirmsHardwareStartup(true, -10_240, null))
    }

    @Test
    fun softwareStartupGainIsMinusFortyDb() {
        assertEquals(0.01f, UsbStartupVolumePolicy.SAFE_SOFTWARE_GAIN, 0.000001f)
    }
}
