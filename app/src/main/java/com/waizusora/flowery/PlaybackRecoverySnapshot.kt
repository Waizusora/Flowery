package com.waizusora.flowery

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable service snapshot. A restored session is always paused; persisted
 * wasPlaying is diagnostic only and is never permission to auto-start audio.
 */
internal data class PlaybackRecoverySnapshot(
    val queue: List<String>,
    val titles: List<String>,
    val artists: List<String>,
    val cueStartsMs: List<Long>,
    val cueEndsMs: List<Long>,
    val cuePlaylists: List<String>,
    val playlistNames: List<String>,
    val playlistOrders: List<Int>,
    val queueIndex: Int,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val callInterrupted: Boolean,
    val currentTitle: String,
    val currentArtist: String,
) {
    fun normalized(): PlaybackRecoverySnapshot {
        val size = queue.size
        return copy(
            titles = titles.take(size).let { it + List(size - it.size) { "" } },
            artists = artists.take(size).let { it + List(size - it.size) { "" } },
            cueStartsMs = cueStartsMs.take(size).map { it.coerceAtLeast(0L) }
                .let { it + List(size - it.size) { 0L } },
            cueEndsMs = cueEndsMs.take(size).mapIndexed { index, end ->
                end.coerceAtLeast(0L).takeIf { it == 0L || it > cueStartsMs.getOrElse(index) { 0L }.coerceAtLeast(0L) } ?: 0L
            }.let { it + List(size - it.size) { 0L } },
            cuePlaylists = cuePlaylists.take(size).let { it + List(size - it.size) { "" } },
            playlistNames = playlistNames.take(size).let { it + List(size - it.size) { "" } },
            playlistOrders = playlistOrders.take(size).let { it + List(size - it.size) { -1 } },
            queueIndex = if (size == 0) 0 else queueIndex.coerceIn(0, size - 1),
            repeatMode = repeatMode.coerceIn(0, 2),
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }

    fun toJson(): String {
        val value = normalized()
        return JSONObject().apply {
            put("version", VERSION)
            put("queue", JSONArray(value.queue))
            put("titles", JSONArray(value.titles))
            put("artists", JSONArray(value.artists))
            put("cueStartsMs", JSONArray(value.cueStartsMs))
            put("cueEndsMs", JSONArray(value.cueEndsMs))
            put("cuePlaylists", JSONArray(value.cuePlaylists))
            put("playlistNames", JSONArray(value.playlistNames))
            put("playlistOrders", JSONArray(value.playlistOrders))
            put("queueIndex", value.queueIndex)
            put("repeatMode", value.repeatMode)
            put("shuffleEnabled", value.shuffleEnabled)
            put("positionMs", value.positionMs)
            put("wasPlaying", value.wasPlaying)
            put("callInterrupted", value.callInterrupted)
            put("currentTitle", value.currentTitle)
            put("currentArtist", value.currentArtist)
        }.toString()
    }

    companion object {
        const val VERSION = 1
        private const val KEY = "playback_recovery_snapshot"

        fun fromJson(raw: String): PlaybackRecoverySnapshot? = runCatching {
            val json = JSONObject(raw)
            require(json.optInt("version", -1) == VERSION)
            val queue = json.stringList("queue")
            require(queue.isNotEmpty() && queue.all { it.isNotBlank() })
            PlaybackRecoverySnapshot(
                queue = queue,
                titles = json.stringList("titles"),
                artists = json.stringList("artists"),
                cueStartsMs = json.longList("cueStartsMs"),
                cueEndsMs = json.longList("cueEndsMs"),
                cuePlaylists = json.stringList("cuePlaylists"),
                playlistNames = json.stringList("playlistNames"),
                playlistOrders = json.intList("playlistOrders"),
                queueIndex = json.optInt("queueIndex", 0),
                repeatMode = json.optInt("repeatMode", 0),
                shuffleEnabled = json.optBoolean("shuffleEnabled", false),
                positionMs = json.optLong("positionMs", 0L),
                wasPlaying = json.optBoolean("wasPlaying", false),
                callInterrupted = json.optBoolean("callInterrupted", false),
                currentTitle = json.optString("currentTitle", "Flowery"),
                currentArtist = json.optString("currentArtist", "Local music"),
            ).normalized()
        }.getOrNull()

        fun load(preferences: SharedPreferences): PlaybackRecoverySnapshot? =
            preferences.getString(KEY, null)?.let(::fromJson)

        fun save(preferences: SharedPreferences, snapshot: PlaybackRecoverySnapshot) {
            preferences.edit().putString(KEY, snapshot.toJson()).apply()
        }

        fun clear(preferences: SharedPreferences) {
            preferences.edit().remove(KEY).apply()
        }

        private fun JSONObject.stringList(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { index -> array.optString(index, "") }
        }

        private fun JSONObject.longList(key: String): List<Long> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { index -> array.optLong(index, 0L) }
        }

        private fun JSONObject.intList(key: String): List<Int> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { index -> array.optInt(index, -1) }
        }
    }
}
