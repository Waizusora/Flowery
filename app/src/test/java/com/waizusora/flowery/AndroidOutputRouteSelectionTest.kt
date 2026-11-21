package com.waizusora.flowery

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AndroidOutputRouteSelectionTest {
    private fun device(id: Int, type: Int, name: String) =
        OutputDeviceCandidate(id, type, name)

    @Before fun resetDeviceArrivalOrder() {
        AndroidOutputDeviceOrder.onRemoved(listOf(1, 2, 3, 7, 9, 12))
    }

    @Test fun resolvesUsbDacOutputs() {
        val selected = AndroidOutputRouteSelection.selectUsb(listOf(
            device(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
            device(12, AudioDeviceInfo.TYPE_USB_DEVICE, "USB DAC")))
        assertEquals(12, selected?.id)
        assertEquals("USB DAC", selected?.productName)
    }

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
        assertNull(AndroidOutputRouteSelection.selectUsb(emptyList()))
        assertNull(AndroidOutputRouteSelection.selectAux(emptyList()))
        assertNull(AndroidOutputRouteSelection.selectSpeaker(emptyList()))
        assertNull(AndroidOutputRouteSelection.selectBluetooth(emptyList()))
    }

    @Test fun detectsConnectedOutputAutomatically() {
        assertEquals(AndroidOutputTarget.SPEAKER, AndroidOutputRouteSelection.detectCurrent(emptyList()))
        assertEquals(AndroidOutputTarget.USB, AndroidOutputRouteSelection.detectCurrent(listOf(
            device(12, AudioDeviceInfo.TYPE_USB_HEADSET, "USB DAC")
        )))
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputRouteSelection.detectCurrent(listOf(
            device(3, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Wired")
        )))
        assertEquals(AndroidOutputTarget.BLUETOOTH, AndroidOutputRouteSelection.detectCurrent(listOf(
            device(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Bluetooth")
        )))
    }

    @Test fun newestConnectedOutputWinsUsingLifoOrder() {
        val bluetooth = device(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Bluetooth")
        val wired = device(3, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Wired")
        val usb = device(12, AudioDeviceInfo.TYPE_USB_DEVICE, "USB DAC")

        AndroidOutputDeviceOrder.onAdded(listOf(bluetooth))
        AndroidOutputDeviceOrder.onAdded(listOf(wired))
        var ordered = AndroidOutputDeviceOrder.newestFirst(listOf(bluetooth, wired))
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputRouteSelection.detectCurrent(ordered))

        AndroidOutputDeviceOrder.onAdded(listOf(usb))
        ordered = AndroidOutputDeviceOrder.newestFirst(listOf(bluetooth, wired, usb))
        assertEquals(AndroidOutputTarget.USB, AndroidOutputRouteSelection.detectCurrent(ordered))

        AndroidOutputDeviceOrder.onRemoved(listOf(usb.id))
        ordered = AndroidOutputDeviceOrder.newestFirst(listOf(bluetooth, wired))
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputRouteSelection.detectCurrent(ordered))
    }

    @Test fun outputRouteTransitionsRequireManualResume() {
        val usb = OutputRouteTransitionPolicy.forTarget(AndroidOutputTarget.USB, 42_000L)
        assertEquals(0L, usb.positionMs)
        assertEquals(true, usb.startPaused)

        val aaudio = OutputRouteTransitionPolicy.forTarget(AndroidOutputTarget.AUX, 42_000L)
        assertEquals(42_000L, aaudio.positionMs)
        assertEquals(true, aaudio.startPaused)

        val speaker = OutputRouteTransitionPolicy.forTarget(AndroidOutputTarget.SPEAKER, 0L)
        assertEquals(0L, speaker.positionMs)
        assertEquals(true, speaker.startPaused)
    }

    @Test fun preferenceDefaultsToSpeaker() {
        assertEquals(AndroidOutputTarget.SPEAKER, AndroidOutputTarget.fromPreference(null))
        assertEquals(AndroidOutputTarget.USB, AndroidOutputTarget.fromPreference("usb"))
        assertEquals(AndroidOutputTarget.AUX, AndroidOutputTarget.fromPreference("aux"))
        assertEquals(AndroidOutputTarget.BLUETOOTH, AndroidOutputTarget.fromPreference("bluetooth"))
    }
}
