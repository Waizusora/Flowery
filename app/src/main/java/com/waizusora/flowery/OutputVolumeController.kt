package com.waizusora.flowery

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** The three volume policies used by Flowery's Android and USB output routes. */
enum class OutputVolumeProfile {
    SYSTEM_STREAM,
    USB_HARDWARE_SYNCHRONIZED,
    USB_SOFTWARE
}

/** Routes system outputs through Android and keeps USB DAC software gain per device. */
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

    fun usbProfile(deviceKey: String, hardwareVolumeAvailable: Boolean): OutputVolumeProfile {
        val profile = if (hardwareVolumeAvailable) {
            OutputVolumeProfile.USB_HARDWARE_SYNCHRONIZED
        } else {
            OutputVolumeProfile.USB_SOFTWARE
        }
        preferences.edit().putString(profileKey(deviceKey), profile.name).apply()
        return profile
    }

    fun usbProfile(deviceKey: String): OutputVolumeProfile =
        runCatching {
            OutputVolumeProfile.valueOf(
                preferences.getString(profileKey(deviceKey), OutputVolumeProfile.USB_SOFTWARE.name)
                    ?: OutputVolumeProfile.USB_SOFTWARE.name
            )
        }.getOrDefault(OutputVolumeProfile.USB_SOFTWARE)

    /** New USB DACs without hardware volume controls start at a conservative 20%. */
    fun usbVolume(deviceKey: String): Float =
        preferences.getFloat(key(deviceKey), 0.20f).coerceIn(0f, 1f)

    fun setUsbVolume(deviceKey: String, fraction: Float) {
        preferences.edit().putFloat(key(deviceKey), fraction.coerceIn(0f, 1f)).apply()
    }

    private fun key(deviceKey: String) = "usb_volume_" + deviceKey.hashCode().toUInt().toString(16)
    private fun profileKey(deviceKey: String) = "usb_profile_" + deviceKey.hashCode().toUInt().toString(16)
}
