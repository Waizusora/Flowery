package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbFeatureUnitVolumeRangeTest {
    @Test
    fun parsesSingleUac2RangeAsSignedValues() {
        val bytes = byteArrayOf(
            1, 0,
            0x00, 0xF0.toByte(), // -4096
            0x00, 0x00,         // 0
            0x01, 0x00          // resolution 1
        )
        assertEquals(-4096 to 0, UsbFeatureUnitVolumeRange.parseUac2(bytes, bytes.size))
    }

    @Test
    fun combinesAllUac2Subranges() {
        val bytes = byteArrayOf(
            2, 0,
            0x00, 0xF0.toByte(), 0x00, 0xF8.toByte(), 1, 0, // -4096..-2048
            0x00, 0xFC.toByte(), 0x00, 0x00, 1, 0          // -1024..0
        )
        assertEquals(-4096 to 0, UsbFeatureUnitVolumeRange.parseUac2(bytes, bytes.size))
    }

    @Test
    fun rejectsTruncatedUac2RangeResponse() {
        val bytes = byteArrayOf(2, 0, 0, 0, 1, 0, 1, 0)
        assertNull(UsbFeatureUnitVolumeRange.parseUac2(bytes, bytes.size))
    }

    @Test
    fun rejectsEmptyOrInvalidUac2RangeResponse() {
        assertNull(UsbFeatureUnitVolumeRange.parseUac2(byteArrayOf(0, 0), 2))
        assertNull(UsbFeatureUnitVolumeRange.parseUac2(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), 8))
    }

    @Test
    fun parsesUac1SignedMinimumAndMaximum() {
        val minimum = byteArrayOf(0x00, 0xF0.toByte())
        val maximum = byteArrayOf(0x00, 0x00)
        assertEquals(-4096 to 0, UsbFeatureUnitVolumeRange.parseUac1(minimum, maximum))
    }

    @Test
    fun rejectsInvalidUac1Range() {
        assertNull(UsbFeatureUnitVolumeRange.parseUac1(byteArrayOf(0, 0), byteArrayOf(0, 0)))
    }
}
