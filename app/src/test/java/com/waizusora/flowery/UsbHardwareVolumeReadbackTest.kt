package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbHardwareVolumeReadbackTest {
    @Test
    fun acceptsOnlyConfirmedHardwareVolumeReadback() {
        assertEquals(0.01f, UsbHardwareVolumeReadback.confirmedFraction(true, 0.01f)!!, 0.0001f)
    }

    @Test
    fun rejectsSetRequestWhenDeviceDoesNotAcceptIt() {
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(false, 0.01f))
    }

    @Test
    fun rejectsWriteWithoutReadableGetCurrentResponse() {
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, null))
    }

    @Test
    fun clampsReadbackToNormalizedRangeWhenCallerAllowsFullRange() {
        assertEquals(
            1f,
            UsbHardwareVolumeReadback.confirmedFraction(
                true,
                1.5f,
                maximumSafeFraction = 1f,
            )!!,
            0f,
        )
        assertEquals(
            0f,
            UsbHardwareVolumeReadback.confirmedFraction(
                true,
                -0.5f,
                maximumSafeFraction = 1f,
            )!!,
            0f,
        )
    }

    @Test
    fun rejectsNonFiniteReadback() {
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, Float.NaN))
        assertNull(UsbHardwareVolumeReadback.confirmedFraction(true, Float.POSITIVE_INFINITY))
    }
}
