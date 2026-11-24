package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPlaybackSlotTest {
    private class Resource : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
        }
    }

    @Test
    fun matchingGenerationAndKeyTransfersResourceExactlyOnce() {
        val slot = PreparedPlaybackSlot<Resource>()
        val resource = Resource()
        slot.beginGeneration(1L)

        assertTrue(slot.publish(1L, "content://track/next", resource))
        assertSame(resource, slot.take(1L, "content://track/next"))
        assertNull(slot.take(1L, "content://track/next"))
        assertEquals(0, resource.closeCount)
    }

    @Test
    fun staleGenerationCannotPublishAndResourceIsClosed() {
        val slot = PreparedPlaybackSlot<Resource>()
        slot.beginGeneration(2L)
        val stale = Resource()

        assertFalse(slot.publish(1L, "content://track/next", stale))
        assertEquals(1, stale.closeCount)
        assertNull(slot.take(2L, "content://track/next"))
    }

    @Test
    fun changingGenerationClosesUnclaimedPreparedResource() {
        val slot = PreparedPlaybackSlot<Resource>()
        val resource = Resource()
        slot.beginGeneration(3L)
        slot.publish(3L, "content://track/next", resource)

        slot.beginGeneration(4L)

        assertEquals(1, resource.closeCount)
        assertNull(slot.take(4L, "content://track/next"))
    }

    @Test
    fun replacingPreparedResourceClosesPreviousEntry() {
        val slot = PreparedPlaybackSlot<Resource>()
        val first = Resource()
        val second = Resource()
        slot.beginGeneration(5L)
        slot.publish(5L, "content://track/a", first)

        slot.publish(5L, "content://track/b", second)

        assertEquals(1, first.closeCount)
        assertSame(second, slot.take(5L, "content://track/b"))
    }

    @Test
    fun invalidationClosesResourceAndRejectsOldGeneration() {
        val slot = PreparedPlaybackSlot<Resource>()
        val resource = Resource()
        slot.beginGeneration(6L)
        slot.publish(6L, "content://track/next", resource)

        slot.invalidate()

        assertEquals(1, resource.closeCount)
        assertNull(slot.take(6L, "content://track/next"))
    }

    @Test
    fun wrongKeyDoesNotTransferPreparedResource() {
        val slot = PreparedPlaybackSlot<Resource>()
        val resource = Resource()
        slot.beginGeneration(7L)
        slot.publish(7L, "content://track/expected", resource)

        assertNull(slot.take(7L, "content://track/other"))
        assertSame(resource, slot.take(7L, "content://track/expected"))
    }
}
