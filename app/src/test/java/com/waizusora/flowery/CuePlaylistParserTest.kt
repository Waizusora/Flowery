package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

class CuePlaylistParserTest {
    @Test fun parsesCueMetadataAndTrackBoundaries() {
        val cue = """
            PERFORMER "Album Artist"
            TITLE "Album"
            FILE "disc one.flac" WAVE
              TRACK 01 AUDIO
                TITLE "First"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Second"
                INDEX 01 03:12:30
        """.trimIndent()
        val result = CuePlaylistParser.parseCue(cue)
        assertEquals("Album", result.title)
        assertEquals(2, result.tracks.size)
        assertEquals("First", result.tracks[0].title)
        assertEquals("disc one.flac", result.tracks[0].fileName)
        assertEquals(0L, result.tracks[0].startTimeMs)
        assertEquals(194_000L, result.tracks[0].endTimeMs)
        assertEquals(194_000L, result.tracks[1].startTimeMs)
    }

    @Test fun parsesExtendedM3uAndPls() {
        val m3u = CuePlaylistParser.parseM3u("#EXTM3U\n#EXTINF:120,Track Name\naudio.flac")
        assertEquals(PlaylistEntry("audio.flac", "Track Name"), m3u.single())
        val pls = CuePlaylistParser.parsePls("[playlist]\nFile2=b.flac\nTitle2=Second\nFile1=a.flac")
        assertEquals(listOf(PlaylistEntry("a.flac"), PlaylistEntry("b.flac", "Second")), pls)
    }

    @Test fun parsesBomPrefixedCueAndQuotedPlsPaths() {
        val cue = "\uFEFFTITLE \"Álbum\"\nFILE \"disc one.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"Café\"\nINDEX 01 00:00:00"
        assertEquals("Álbum", CuePlaylistParser.parseCue(cue).title)
        val pls = CuePlaylistParser.parsePls("[playlist]\nFile10=ten.flac\nFile2=two.flac\nTitle2=Second")
        assertEquals(listOf(PlaylistEntry("two.flac", "Second"), PlaylistEntry("ten.flac")), pls)
    }

    @Test fun parsesUtf8M3uBom() {
        val entries = CuePlaylistParser.parseM3u("\uFEFF#EXTM3U\n#EXTINF:-1,東京\naudio.flac")
        assertEquals(PlaylistEntry("audio.flac", "東京"), entries.single())
    }
    @Test fun decodesSelectedCharset() {
        val bytes = "Música".toByteArray(Charset.forName("windows-1252"))
        assertEquals("Música", LibraryTextEncoding.decode(bytes, "windows-1252"))
        assertNotNull(LibraryTextEncoding.available().firstOrNull { it.name() == StandardCharsets.UTF_8.name() })
    }
}
