package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallInterruptionPolicyTest {
    @Test
    fun callStartLatchesInterruptionAndBlocksPlayback() {
        val policy = CallInterruptionPolicy()

        assertEquals(
            CallInterruptionPolicy.Transition.CALL_STARTED,
            policy.update(callAudioModeActive = true)
        )
        assertTrue(policy.isInterrupted)
        assertTrue(policy.blocksPlayback(callAudioModeActive = true))
    }

    @Test
    fun callEndClearsLatchWithoutRequestingAutomaticResume() {
        val policy = CallInterruptionPolicy()
        policy.update(callAudioModeActive = true)

        assertEquals(
            CallInterruptionPolicy.Transition.CALL_ENDED,
            policy.update(callAudioModeActive = false)
        )
        assertFalse(policy.isInterrupted)
        assertFalse(policy.blocksPlayback(callAudioModeActive = false))
    }

    @Test
    fun repeatedModeSamplesDoNotCreateDuplicateTransitions() {
        val policy = CallInterruptionPolicy()

        assertEquals(CallInterruptionPolicy.Transition.CALL_STARTED, policy.update(true))
        assertEquals(CallInterruptionPolicy.Transition.NONE, policy.update(true))
        assertEquals(CallInterruptionPolicy.Transition.CALL_ENDED, policy.update(false))
        assertEquals(CallInterruptionPolicy.Transition.NONE, policy.update(false))
    }

    @Test
    fun explicitPlayRemainsBlockedUntilCallModeEnds() {
        val policy = CallInterruptionPolicy()

        assertTrue(policy.blocksPlayback(callAudioModeActive = true))
        assertTrue(policy.blocksPlayback(callAudioModeActive = true))
        assertFalse(policy.blocksPlayback(callAudioModeActive = false))
    }
}
