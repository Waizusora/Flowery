package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputVolumePolicyTest {
    @Test fun usbRouteDoesNotChangeAndroidSystemVolume() {
        assertFalse(OutputVolumePolicy.allowsSystemVolumeControl(usbRouteActive = true))
        assertTrue(OutputVolumePolicy.allowsSystemVolumeControl(usbRouteActive = false))
    }

    @Test fun usbRouteAlwaysUsesUnityNativeGainEvenWhenDucked() {
        assertEquals(1f, OutputVolumePolicy.nativeGain(true, true, 0.25f), 0f)
        assertEquals(1f, OutputVolumePolicy.nativeGain(true, false, 0.25f), 0f)
    }

    @Test fun androidRoutesRetainDuckingPolicy() {
        assertEquals(0.25f, OutputVolumePolicy.nativeGain(false, true, 0.25f), 0f)
        assertEquals(1f, OutputVolumePolicy.nativeGain(false, false, 0.25f), 0f)
    }

    @Test fun usbVolumeIsReportedAsUnity() {
        assertEquals(1f, OutputVolumePolicy.reportedVolume(true, 0.4f), 0f)
        assertEquals(0.4f, OutputVolumePolicy.reportedVolume(false, 0.4f), 0f)
    }
}
