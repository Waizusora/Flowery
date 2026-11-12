package com.waizusora.flowery

import org.json.JSONArray

data class LyricWord(val timeMs: Long, val text: String)
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

object LrcParser {
    /**
     * Parses timestamped LRC entries in the native Rust engine.
     * Kotlin converts the structured result into UI-facing lyric models.
     */
    fun parse(content: String): List<LyricLine> {
        return parseEncoded(NativeAudio.nativeParseLrc(content))
    }

    fun parsePlain(content: String): List<LyricLine> = parseEncoded(NativeAudio.nativeParsePlainLyrics(content))

    fun parseLyricsFile(content: String): List<LyricLine> = parseEncoded(NativeAudio.nativeParseLyricsFile(content))

    fun parseDownloaded(content: String, extension: String): List<LyricLine> = when (extension) {
        "txt" -> parsePlain(content)
        "lyricsfile.yaml" -> parseLyricsFile(content)
        else -> parse(content)
    }

    private fun parseEncoded(encoded: String): List<LyricLine> {
        val entries = JSONArray(encoded)
        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                add(
                    LyricLine(
                        timeMs = entry.getLong("timeMs"),
                        text = entry.getString("text"),
                        words = entry.optJSONArray("words")?.let { wordEntries ->
                            buildList(wordEntries.length()) {
                                for (wordIndex in 0 until wordEntries.length()) {
                                    val word = wordEntries.getJSONObject(wordIndex)
                                    add(LyricWord(word.getLong("timeMs"), word.getString("text")))
                                }
                            }
                        }.orEmpty()
                    )
                )
            }
        }
    }

    fun currentWordIndex(line: LyricLine, positionMs: Long): Int {
        if (line.words.isEmpty()) return -1
        val timestamps = LongArray(line.words.size) { index -> line.words[index].timeMs }
        return NativeAudio.nativeFindCurrentLyricWordIndex(timestamps, positionMs)
    }

    /**
     * Resolves the active line in Rust using a binary search over sorted timestamps.
     * The returned index preserves the last-entry behavior for duplicate timestamps.
     */
    fun currentLine(lines: List<LyricLine>, positionMs: Long): LyricLine? {
        if (lines.isEmpty()) return null
        val timestamps = LongArray(lines.size) { index -> lines[index].timeMs }
        val index = NativeAudio.nativeFindCurrentLyricIndex(timestamps, positionMs)
        return lines.getOrNull(index)
    }
}
