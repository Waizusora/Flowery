package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbFeatureUnitParserTest {
    @Test
    fun detectsUac1MasterVolumeControl() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 0x24, 0x06, 5, 1, 1, 2, 2, 0
        )
        assertEquals(UsbFeatureUnit(0, 5, 0), UsbFeatureUnitParser.find(descriptors))
    }

    @Test
    fun detectsUac2MasterVolumeControl() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            15, 0x24, 0x06, 7, 1,
            2, 0, 0, 0,
            2, 0, 0, 0,
            0
        )
        assertEquals(UsbFeatureUnit(0, 7, 0x20), UsbFeatureUnitParser.find(descriptors))
    }

    @Test
    fun ignoresFeatureUnitsWithoutMasterVolumeControl() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 0x24, 0x06, 5, 1, 1, 1, 1, 0
        )
        assertNull(UsbFeatureUnitParser.find(descriptors))
    }

    @Test
    fun rejectsTruncatedFeatureUnitDescriptors() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            7, 0x24, 0x06, 5, 1, 1, 2
        )
        assertNull(UsbFeatureUnitParser.find(descriptors))
    }
}
