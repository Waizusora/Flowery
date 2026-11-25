package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class PlaybackPrefetchCoordinatorTest {
    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) {
            check(!stopped)
            command.run()
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }

    private class Resource : AutoCloseable {
        var closeCount = 0
        override fun close() { closeCount += 1 }
    }

    @Test
    fun preparesOfferedResourceAndTransfersOwnershipExactlyOnce() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        val resource = Resource()
        coordinator.beginGeneration(1L)

        assertTrue(coordinator.prefetch(1L, "next-uri") { resource })
        assertSame(resource, coordinator.take(1L, "next-uri"))
        assertNull(coordinator.take(1L, "next-uri"))
        assertEquals(0, resource.closeCount)
        coordinator.close()
        assertEquals(0, resource.closeCount)
    }

    @Test
    fun generationChangeClosesUnclaimedPrefetch() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        val resource = Resource()
        coordinator.beginGeneration(2L)
        coordinator.prefetch(2L, "next-uri") { resource }

        coordinator.beginGeneration(3L)

        assertEquals(1, resource.closeCount)
        assertNull(coordinator.take(2L, "next-uri"))
        coordinator.close()
    }

    @Test
    fun rejectsRequestForNonCurrentGeneration() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        coordinator.beginGeneration(4L)
        var prepareCalls = 0

        assertFalse(coordinator.prefetch(3L, "next-uri") {
            prepareCalls += 1
            Resource()
        })

        assertEquals(0, prepareCalls)
        coordinator.close()
    }

    @Test
    fun closeDisposesUnclaimedResourceAndRejectsFurtherWork() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        val resource = Resource()
        coordinator.beginGeneration(5L)
        coordinator.prefetch(5L, "next-uri") { resource }

        coordinator.close()

        assertEquals(1, resource.closeCount)
        assertNull(coordinator.take(5L, "next-uri"))
    }

    @Test
    fun reportsCurrentPreparationFailure() {
        val failures = mutableListOf<String>()
        val coordinator = PlaybackPrefetchCoordinator<Resource>(
            DirectExecutor(),
            onPreparationFailure = { failures += it.message.orEmpty() },
        )
        coordinator.beginGeneration(7L)

        assertTrue(coordinator.prefetch(7L, "next-uri") {
            error("decoder preparation failed")
        })

        assertEquals(listOf("decoder preparation failed"), failures)
        assertNull(coordinator.take(7L, "next-uri"))
        coordinator.close()
    }

    @Test
    fun failedPreparationDoesNotPublishAResource() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        coordinator.beginGeneration(8L)

        assertTrue(coordinator.prefetch(8L, "next-uri") {
            error("simulated decoder preparation failure")
        })

        assertNull(coordinator.take(8L, "next-uri"))
        coordinator.close()
    }
    @Test
    fun clearPrefetchDisposesResourceWithoutChangingGeneration() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        val stale = Resource()
        val replacement = Resource()
        coordinator.beginGeneration(9L)
        coordinator.prefetch(9L, "old-next") { stale }

        coordinator.clearPrefetch()

        assertEquals(1, stale.closeCount)
        assertNull(coordinator.take(9L, "old-next"))
        assertTrue(coordinator.prefetch(9L, "new-next") { replacement })
        assertSame(replacement, coordinator.take(9L, "new-next"))
        coordinator.close()
        assertEquals(0, replacement.closeCount)
    }

    @Test
    fun replacementRequestClosesOldSlotBeforePreparingNewCandidate() {
        val failures = mutableListOf<String>()
        val coordinator = PlaybackPrefetchCoordinator<Resource>(
            DirectExecutor(),
            onPreparationFailure = { failures += it.message.orEmpty() },
        )
        val old = Resource()
        coordinator.beginGeneration(10L)
        coordinator.prefetch(10L, "old-next") { old }

        coordinator.prefetch(10L, "new-next") {
            error("new candidate unavailable")
        }

        assertEquals(1, old.closeCount)
        assertNull(coordinator.take(10L, "old-next"))
        assertNull(coordinator.take(10L, "new-next"))
        assertEquals(listOf("new candidate unavailable"), failures)
        coordinator.close()
    }

}
