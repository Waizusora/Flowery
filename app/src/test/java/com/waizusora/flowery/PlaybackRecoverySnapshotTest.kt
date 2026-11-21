package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PlaybackRecoverySnapshotTest {
    private fun sample() = PlaybackRecoverySnapshot(
        queue = listOf("content://music/a.flac", "content://music/b.flac"),
        titles = listOf("A", "B"),
        artists = listOf("Artist A", "Artist B"),
        cueStartsMs = listOf(1_000L, 2_000L),
        cueEndsMs = listOf(10_000L, 20_000L),
        cuePlaylists = listOf("Disc 1", "Disc 1"),
        playlistNames = listOf("Album", "Album"),
        playlistOrders = listOf(0, 1),
        queueIndex = 1,
        repeatMode = 2,
        shuffleEnabled = true,
        positionMs = 5_500L,
        wasPlaying = true,
        callInterrupted = true,
        currentTitle = "B",
        currentArtist = "Artist B",
    )

    @Test
    fun roundTripPreservesQueuePositionCueAndInterruptionState() {
        val restored = PlaybackRecoverySnapshot.fromJson(sample().toJson())
        assertNotNull(restored)
        assertEquals(sample(), restored)
    }

    @Test
    fun normalizationAlignsEveryTrackMetadataArrayAndBoundsValues() {
        val restored = sample().copy(
            titles = listOf("Only title"),
            cueStartsMs = listOf(-5L),
            cueEndsMs = listOf(500L, 1_000L),
            queueIndex = 99,
            repeatMode = 9,
            positionMs = -10L,
        ).normalized()

        assertEquals(listOf("Only title", ""), restored.titles)
        assertEquals(listOf(0L, 0L), restored.cueStartsMs)
        assertEquals(listOf(500L, 0L), restored.cueEndsMs)
        assertEquals(1, restored.queueIndex)
        assertEquals(2, restored.repeatMode)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun rejectsUnknownVersionAndEmptyQueue() {
        assertNull(PlaybackRecoverySnapshot.fromJson(JSONObject(sample().toJson()).put("version", 99).toString()))
        assertNull(PlaybackRecoverySnapshot.fromJson(sample().copy(queue = emptyList()).toJson()))
    }

    @Test
    fun previouslyPlayingSessionIsRecordedButRecoveryMustRemainPaused() {
        val restored = PlaybackRecoverySnapshot.fromJson(sample().toJson())
        assertTrue(restored!!.wasPlaying)
        assertTrue(restored.callInterrupted)
        // The service recovery contract intentionally never treats wasPlaying as auto-play permission.
        assertFalse(restored.positionMs < 0L)
    }
}
