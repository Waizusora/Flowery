package com.waizusora.flowery

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Android STREAM_MUSIC volume adapter for all AAudio output targets. */
class OutputVolumeController(context: Context) {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun systemVolumeFraction(): Float {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
    }

    fun setSystemVolume(fraction: Float) {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val requested = (fraction.coerceIn(0f, 1f) * maximum).roundToInt()
        // Android applies its own safe-media-volume policy and warning UI.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0)
    }
}
