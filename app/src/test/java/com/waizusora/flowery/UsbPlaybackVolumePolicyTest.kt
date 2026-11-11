package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbPlaybackVolumePolicyTest {
    @Test
    fun confirmsMinimumHardwareVolumeAfterAcceptedSet() {
        assertEquals(
            0.01f,
            UsbHardwareVolumeReadback.confirmedFraction(true, 0.01f),
            0.0001f,
        )
    }

    @Test
    fun rejectsHardwareVolumeWhenSetWasNotAccepted() {
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(false, 0f))
    }

    @Test
    fun rejectsMissingOrNonFiniteHardwareReadback() {
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, null))
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, Float.NaN))
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, Float.POSITIVE_INFINITY))
    }

    @Test
    fun rejectsReadbackAboveSafeStartupWindow() {
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, 0.0201f))
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, 0.5f))
    }

    @Test
    fun clampsSmallNegativeReadbackToMinimum() {
        assertEquals(
            0f,
            UsbHardwareVolumeReadback.confirmedFraction(true, -0.01f),
            0.0001f,
        )
    }
}
