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
    fun failedPreparationDoesNotPublishAResource() {
        val coordinator = PlaybackPrefetchCoordinator<Resource>(DirectExecutor())
        coordinator.beginGeneration(6L)

        assertTrue(coordinator.prefetch(6L, "next-uri") {
            error("simulated decoder preparation failure")
        })

        assertNull(coordinator.take(6L, "next-uri"))
        coordinator.close()
    }
}
