package com.waizusora.flowery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSeekEpochPolicyTest {
    @Test
    fun encodedSeekLeavesEpochTransitionToEncodedPipeline() {
        assertFalse(shouldAdvanceUsbSessionEpochBeforeSeek(encodedPipelineActive = true, pcmProducerHandle = 0L))
    }

    @Test
    fun activePcmSeekInvalidatesProducerEpochBeforeRequestingSeek() {
        assertTrue(shouldAdvanceUsbSessionEpochBeforeSeek(encodedPipelineActive = false, pcmProducerHandle = 7L))
    }

    @Test
    fun startupSeekDoesNotAdvanceEpochWithoutAnActivePcmProducer() {
        assertFalse(shouldAdvanceUsbSessionEpochBeforeSeek(encodedPipelineActive = false, pcmProducerHandle = 0L))
    }

    @Test
    fun encodedPipelineTakesPrecedenceIfBothHandlesArePresent() {
        assertFalse(shouldAdvanceUsbSessionEpochBeforeSeek(encodedPipelineActive = true, pcmProducerHandle = 7L))
    }
}
