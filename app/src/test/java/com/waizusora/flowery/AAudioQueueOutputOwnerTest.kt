package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AAudioQueueOutputOwnerTest {
    private class FakeNative : AAudioQueueOutputNative {
        val persistence = mutableListOf<Boolean>()
        val prepared = mutableListOf<AAudioOutputSignature>()
        var prepareResult = 0
        var stopCount = 0
        override fun setPersistence(enabled: Boolean) { persistence += enabled }
        override fun prepareNextTrack(signature: AAudioOutputSignature): Int {
            prepared += signature
            return prepareResult
        }
        override fun stopOutput() { stopCount += 1 }
    }

    private fun signature(
        rate: Int = 48_000,
        channels: Int = 2,
        format: Int = 1,
        device: Int = 7,
        sharing: Int = AAudioOutputSignature.SHARING_EXCLUSIVE,
    ) = AAudioOutputSignature(rate, channels, format, device, sharing)

    @Test
    fun identicalPcmAndDeviceSignatureCanBePreparedAndAttached() {
        val native = FakeNative()
        val owner = AAudioQueueOutputOwner(native)
        val current = signature()

        assertTrue(owner.adoptOpenedOutput(current))
        assertTrue(owner.retainForNextTrack())
        assertTrue(owner.prepareNextTrack(current))
        assertTrue(owner.confirmProducerAttached())
        assertEquals(AAudioQueueOutputOwner.Phase.OPEN, owner.phase())
        assertEquals(listOf(true), native.persistence)
        assertEquals(listOf(current), native.prepared)
    }

    @Test
    fun pcmOrDeviceChangeIsRejectedWithoutTouchingNativeRing() {
        val native = FakeNative()
        val owner = AAudioQueueOutputOwner(native)
        val current = signature()
        assertTrue(owner.adoptOpenedOutput(current))
        assertTrue(owner.retainForNextTrack())

        assertFalse(owner.prepareNextTrack(signature(rate = 96_000)))
        assertFalse(owner.prepareNextTrack(signature(device = 8)))
        assertFalse(owner.prepareNextTrack(signature(sharing = AAudioOutputSignature.SHARING_SHARED)))
        assertTrue(native.prepared.isEmpty())
        assertEquals(AAudioQueueOutputOwner.Phase.RETAINED, owner.phase())
    }

    @Test
    fun nativeRingResetFailureDoesNotClaimProducerReady() {
        val native = FakeNative().apply { prepareResult = -898 }
        val owner = AAudioQueueOutputOwner(native)
        val current = signature()
        assertTrue(owner.adoptOpenedOutput(current))
        assertTrue(owner.retainForNextTrack())

        assertFalse(owner.prepareNextTrack(current))
        assertFalse(owner.confirmProducerAttached())
        assertEquals(AAudioQueueOutputOwner.Phase.RETAINED, owner.phase())
    }

    @Test
    fun queueCloseRevokesLeaseAndStopsOutputExactlyOnce() {
        val native = FakeNative()
        val owner = AAudioQueueOutputOwner(native)
        assertTrue(owner.adoptOpenedOutput(signature()))
        assertTrue(owner.retainForNextTrack())

        owner.close()
        owner.close()

        assertEquals(listOf(true, false), native.persistence)
        assertEquals(1, native.stopCount)
        assertEquals(AAudioQueueOutputOwner.Phase.CLOSED, owner.phase())
        assertNull(owner.currentSignature())
    }

    @Test
    fun invalidOrDuplicateOutputAdoptionIsRejected() {
        val native = FakeNative()
        val owner = AAudioQueueOutputOwner(native)
        assertFalse(owner.adoptOpenedOutput(signature(rate = 0)))
        assertTrue(owner.adoptOpenedOutput(signature()))
        assertFalse(owner.adoptOpenedOutput(signature(device = 8)))
        assertTrue(owner.retainForNextTrack())
    }
}
