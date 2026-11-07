package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPcmRouteNegotiatorTest {
    @Test
    fun ieeeFloatSourcePrefersExactFloatThenUsesDescriptorDrivenIntegerFallback() {
        val attempts = UsbPcmRouteNegotiator.profileAttempts(
            ieeeFloatSource = true,
            sourceBits = 32,
            equalizerFlat = true,
        )

        assertEquals(2, attempts.size)
        assertEquals("ieee_float32", attempts[0].encoding)
        assertEquals(32, attempts[0].bitResolution)
        assertFalse(attempts[0].allowSampleRateConversion)
        assertEquals(4, attempts[0].requiredSubslotBytes)
        assertTrue(attempts[0].sourcePreservingCandidate)
        assertEquals("signed_integer", attempts[1].encoding)
        assertTrue(attempts[1].allowSampleRateConversion)
        assertFalse(attempts[1].sourcePreservingCandidate)
    }

    @Test
    fun integerSourcePrefersExactResolutionBeforeAutomaticFallback() {
        val attempts = UsbPcmRouteNegotiator.profileAttempts(
            ieeeFloatSource = false,
            sourceBits = 24,
            equalizerFlat = true,
        )

        assertEquals(2, attempts.size)
        assertEquals(24, attempts[0].bitResolution)
        assertEquals("signed_integer", attempts[0].encoding)
        assertFalse(attempts[0].allowSampleRateConversion)
        assertTrue(attempts[0].sourcePreservingCandidate)
        assertEquals(0, attempts[1].bitResolution)
        assertTrue(attempts[1].allowSampleRateConversion)
        assertFalse(attempts[1].sourcePreservingCandidate)
    }

    @Test
    fun activeEqualizerDisablesSourcePreservingProfilePreference() {
        val attempts = UsbPcmRouteNegotiator.profileAttempts(
            ieeeFloatSource = true,
            sourceBits = 24,
            equalizerFlat = false,
        )

        assertEquals(1, attempts.size)
        assertEquals(0, attempts.single().bitResolution)
        assertEquals("signed_integer", attempts.single().encoding)
        assertFalse(attempts.single().sourcePreservingCandidate)
    }

    @Test
    fun unsupportedIntegerDepthUsesAutomaticDescriptorDrivenSelection() {
        val attempts = UsbPcmRouteNegotiator.profileAttempts(
            ieeeFloatSource = false,
            sourceBits = 7,
            equalizerFlat = true,
        )

        assertEquals(1, attempts.size)
        assertEquals(0, attempts.single().bitResolution)
        assertTrue(attempts.single().allowSampleRateConversion)
    }
}
