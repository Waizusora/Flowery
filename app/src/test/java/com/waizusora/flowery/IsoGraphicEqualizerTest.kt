package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IsoGraphicEqualizerTest {
    @Test
    fun exposesThe31IsoPreferredCenterFrequencies() {
        assertEquals(31, IsoGraphicEqualizer.centerFrequenciesHz.size)
        assertEquals(20f, IsoGraphicEqualizer.centerFrequenciesHz.first())
        assertEquals(1000f, IsoGraphicEqualizer.centerFrequenciesHz[17])
        assertEquals(20000f, IsoGraphicEqualizer.centerFrequenciesHz.last())
    }

    @Test
    fun formatsFrequencyLabelsForTheGraphicEqualizer() {
        assertEquals("20", IsoGraphicEqualizer.formatFrequency(0))
        assertEquals("31.5", IsoGraphicEqualizer.formatFrequency(2))
        assertEquals("1k", IsoGraphicEqualizer.formatFrequency(17))
        assertEquals("1.25k", IsoGraphicEqualizer.formatFrequency(18))
        assertEquals("20k", IsoGraphicEqualizer.formatFrequency(30))
    }

    @Test
    fun legacyThreeBandValuesAreExpandedOnlyForPreferenceMigration() {
        val expanded = IsoGraphicEqualizer.expandLegacyThreeBand(listOf(3f, -2f, 5f))
        assertEquals(31, expanded.size)
        assertEquals(3f, expanded[0])
        assertEquals(-2f, expanded[17])
        assertEquals(5f, expanded[30])
        assertEquals(3f, expanded[10])
        assertEquals(-2f, expanded[20])
    }

    @Test
    fun legacyValuesAreClampedToNativeGainRange() {
        val expanded = IsoGraphicEqualizer.expandLegacyThreeBand(listOf(30f, -30f, 15f))
        assertEquals(12f, expanded[0])
        assertEquals(-12f, expanded[17])
        assertEquals(12f, expanded[30])
    }

    @Test
    fun preferenceKeysAreUniqueAndValidateBandIndex() {
        assertEquals("equalizer_band_0_db", IsoGraphicEqualizer.preferenceKey(0))
        assertEquals("equalizer_band_30_db", IsoGraphicEqualizer.preferenceKey(30))
        assertThrows(IllegalArgumentException::class.java) {
            IsoGraphicEqualizer.preferenceKey(31)
        }
    }
}
