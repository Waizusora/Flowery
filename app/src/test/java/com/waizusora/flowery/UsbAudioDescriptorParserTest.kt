package com.waizusora.flowery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAudioDescriptorParserTest {
    @Test
    fun mapsNativePlaybackProfileWithoutReparsingDescriptorsInKotlin() {
        val profile = UsbDacPlaybackProfile.fromNativeJson(JSONObject(
            """{"interfaceNumber":2,"alternateSetting":3,"channels":2,"subslotBytes":3,"bitResolution":24,"endpointAddress":4,"maxPacketBytes":294,"interval":1,"synchronization":"asynchronous"}"""
        ))

        assertEquals(2, profile.channels)
        assertEquals(24, profile.bitResolution)
        assertEquals(294, profile.maxPacketBytes)
        assertEquals("0x04", profile.endpointHex)
    }
}
