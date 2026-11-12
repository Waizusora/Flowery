package com.waizusora.flowery

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidOutputRouteSelectionTest {
    private fun device(id: Int, type: Int, name: String) =
        OutputDeviceCandidate(id, type, name)

    @Test fun resolvesAuxFromWiredAnalogOutputs() {
        val selected = AndroidOutputRouteSelection.selectAux(listOf(
            device(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
            device(7, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "AUX")))
        assertEquals(7, selected?.id)
        assertEquals("AUX", selected?.productName)
    }

    @Test fun resolvesSpeakerIndependently() {
        val selected = AndroidOutputRouteSelection.selectSpeaker(listOf(
            device(7, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "AUX"),
            device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")))
        assertEquals(2, selected?.id)
    }

    @Test fun resolvesBluetoothA2dp() {
        val selected = AndroidOutputRouteSelection.selectBluetooth(listOf(
            device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
            device(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Bluetooth")))
        assertEquals(9, selected?.id)
    }

    @Test fun missingRouteReturnsNull() {
        assertNull(AndroidOutputRouteSelection.selectAux(emptyList()))
        assertNull(AndroidOutputRouteSelection.selectSpeaker(emptyList()))
        assertNull(AndroidOutputRouteSelection.selectBluetooth(emptyList()))
    }

    @Test fun detectsConnectedOutputAutomatically() {
        assertEquals(AndroidOutputTarget.SPEAKER, AndroidOutputRouteSelection.detectCurrent(emptyList()))
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputRouteSelection.detectCurrent(listOf(
            device(3, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Wired")
        )))
        assertEquals(AndroidOutputTarget.BLUETOOTH, AndroidOutputRouteSelection.detectCurrent(listOf(
            device(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Bluetooth")
        )))
    }

    @Test fun wiredOutputTakesPrecedenceWhenMultipleRoutesAreConnected() {
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputRouteSelection.detectCurrent(listOf(
            device(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Bluetooth"),
            device(3, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Wired")
        )))
    }

    @Test fun preferenceDefaultsToSpeaker() {
        assertEquals(AndroidOutputTarget.SPEAKER, AndroidOutputTarget.fromPreference(null))
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputTarget.fromPreference("aux"))
        assertEquals(AndroidOutputTarget.BLUETOOTH, AndroidOutputTarget.fromPreference("bluetooth"))
    }
}
