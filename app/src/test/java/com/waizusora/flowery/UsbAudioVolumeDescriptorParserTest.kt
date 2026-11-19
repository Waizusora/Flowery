package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioVolumeDescriptorParserTest {
    @Test fun parsesUac1FeatureUnitMasterAndChannelVolume() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            10, 0x24, 0x06, 5, 4, 1, 0x02, 0x02, 0x02, 0,
            9, 0x24, 0x03, 10, 0x01, 0x03, 0, 5, 0
        )
        val control = UsbAudioVolumeDescriptorParser.parse(descriptors)
        assertNotNull(control)
        assertEquals(0, control!!.controlInterface)
        assertEquals(5, control.unitId)
        assertEquals(listOf(0, 1, 2), control.readableChannels)
        assertEquals(listOf(0, 1, 2), control.writableChannels)
    }

    @Test fun parsesUac2ReadOnlyAndWritableVolumeControls() {
        val descriptors = byteArrayOf(
            9, 4, 2, 0, 0, 1, 1, 0x20, 0,
            18, 0x24, 0x06, 7, 4,
            0x04, 0, 0, 0, // master: read-only
            0x0c, 0, 0, 0, // left: read/write
            0x04, 0, 0, 0, // right: read-only
            0,
            10, 0x24, 0x03, 10, 0x01, 0x03, 0, 7, 7, 0
        )
        val control = UsbAudioVolumeDescriptorParser.parse(descriptors)
        assertNotNull(control)
        assertEquals(0x20, control!!.protocol)
        assertEquals(listOf(0, 1, 2), control.readableChannels)
        assertEquals(listOf(1), control.writableChannels)
        assertTrue(control.supportsRead)
        assertTrue(control.supportsWrite)
    }

    @Test fun ignoresFeatureUnitWithoutVolumeControl() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            18, 0x24, 0x06, 7, 4,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0,
            10, 0x24, 0x03, 10, 0x01, 0x03, 0, 7, 7, 0
        )
        assertFalse(UsbAudioVolumeDescriptorParser.parse(descriptors)?.supportsRead ?: false)
    }

    @Test fun ignoresCaptureFeatureUnitConnectedToUsbStreamingTerminal() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            18, 0x24, 0x06, 5, 4,
            0x0c, 0, 0, 0,
            0x0c, 0, 0, 0,
            0x0c, 0, 0, 0,
            0,
            10, 0x24, 0x03, 10, 0x01, 0x01, 0, 5, 7, 0
        )
        assertEquals(null, UsbAudioVolumeDescriptorParser.parse(descriptors))
    }

    @Test fun rejectsMalformedFeatureUnitLength() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            8, 0x24, 0x06, 5, 4, 2, 0x02, 0x02,
            9, 0x24, 0x03, 10, 0x01, 0x03, 0, 5, 0
        )
        assertEquals(null, UsbAudioVolumeDescriptorParser.parse(descriptors))
    }

    @Test fun doesNotTreatReservedUac2VolumeEncodingAsReadableOrWritable() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            10, 0x24, 0x06, 5, 4, 0x08, 0, 0, 0, 0,
            10, 0x24, 0x03, 10, 0x01, 0x03, 0, 5, 7, 0
        )
        assertEquals(null, UsbAudioVolumeDescriptorParser.parse(descriptors))
    }
}
