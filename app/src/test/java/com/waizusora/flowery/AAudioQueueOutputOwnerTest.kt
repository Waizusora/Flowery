package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AAudioQueueOutputOwnerTest {
    @Test
    fun queuedPcmDurationUsesOutputRateAndRejectsInvalidInputs() {
        assertEquals(100L, queuedPcmDurationMs(4_800, 48_000))
        assertEquals(50L, queuedPcmDurationMs(2_205, 44_100))
        assertEquals(0L, queuedPcmDurationMs(0, 48_000))
        assertNull(queuedPcmDurationMs(-1, 48_000))
        assertNull(queuedPcmDurationMs(4_800, 0))
    }


    private class FakeNative : AAudioQueueOutputNative {
        val persistence = mutableListOf<Boolean>()
        val attached = mutableListOf<AAudioOutputSignature>()
        var prepareResult = 0
        var throwOnAttach = false
        var stopCount = 0
        var failEnablePersistence = false
        var failDisablePersistence = false
        override fun setPersistence(enabled: Boolean) {
            if (enabled && failEnablePersistence) error("native persistence lease failed")
            if (!enabled && failDisablePersistence) error("native persistence revoke failed")
            persistence += enabled
        }
        override fun attachNextTrack(signature: AAudioOutputSignature): Int {
            attached += signature
            if (throwOnAttach) error("native producer attach threw")
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
        assertEquals(listOf(current), native.attached)
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
        assertTrue(native.attached.isEmpty())
        assertEquals(AAudioQueueOutputOwner.Phase.RETAINED, owner.phase())
    }

    @Test
    fun channelCountAndSampleFormatChangesRequireAReopen() {
        val native = FakeNative()
        val owner = AAudioQueueOutputOwner(native)
        val current = signature(rate = 48_000, channels = 2, format = 1)
        assertTrue(owner.adoptOpenedOutput(current))
        assertTrue(owner.retainForNextTrack())

        assertFalse(owner.prepareNextTrack(signature(channels = 1)))
        assertFalse(owner.prepareNextTrack(signature(format = 3)))
        assertTrue(native.attached.isEmpty())
        assertEquals(AAudioQueueOutputOwner.Phase.RETAINED, owner.phase())

        owner.close()
        assertEquals(1, native.stopCount)
        assertEquals(AAudioQueueOutputOwner.Phase.CLOSED, owner.phase())
    }

    @Test
    fun failedPersistenceEnableDoesNotPublishRetainedLease() {
        val native = FakeNative().apply { failEnablePersistence = true }
        val owner = AAudioQueueOutputOwner(native)
        assertTrue(owner.adoptOpenedOutput(signature()))

        assertFalse(owner.retainForNextTrack())
        assertEquals(AAudioQueueOutputOwner.Phase.OPEN, owner.phase())
        assertTrue(native.persistence.isEmpty())

        owner.close()
        assertEquals(1, native.stopCount)
        assertEquals(AAudioQueueOutputOwner.Phase.CLOSED, owner.phase())
    }

    @Test
    fun failedProducerStartupAfterNativeAttachReleasesPreparedOutput() {
        val native = FakeNative()
        val owner = AAudioQueueOutputOwner(native)
        val current = signature()
        assertTrue(owner.adoptOpenedOutput(current))
        assertTrue(owner.retainForNextTrack())
        assertTrue(owner.prepareNextTrack(current))

        // Model native decoder/pump startup failing after the ring epoch changed.
        owner.close()

        assertEquals(listOf(true, false), native.persistence)
        assertEquals(1, native.stopCount)
        assertEquals(AAudioQueueOutputOwner.Phase.CLOSED, owner.phase())
        assertNull(owner.currentSignature())
    }

    @Test
    fun nativeProducerAttachFailureDoesNotClaimProducerReady() {
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
    fun nativeProducerAttachExceptionDoesNotAdvanceOwnerPhase() {
        val native = FakeNative().apply { throwOnAttach = true }
        val owner = AAudioQueueOutputOwner(native)
        val current = signature()
        assertTrue(owner.adoptOpenedOutput(current))
        assertTrue(owner.retainForNextTrack())

        assertFalse(owner.prepareNextTrack(current))
        assertFalse(owner.confirmProducerAttached())
        assertEquals(AAudioQueueOutputOwner.Phase.RETAINED, owner.phase())

        owner.close()
        assertEquals(1, native.stopCount)
        assertEquals(AAudioQueueOutputOwner.Phase.CLOSED, owner.phase())
    }

    @Test
    fun outputSignatureMatchesNativeChannelLimits() {
        assertTrue(signature(channels = 6, format = 1).isValid())
        assertTrue(signature(channels = 8, format = 4).isValid())
        assertFalse(signature(channels = 6, format = 2).isValid())
        assertFalse(signature(channels = 9, format = 1).isValid())
    }
    @Test
    fun earlyBoundaryAdvancesOnlyWhenQueueHasNextTrack() {
        assertTrue(shouldAdvanceAaudioQueue(status = 3, hasNextTrack = true))
        assertFalse(shouldAdvanceAaudioQueue(status = 3, hasNextTrack = false))
        assertTrue(shouldAdvanceAaudioQueue(status = 4, hasNextTrack = false))
        assertFalse(shouldAdvanceAaudioQueue(status = 0, hasNextTrack = true))
        assertFalse(shouldAdvanceAaudioQueue(status = 1, hasNextTrack = true))
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
    fun persistenceRevokeFailureStillStopsOutputAndClearsOwnerState() {
        val native = FakeNative().apply { failDisablePersistence = true }
        val owner = AAudioQueueOutputOwner(native)
        assertTrue(owner.adoptOpenedOutput(signature()))
        assertTrue(owner.retainForNextTrack())

        assertTrue(runCatching { owner.close() }.isFailure)
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
