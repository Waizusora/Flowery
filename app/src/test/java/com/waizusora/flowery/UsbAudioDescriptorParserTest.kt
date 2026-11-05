package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorParserTest {
    @Test
    fun parsesUac1PcmFormatAndIsochronousEndpoint() {
        val descriptors = byteArrayOf(
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            14, 0x24, 2, 1, 2, 3, 24, 2,
            0x44, 0xAC.toByte(), 0, 0x80.toByte(), 0xBB.toByte(), 0,
            7, 5, 1, 5, 0, 4, 1
        )

        val report = UsbAudioDescriptorParser.report(descriptors, 0x31B2, 0x0111, "KT-384/32 HIFI", "KTMicro")

        assertTrue(report.contains("UAC1"))
        assertTrue(report.contains("Channels: 2"))
        assertTrue(report.contains("PCM subslot bytes: 3"))
        assertTrue(report.contains("PCM bit resolutions: 24"))
        assertTrue(report.contains("44100, 48000"))
        assertTrue(report.contains("isochronous"))
        assertTrue(report.contains("0x31B2"))
        assertTrue(report.contains("0x0111"))
    }

    @Test
    fun parsesUac1ContinuousSampleRateRange() {
        val descriptors = byteArrayOf(
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            14, 0x24, 2, 1, 2, 3, 24, 0,
            0x44, 0xAC.toByte(), 0, 0x00, 0x77, 0x01
        )

        val report = UsbAudioDescriptorParser.report(descriptors, 0x31B2, 0x0111, null, null)

        assertTrue(report.contains("Continuous sample-rate range: 44100-96000 Hz"))
    }

    @Test
    fun discoversUac2ClockSourceOnAudioControlInterface() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            8, 0x24, 0x0A, 5, 1, 3, 0, 0
        )

        assertEquals(listOf(5 to 0), UsbAudioDescriptorParser.clockSources(descriptors))
    }

    @Test
    fun selectsStereoUac2PlaybackAlternateForRequestedBitDepth() {
        val descriptors = byteArrayOf(
            9, 4, 2, 2, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0,
            6, 0x24, 2, 1, 3, 24,
            7, 5, 0x04, 0x09, 0x26, 1, 1
        )

        val profiles = UsbDacPlaybackProfile.findStereoUac2PlaybackProfiles(
            descriptors,
            requestedBitResolution = 24
        )

        assertEquals(1, profiles.size)
        assertEquals(2, profiles.single().channels)
        assertEquals(24, profiles.single().bitResolution)
        assertEquals(0x04, profiles.single().endpointAddress)
        assertEquals(294, profiles.single().maxPacketBytes)
    }

    @Test
    fun doesNotTreatMonoInputEndpointAsPlayback() {
        val descriptors = byteArrayOf(
            9, 4, 1, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0,
            6, 0x24, 2, 1, 2, 16,
            7, 5, 0x04.toByte(), 1, 98, 0, 1
        )

        assertTrue(UsbDacPlaybackProfile.findStereoUac2PlaybackProfiles(descriptors).isEmpty())
    }


    @Test
    fun resolvesOnlyClockSourceLinkedToStereoPlaybackTerminal() {
        val descriptors = byteArrayOf(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,
            17, 0x24, 0x02, 5, 1, 1, 0, 3, 2, 0, 0, 0, 0, 0, 0, 0, 0,
            8, 0x24, 0x0A, 3, 1, 0, 0, 0,
            8, 0x24, 0x0A, 6, 1, 0, 0, 0,
            9, 4, 2, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 0x01, 5, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0
        )

        assertEquals(
            listOf(3 to 0),
            UsbAudioDescriptorParser.playbackClockSources(descriptors, streamingInterface = 2)
        )
    }

}
