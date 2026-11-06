package com.waizusora.flowery

import org.json.JSONArray

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    /**
     * Parses timestamped LRC entries in the native Rust engine.
     * Kotlin converts the structured result into UI-facing lyric models.
     */
    fun parse(content: String): List<LyricLine> {
        val encoded = NativeAudio.nativeParseLrc(content)
        val entries = JSONArray(encoded)
        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                add(
                    LyricLine(
                        timeMs = entry.getLong("timeMs"),
                        text = entry.getString("text")
                    )
                )
            }
        }
    }

    fun currentLine(lines: List<LyricLine>, positionMs: Long): LyricLine? =
        lines.lastOrNull { it.timeMs <= positionMs }
}
