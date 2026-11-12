package com.waizusora.flowery

import android.content.Context
import java.io.File

internal object LyricsRepository {
    private fun fileFor(context: Context, track: AudioTrackItem, extension: String): File {
        val directory = File(context.filesDir, "lyrics").apply { mkdirs() }
        val stableId = track.uri.toString().hashCode().toUInt().toString(16)
        return File(directory, "$stableId.$extension")
    }

    fun save(context: Context, track: AudioTrackItem, extension: String, content: String): List<LyricLine> {
        require(extension in setOf("lrc", "txt", "lyricsfile.yaml")) { "Unsupported lyrics format" }
        require(content.isNotBlank()) { "Downloaded lyrics are empty." }
        val parsed = runCatching { LrcParser.parseDownloaded(content, extension) }
            .getOrElse { error ->
                throw IllegalArgumentException("Downloaded lyrics could not be parsed as .$extension.", error)
            }
        require(parsed.isNotEmpty()) { "Downloaded lyrics contain no readable lyric lines." }
        val file = fileFor(context, track, extension)
        file.writeText(content, Charsets.UTF_8)
        return parsed
    }

    fun load(context: Context, track: AudioTrackItem?): List<LyricLine>? {
        track ?: return null
        val directory = File(context.filesDir, "lyrics")
        val stableId = track.uri.toString().hashCode().toUInt().toString(16)
        val file = listOf("lyricsfile.yaml", "lrc", "txt")
            .asSequence()
            .map { File(directory, "$stableId.$it") }
            .firstOrNull { it.isFile }
            ?: return null
        return runCatching {
            LrcParser.parseDownloaded(file.readText(Charsets.UTF_8), file.name.substringAfter("$stableId."))
        }.getOrNull()
    }
}
