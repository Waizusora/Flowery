package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueStateTest {
    @Test
    fun downloadedLyricsUseTheirOwnListForActiveLineSelection() {
        val downloaded = listOf(
            LyricLine(1_000L, "First line"),
            LyricLine(2_000L, "Second line")
        )

        assertEquals(0, LrcParser.activeLineIndex(downloaded, 1_500L))
        assertEquals(1, LrcParser.activeLineIndex(downloaded, 2_500L))
    }

    @Test
    fun activeLineSelectionHandlesEmptyLyricsAndPositionsBeforeFirstLine() {
        assertEquals(-1, LrcParser.activeLineIndex(emptyList(), 0L))
        assertEquals(0, LrcParser.activeLineIndex(listOf(LyricLine(1_000L, "First")), 0L))
    }

    @Test
    fun actionValuesMatchTheNativeQueueContract() {
        assertEquals(0, PlaybackQueueState.Action.NEXT.nativeValue)
        assertEquals(1, PlaybackQueueState.Action.PREVIOUS.nativeValue)
        assertEquals(2, PlaybackQueueState.Action.COMPLETION.nativeValue)
    }
}
