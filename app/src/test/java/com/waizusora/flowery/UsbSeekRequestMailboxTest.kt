package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbSeekRequestMailboxTest {
    @Test
    fun latestPendingSeekReplacesEarlierRequests() {
        val mailbox = UsbSeekRequestMailbox()

        mailbox.request(1_000L)
        mailbox.request(2_500L)

        assertEquals(2_500L, mailbox.take())
    }

    @Test
    fun requestIsConsumedOnlyOnce() {
        val mailbox = UsbSeekRequestMailbox()
        mailbox.request(4_000L)

        assertEquals(4_000L, mailbox.take())
        assertNull(mailbox.take())
    }

    @Test
    fun clearDiscardsPendingSeek() {
        val mailbox = UsbSeekRequestMailbox()
        mailbox.request(8_000L)

        mailbox.clear()

        assertNull(mailbox.take())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSeekIsRejected() {
        UsbSeekRequestMailbox().request(-1L)
    }
}
