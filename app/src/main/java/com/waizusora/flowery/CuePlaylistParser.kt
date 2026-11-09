package com.waizusora.flowery

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Decodes text-based library metadata using a user-selected JVM charset.
 * UTF-8 is the safe default; malformed input is replaced rather than crashing a scan.
 */
object LibraryTextEncoding {
    private val commonCharsets = listOf(
        StandardCharsets.UTF_8,
        StandardCharsets.UTF_16LE,
        StandardCharsets.UTF_16BE,
        Charset.forName("windows-1252"),
        Charset.forName("windows-1251"),
        Charset.forName("ISO-8859-1"),
        Charset.forName("ISO-8859-2"),
        Charset.forName("Shift_JIS"),
        Charset.forName("EUC-JP"),
        Charset.forName("GBK"),
        Charset.forName("GB18030"),
        Charset.forName("Big5"),
        Charset.forName("EUC-KR"),
        Charset.forName("KOI8-R")
    )

    fun available(): List<Charset> = commonCharsets

    fun decode(bytes: ByteArray, charsetName: String?): String {
        val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.UTF_8
        return charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}

data class CueTrack(
    val number: Int,
    val title: String,
    val performer: String?,
    val fileName: String,
    val startFrame: Long,
    val endFrame: Long?
) {
    /** CUE time is expressed in 75 CD frames per second. Start boundaries round up to avoid early playback. */
    val startTimeMs: Long get() {
        val frames = startFrame.coerceAtLeast(0L)
        return (frames / 75L) * 1000L + ((frames % 75L) * 1000L + 74L) / 75L
    }
    /** End boundaries round down so a segment cannot extend into the following track. */
    val endTimeMs: Long? get() = endFrame?.let { value ->
        val frames = value.coerceAtLeast(0L)
        (frames / 75L) * 1000L + (frames % 75L) * 1000L / 75L
    }
}

data class CueSheet(val title: String?, val performer: String?, val tracks: List<CueTrack>)
data class PlaylistEntry(val path: String, val title: String? = null)

object CuePlaylistParser {
    fun parseCue(text: String): CueSheet {
        val parsed = JSONObject(NativeAudio.nativeParseCue(text))
        val entries = parsed.getJSONArray("tracks")
        val tracks = buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    CueTrack(
                        number = item.getInt("number"),
                        title = item.getString("title"),
                        performer = item.optString("performer").takeIf { it.isNotEmpty() },
                        fileName = item.getString("fileName"),
                        startFrame = item.getLong("startFrame"),
                        endFrame = if (item.isNull("endFrame")) null else item.getLong("endFrame")
                    )
                )
            }
        }
        return CueSheet(
            title = parsed.optString("title").takeIf { it.isNotEmpty() },
            performer = parsed.optString("performer").takeIf { it.isNotEmpty() },
            tracks = tracks
        )
    }

    fun parseM3u(text: String): List<PlaylistEntry> =
        parseEntries(NativeAudio.nativeParseM3u(text))

    fun parsePls(text: String): List<PlaylistEntry> =
        parseEntries(NativeAudio.nativeParsePls(text))

    private fun parseEntries(encoded: String): List<PlaylistEntry> {
        val entries = JSONArray(encoded)
        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                add(
                    PlaylistEntry(
                        path = item.getString("path"),
                        title = item.optString("title").takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
    }
}
