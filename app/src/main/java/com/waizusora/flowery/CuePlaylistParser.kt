package com.waizusora.flowery

import java.io.BufferedReader
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Decodes text-based library metadata using a user-selected JVM charset.
 * UTF-8 is the safe default; malformed input is replaced rather than crashing a scan.
 */
object LibraryTextEncoding {
    fun available(): List<Charset> = Charset.availableCharsets().values
        .sortedBy { it.name().lowercase() }

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
    /** CUE time is expressed in 75 CD frames per second. */
    val startTimeMs: Long get() = startFrame * 1000L / 75L
    val endTimeMs: Long? get() = endFrame?.let { it * 1000L / 75L }
}

data class CueSheet(val title: String?, val performer: String?, val tracks: List<CueTrack>)

data class PlaylistEntry(val path: String, val title: String? = null)

object CuePlaylistParser {
    fun parseCue(text: String): CueSheet {
        var albumTitle: String? = null
        var albumPerformer: String? = null
        var currentFile: String? = null
        data class MutableTrack(
            val number: Int, var title: String? = null, var performer: String? = null,
            var fileName: String = "", var start: Long? = null
        )
        val parsed = mutableListOf<MutableTrack>()
        var current: MutableTrack? = null

        StringReader(text).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                val command = line.substringBefore(' ').uppercase()
                val value = line.substringAfter(' ', "").trim()
                when (command) {
                    "TITLE" -> {
                        val title = unquote(value)
                        if (current == null) albumTitle = title else current?.title = title
                    }
                    "PERFORMER" -> {
                        val performer = unquote(value)
                        if (current == null) albumPerformer = performer else current?.performer = performer
                    }
                    "FILE" -> {
                        val remainder = value.substringBeforeLast(' ', value)
                        currentFile = unquote(remainder)
                    }
                    "TRACK" -> {
                        val number = value.substringBefore(' ').toIntOrNull() ?: return@forEach
                        current = MutableTrack(number, fileName = currentFile.orEmpty())
                        parsed += current!!
                    }
                    "INDEX" -> if (value.startsWith("01 ")) {
                        current?.start = parseCueTime(value.substringAfter(' ').trim())
                    }
                }
            }
        }

        val valid = parsed.mapNotNull { item ->
            val start = item.start ?: return@mapNotNull null
            CueTrack(item.number, item.title ?: "Track ${item.number}",
                item.performer ?: albumPerformer, item.fileName, start, null)
        }
        val tracks = valid.mapIndexed { index, track ->
            val next = valid.getOrNull(index + 1)?.takeIf { it.fileName == track.fileName }
            track.copy(endFrame = next?.startFrame)
        }
        return CueSheet(albumTitle, albumPerformer, tracks)
    }

    fun parseM3u(text: String): List<PlaylistEntry> {
        var nextTitle: String? = null
        val entries = mutableListOf<PlaylistEntry>()
        text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", true) -> nextTitle = line.substringAfter(',', "").trim().ifBlank { null }
                line.startsWith("#") -> Unit
                else -> {
                    entries += PlaylistEntry(line, nextTitle)
                    nextTitle = null
                }
            }
        }
        return entries
    }

    fun parsePls(text: String): List<PlaylistEntry> {
        val values = sortedMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        text.lineSequence().map { it.trim() }.forEach { line ->
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("[")) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().removeSurrounding("\"")
            val index = key.takeLastWhile { it.isDigit() }.toIntOrNull() ?: return@forEach
            when {
                key.startsWith("File", true) -> values[index] = value
                key.startsWith("Title", true) -> titles[index] = value
            }
        }
        return values.map { (index, path) -> PlaylistEntry(path, titles[index]) }
    }

    private fun parseCueTime(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        if (seconds !in 0..59 || frames !in 0..74) return null
        return (minutes * 60L + seconds) * 75L + frames
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")
}
