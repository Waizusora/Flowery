package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbNativePlaybackSessionTest {
    @Test
    fun doesNotStartNativePcmWhenClockNegotiationFails() {
        var nativeStartCalled = false
        var rollbackCalled = false
        var commitCalled = false

        val result = startUsbPlaybackTransaction(
            negotiateClock = { Result.failure(IllegalStateException("clock negotiation failed")) },
            startNativePlayback = {
                nativeStartCalled = true
                Result.success(Unit)
            },
            rollbackClock = {
                rollbackCalled = true
                Result.success(Unit)
            },
            commitClock = { commitCalled = true },
            rollbackOnStartFailure = true
        )

        assertTrue(result.isFailure)
        assertFalse(nativeStartCalled)
        assertFalse(rollbackCalled)
        assertFalse(commitCalled)
    }

    @Test
    fun rollsBackClockWhenNativeStreamFailsToStart() {
        val events = mutableListOf<String>()

        val result = startUsbPlaybackTransaction(
            negotiateClock = { events += "negotiate"; Result.success(Unit) },
            startNativePlayback = {
                events += "start"
                Result.failure(IllegalStateException("native start failed"))
            },
            rollbackClock = {
                events += "rollback"
                Result.success(Unit)
            },
            commitClock = { events += "commit" },
            rollbackOnStartFailure = true
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("negotiate", "start", "rollback"), events)
        assertEquals("native start failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun commitsClockOnlyAfterNativeStreamStartsSuccessfully() {
        val events = mutableListOf<String>()

        val result = startUsbPlaybackTransaction(
            negotiateClock = { events += "negotiate"; Result.success(Unit) },
            startNativePlayback = { events += "start"; Result.success(Unit) },
            rollbackClock = {
                events += "rollback"
                Result.success(Unit)
            },
            commitClock = { events += "commit" },
            rollbackOnStartFailure = true
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("negotiate", "start", "commit"), events)
    }

    @Test
    fun reportsFatalWhenStartupRollbackFails() {
        val result = startUsbPlaybackTransaction(
            negotiateClock = { Result.success(Unit) },
            startNativePlayback = {
                Result.failure(IllegalStateException("native start failed"))
            },
            rollbackClock = {
                Result.failure(IllegalStateException("FATAL: clock restore failed"))
            },
            commitClock = {},
            rollbackOnStartFailure = true
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("native start failed") == true)
        assertTrue(result.exceptionOrNull()?.message?.contains("FATAL: clock restore failed") == true)
    }

    @Test
    fun doesNotRollbackClockForUac1StartupFailure() {
        var rollbackCalled = false
        val result = startUsbPlaybackTransaction(
            negotiateClock = { Result.success(Unit) },
            startNativePlayback = {
                Result.failure(IllegalStateException("native start failed"))
            },
            rollbackClock = {
                rollbackCalled = true
                Result.success(Unit)
            },
            commitClock = {},
            rollbackOnStartFailure = false
        )

        assertTrue(result.isFailure)
        assertFalse(rollbackCalled)
    }
}
