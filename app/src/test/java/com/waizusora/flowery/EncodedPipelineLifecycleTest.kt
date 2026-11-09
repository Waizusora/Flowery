package com.waizusora.flowery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedPipelineLifecycleTest {
    @Test
    fun failedCloseCanBeRetriedWithoutReopeningPlaybackOperations() {
        val lifecycle = EncodedPipelineLifecycle()

        assertTrue(lifecycle.beginClose())
        assertFalse(lifecycle.acceptsControlRequests())
        assertThrows(IllegalStateException::class.java) { lifecycle.checkOpen() }

        // A failed native teardown does not complete the lifecycle.
        assertTrue(lifecycle.beginClose())
        assertThrows(IllegalStateException::class.java) { lifecycle.checkOpen() }

        lifecycle.completeClose()
        assertFalse(lifecycle.beginClose())
        assertFalse(lifecycle.acceptsControlRequests())
    }

    @Test
    fun successfulCloseIsIdempotent() {
        val lifecycle = EncodedPipelineLifecycle()

        assertTrue(lifecycle.beginClose())
        lifecycle.completeClose()

        assertFalse(lifecycle.beginClose())
    }
}
