package com.waizusora.flowery

import android.content.Context

internal data class ReplayGainPreferenceState(
    val mode: Int = 0,
    val trackPreampDb: Float = 0f,
    val albumPreampDb: Float = 0f,
    val preventClipping: Boolean = true,
)

internal object ReplayGainPreferences {
    private const val FILE = "flowery_replay_gain"
    private const val MODE = "mode"
    private const val TRACK_PREAMP = "track_preamp_db"
    private const val ALBUM_PREAMP = "album_preamp_db"
    private const val PREVENT_CLIPPING = "prevent_clipping"

    fun read(context: Context): ReplayGainPreferenceState {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ReplayGainPreferenceState(
            mode = prefs.getInt(MODE, 0).coerceIn(0, 2),
            trackPreampDb = prefs.getFloat(TRACK_PREAMP, 0f).takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f,
            albumPreampDb = prefs.getFloat(ALBUM_PREAMP, 0f).takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f,
            preventClipping = prefs.getBoolean(PREVENT_CLIPPING, true),
        )
    }

    fun saveAndApply(context: Context, state: ReplayGainPreferenceState): Boolean {
        val safe = state.copy(
            mode = state.mode.coerceIn(0, 2),
            trackPreampDb = state.trackPreampDb.takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f,
            albumPreampDb = state.albumPreampDb.takeIf { it.isFinite() }?.coerceIn(-12f, 12f) ?: 0f,
        )
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(MODE, safe.mode)
            .putFloat(TRACK_PREAMP, safe.trackPreampDb)
            .putFloat(ALBUM_PREAMP, safe.albumPreampDb)
            .putBoolean(PREVENT_CLIPPING, safe.preventClipping)
            .apply()
        return NativeAudio.nativeSetReplayGainSettings(
            safe.mode, safe.trackPreampDb, safe.albumPreampDb, safe.preventClipping
        ) == 0
    }

    fun apply(context: Context): Boolean = saveAndApply(context, read(context))
}
