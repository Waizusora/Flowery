package com.waizusora.flowery

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timestamp = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        content.lineSequence().forEach { line ->
            val matches = timestamp.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.replace(timestamp, "").trim()
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                lines += LyricLine(minutes * 60_000 + seconds * 1_000 + fraction, text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    fun currentLine(lines: List<LyricLine>, positionMs: Long): LyricLine? =
        lines.lastOrNull { it.timeMs <= positionMs }
}
