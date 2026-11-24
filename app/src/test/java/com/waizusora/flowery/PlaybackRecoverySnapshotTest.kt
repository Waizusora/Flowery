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
            cueStartsMs = listOf(-5L, 2_000L),
            cueEndsMs = listOf(500L, 1_000L),
            queueIndex = 99,
            repeatMode = 9,
            positionMs = -10L,
        ).normalized()

        assertEquals(listOf("Only title", ""), restored.titles)
        assertEquals(listOf(0L, 2_000L), restored.cueStartsMs)
        assertEquals(listOf(500L, 0L), restored.cueEndsMs)
        assertEquals(1, restored.queueIndex)
        assertEquals(2, restored.repeatMode)
        assertEquals(0L, restored.positionMs)
    }


    @Test
    fun normalizationAlignsAllQueueMetadataArraysToTheQueueLength() {
        val restored = sample().copy(
            titles = listOf("First", "Second", "Discarded"),
            artists = listOf("Only artist"),
            cueStartsMs = listOf(-10L, 2_000L),
            cueEndsMs = listOf(500L, 1_000L, 9_000L),
            cuePlaylists = listOf("Disc A"),
            playlistNames = listOf("Album A", "Album B", "Discarded album"),
            playlistOrders = listOf(4, 5, 6),
        ).normalized()

        assertEquals(listOf("First", "Second"), restored.titles)
        assertEquals(listOf("Only artist", ""), restored.artists)
        assertEquals(listOf(0L, 2_000L), restored.cueStartsMs)
        assertEquals(listOf(500L, 0L), restored.cueEndsMs)
        assertEquals(listOf("Disc A", ""), restored.cuePlaylists)
        assertEquals(listOf("Album A", "Album B"), restored.playlistNames)
        assertEquals(listOf(4, 5), restored.playlistOrders)
        assertEquals(restored.queue.size, restored.titles.size)
        assertEquals(restored.queue.size, restored.artists.size)
        assertEquals(restored.queue.size, restored.cueStartsMs.size)
        assertEquals(restored.queue.size, restored.cueEndsMs.size)
        assertEquals(restored.queue.size, restored.cuePlaylists.size)
        assertEquals(restored.queue.size, restored.playlistNames.size)
        assertEquals(restored.queue.size, restored.playlistOrders.size)
    }

    @Test
    fun rejectsUnknownVersionAndEmptyQueue() {
        assertNull(PlaybackRecoverySnapshot.fromJson(JSONObject(sample().toJson()).put("version", 99).toString()))
        assertNull(PlaybackRecoverySnapshot.fromJson(sample().copy(queue = emptyList()).toJson()))
    }

    @Test
    fun previouslyPlayingSessionRequiresExplicitPlayAndPreservesPosition() {
        val restored = PlaybackRecoverySnapshot.fromJson(sample().toJson())
        assertNotNull(restored)
        assertTrue(restored!!.wasPlaying)

        val plan = PlaybackRecoveryPolicy.from(restored)
        assertTrue(plan.sessionAvailable)
        assertTrue(plan.requiresPrepareOnPlay)
        assertEquals(5_500L, plan.positionMs)
        assertFalse(plan.autoPlay)
    }

    @Test
    fun previouslyPausedSessionAlsoRequiresExplicitPlay() {
        val pausedSnapshot = sample().copy(wasPlaying = false, callInterrupted = false)
        val plan = PlaybackRecoveryPolicy.from(pausedSnapshot)

        assertTrue(plan.sessionAvailable)
        assertTrue(plan.requiresPrepareOnPlay)
        assertEquals(pausedSnapshot.positionMs, plan.positionMs)
        assertFalse(plan.autoPlay)
    }

    @Test
    fun emptyQueueSnapshotDoesNotCreateARecoverySession() {
        val plan = PlaybackRecoveryPolicy.from(sample().copy(queue = emptyList()))

        assertFalse(plan.sessionAvailable)
        assertFalse(plan.requiresPrepareOnPlay)
        assertEquals(0L, plan.positionMs)
        assertFalse(plan.autoPlay)
    }

    @Test
    fun negativeRecoveryPositionIsClampedToZero() {
        val plan = PlaybackRecoveryPolicy.from(sample().copy(positionMs = -500L))

        assertTrue(plan.sessionAvailable)
        assertEquals(0L, plan.positionMs)
        assertFalse(plan.autoPlay)
    }

    @Test
    fun missingSnapshotDoesNotCreateARecoverySession() {
        val plan = PlaybackRecoveryPolicy.from(null)
        assertFalse(plan.sessionAvailable)
        assertFalse(plan.requiresPrepareOnPlay)
        assertEquals(0L, plan.positionMs)
        assertFalse(plan.autoPlay)
    }
}
