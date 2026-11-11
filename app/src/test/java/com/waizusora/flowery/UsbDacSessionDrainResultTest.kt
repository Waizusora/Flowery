package com.waizusora.flowery

import org.junit.Assert.assertThrows
import org.junit.Test

class UsbDacSessionDrainResultTest {
    @Test
    fun acceptsSuccessfulDrain() {
        requireUsbPcmDrainResult(0)
    }

    @Test
    fun acceptsIntentionalStopDuringDrain() {
        requireUsbPcmDrainResult(UsbDacSession.WRITE_STOPPED)
    }

    @Test
    fun rejectsNativeDrainFailure() {
        assertThrows(IllegalStateException::class.java) {
            requireUsbPcmDrainResult(-1)
        }
    }

    @Test
    fun rejectsUnexpectedPositiveStatus() {
        assertThrows(IllegalStateException::class.java) {
            requireUsbPcmDrainResult(1)
        }
    }
}
