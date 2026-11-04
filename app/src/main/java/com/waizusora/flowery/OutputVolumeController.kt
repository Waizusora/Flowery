package com.waizusora.flowery

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * Routes ordinary outputs through Android's music stream volume.
 * USB DAC software gain is kept separately by stable device identity.
 */
class OutputVolumeController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferences = appContext.getSharedPreferences("flowery_dac_volume", Context.MODE_PRIVATE)

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

    fun usbVolume(deviceKey: String): Float =
        preferences.getFloat(key(deviceKey), 0.20f).coerceIn(0f, 1f)

    fun setUsbVolume(deviceKey: String, fraction: Float) {
        preferences.edit().putFloat(key(deviceKey), fraction.coerceIn(0f, 1f)).apply()
    }

    private fun key(deviceKey: String) = "usb_volume_" + deviceKey.hashCode().toUInt().toString(16)
}
