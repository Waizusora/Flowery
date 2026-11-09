package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class LibraryTextEncodingTest {
    @Test
    fun decodesSelectedCharset() {
        val bytes = "Música".toByteArray(Charset.forName("windows-1252"))
        assertEquals("Música", LibraryTextEncoding.decode(bytes, "windows-1252"))
        assertNotNull(
            LibraryTextEncoding.available().firstOrNull {
                it.name() == StandardCharsets.UTF_8.name()
            }
        )
    }

    @Test
    fun fallsBackToUtf8ForUnknownCharset() {
        val bytes = "東京".toByteArray(StandardCharsets.UTF_8)
        assertEquals("東京", LibraryTextEncoding.decode(bytes, "not-a-real-charset"))
    }
}


class CueTrackBoundaryTest {
    @Test
    fun cueBoundariesRoundStartUpAndEndDown() {
        val track = CueTrack(1, "Track", null, "disc.flac", 1L, 1L)
        assertEquals(14L, track.startTimeMs)
        assertEquals(13L, track.endTimeMs)
    }

    @Test
    fun cueBoundaryConversionSaturatesInsteadOfOverflowing() {
        val track = CueTrack(1, "Track", null, "disc.flac", Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, track.startTimeMs)
        assertEquals(Long.MAX_VALUE, track.endTimeMs)
    }

    @Test
    fun negativeCueBoundaryIsClampedToZero() {
        val track = CueTrack(1, "Track", null, "disc.flac", -1L, -1L)
        assertEquals(0L, track.startTimeMs)
        assertEquals(0L, track.endTimeMs)
    }
}
