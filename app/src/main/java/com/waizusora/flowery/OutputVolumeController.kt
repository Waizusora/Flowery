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

    /**
     * Restores the last software level for this DAC. A DAC with no saved level
     * inherits Android's current media volume instead of receiving an arbitrary default.
     */
    fun usbVolume(deviceKey: String): Float {
        val preferenceKey = key(deviceKey)
        val restored = if (preferences.contains(preferenceKey)) {
            preferences.getFloat(preferenceKey, systemVolumeFraction())
        } else {
            systemVolumeFraction()
        }
        return restored.coerceIn(0f, 1f)
    }

    fun setUsbVolume(deviceKey: String, fraction: Float) {
        val normalized = fraction.coerceIn(0f, 1f)
        // Volume is a safety-critical setting for USB DACs without hardware controls.
        // Persist synchronously so a rapid track transition or service teardown cannot
        // leave the previous default value to be restored by the next USB session.
        check(preferences.edit().putFloat(key(deviceKey), normalized).commit()) {
            "Unable to persist USB DAC software volume."
        }
        check(usbVolume(deviceKey) == normalized) {
            "Persisted USB DAC software volume does not match the requested value."
        }
    }

    private fun key(deviceKey: String) = "usb_volume_" + deviceKey.hashCode().toUInt().toString(16)
    private fun profileKey(deviceKey: String) = "usb_profile_" + deviceKey.hashCode().toUInt().toString(16)
}
