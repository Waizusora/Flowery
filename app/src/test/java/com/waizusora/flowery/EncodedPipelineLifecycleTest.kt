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

    @Test
    fun retrySkipsCompletedStepsAndRetriesOnlyTheFailedStep() {
        val lifecycle = EncodedPipelineLifecycle()
        var producerStopCalls = 0
        var producerDestroyCalls = 0
        var queueCloseCalls = 0

        assertTrue(lifecycle.beginClose())
        lifecycle.runCloseStep("stop-producer") { producerStopCalls++ }
        assertThrows(IllegalStateException::class.java) {
            lifecycle.runCloseStep("destroy-producer") {
                producerDestroyCalls++
                throw IllegalStateException("simulated native teardown failure")
            }
        }

        assertTrue(lifecycle.beginClose())
        lifecycle.runCloseStep("stop-producer") { producerStopCalls++ }
        lifecycle.runCloseStep("destroy-producer") { producerDestroyCalls++ }
        lifecycle.runCloseStep("close-queue") { queueCloseCalls++ }
        lifecycle.completeClose()

        assertTrue(lifecycle.beginClose().not())
        assertTrue(producerStopCalls == 1)
        assertTrue(producerDestroyCalls == 2)
        assertTrue(queueCloseCalls == 1)
    }

    @Test
    fun failedCloseStepIsNotMarkedComplete() {
        val lifecycle = EncodedPipelineLifecycle()
        var attempts = 0

        assertTrue(lifecycle.beginClose())
        assertThrows(IllegalStateException::class.java) {
            lifecycle.runCloseStep("close-usb") {
                attempts++
                throw IllegalStateException("temporary close failure")
            }
        }

        lifecycle.runCloseStep("close-usb") { attempts++ }
        lifecycle.runCloseStep("close-usb") { attempts++ }

        assertTrue(attempts == 2)
    }

}
