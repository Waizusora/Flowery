package com.waizusora.flowery

import android.net.Uri
import org.mockito.Mockito.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ReplayGainDisplayPlanTest {
    private fun tag(
        trackGain: String = "",
        trackPeak: String = "",
        albumGain: String = "",
        albumPeak: String = ""
    ) = AudioTag(
        uri = mock(Uri::class.java),
        fileName = "test.flac",
        title = "Test",
        artist = "",
        album = "",
        albumArtist = "",
        genre = "",
        year = "",
        track = "",
        disc = "",
        comment = "",
        composer = "",
        lyrics = "",
        pictureMime = "",
        pictureBase64 = "",
        tagType = "",
        fileType = "FLAC",
        writable = false,
        sampleRate = 48_000,
        bitDepth = 24,
        channels = 2,
        bitrateKbps = 0,
        durationMs = 1_000,
        replayGainTrackGain = trackGain,
        replayGainTrackPeak = trackPeak,
        replayGainAlbumGain = albumGain,
        replayGainAlbumPeak = albumPeak
    )

    @Test
    fun offModeAlwaysReportsUnityAndNoPeakLimit() {
        val plan = calculateReplayGainDisplay(
            tag(trackGain = "6 dB", trackPeak = "1.5"),
            ReplayGainPreferenceState(mode = 0, preventClipping = true)
        )
        assertEquals("Off", plan.modeLabel)
        assertEquals(0f, plan.effectiveGainDb, 0f)
        assertFalse(plan.peakLimitApplied)
        assertFalse(plan.peakMetadataAvailable)
    }

    @Test
    fun trackModeCombinesTrackGainAndPreamp() {
        val plan = calculateReplayGainDisplay(
            tag(trackGain = "-8.0 dB", trackPeak = "0.5"),
            ReplayGainPreferenceState(mode = 1, trackPreampDb = 2f, preventClipping = false)
        )
        assertEquals("Track", plan.modeLabel)
        assertEquals(-6f, plan.effectiveGainDb, 0.0001f)
        assertFalse(plan.peakLimitApplied)
        assertTrue(plan.peakMetadataAvailable)
    }

    @Test
    fun clippingPreventionCapsGainToPeakHeadroom() {
        val peak = 1.2f
        val expected = -20f * kotlin.math.log10(peak)
        val plan = calculateReplayGainDisplay(
            tag(trackGain = "6 dB", trackPeak = peak.toString()),
            ReplayGainPreferenceState(mode = 1, preventClipping = true)
        )
        assertTrue(plan.peakLimitApplied)
        assertEquals(expected, plan.effectiveGainDb, 0.0001f)
        val linearGain = 10f.pow(plan.effectiveGainDb / 20f)
        assertTrue("peak multiplied by gain must not exceed full scale", peak * linearGain <= 1.0001f)
    }

    @Test
    fun albumModePrefersAlbumMetadataAndFallsBackToTrackMetadata() {
        val albumPlan = calculateReplayGainDisplay(
            tag(trackGain = "-8 dB", trackPeak = "0.9", albumGain = "-5 dB", albumPeak = "1.1"),
            ReplayGainPreferenceState(mode = 2, albumPreampDb = 1f, preventClipping = false)
        )
        assertEquals( -4f, albumPlan.effectiveGainDb, 0.0001f)
        assertTrue(albumPlan.peakMetadataAvailable)

        val fallbackPlan = calculateReplayGainDisplay(
            tag(trackGain = "-8 dB", trackPeak = "0.9"),
            ReplayGainPreferenceState(mode = 2, preventClipping = false)
        )
        assertEquals(-8f, fallbackPlan.effectiveGainDb, 0.0001f)
        assertTrue(fallbackPlan.peakMetadataAvailable)
    }

    @Test
    fun invalidMetadataIsIgnoredAndGainRemainsFinite() {
        val plan = calculateReplayGainDisplay(
            tag(trackGain = "NaN", trackPeak = "Infinity"),
            ReplayGainPreferenceState(mode = 1, trackPreampDb = Float.NaN, preventClipping = true)
        )
        assertEquals(0f, plan.effectiveGainDb, 0f)
        assertFalse(plan.peakMetadataAvailable)
        assertFalse(plan.peakLimitApplied)
    }
}
