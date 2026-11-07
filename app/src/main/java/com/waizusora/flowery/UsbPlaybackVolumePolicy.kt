package com.waizusora.flowery

internal data class UsbPlaybackVolumeState(
    val softwareVolume: Float,
    val hardwareVolumeAvailable: Boolean,
    val softwareGainEnabled: Boolean,
    val bitPerfectActive: Boolean,
)

/**
 * Applies the per-DAC hardware/software volume policy outside the playback coordinator.
 *
 * Hardware volume is preferred when the active DAC exposes a working Feature Unit.
 * Software gain is enabled only when no readable hardware volume control is available.
 */
internal class UsbPlaybackVolumePolicy(
    private val preferences: OutputVolumeController,
) {
    fun attach(deviceKey: String, session: UsbDacSession): UsbPlaybackVolumeState {
        val hardwareVolume = session.readHardwareVolumeFraction()
        preferences.usbProfile(deviceKey, hardwareVolume != null)
        return if (hardwareVolume != null) {
            UsbPlaybackVolumeState(
                softwareVolume = hardwareVolume,
                hardwareVolumeAvailable = true,
                softwareGainEnabled = false,
                bitPerfectActive = true,
            )
        } else {
            UsbPlaybackVolumeState(
                softwareVolume = preferences.usbVolume(deviceKey),
                hardwareVolumeAvailable = false,
                softwareGainEnabled = true,
                bitPerfectActive = false,
            )
        }
    }

    /**
     * Returns null when volume cannot safely be changed without altering a
     * source-preserving route. A non-null result is the new active volume state.
     */
    fun setVolume(
        deviceKey: String,
        session: UsbDacSession?,
        fraction: Float,
        softwareGainEnabled: Boolean,
    ): UsbPlaybackVolumeState? {
        val normalized = fraction.coerceIn(0f, 1f)
        if (session?.hardwareVolumeAvailable == true &&
            session.setHardwareVolumeFraction(normalized)) {
            val applied = session.readHardwareVolumeFraction() ?: normalized
            preferences.setUsbVolume(deviceKey, applied)
            return UsbPlaybackVolumeState(
                softwareVolume = applied,
                hardwareVolumeAvailable = true,
                softwareGainEnabled = false,
                bitPerfectActive = true,
            )
        }
        if (!softwareGainEnabled) return null
        preferences.setUsbVolume(deviceKey, normalized)
        return UsbPlaybackVolumeState(
            softwareVolume = normalized,
            hardwareVolumeAvailable = false,
            softwareGainEnabled = true,
            bitPerfectActive = false,
        )
    }

    fun refreshHardwareVolume(session: UsbDacSession): Float? =
        if (session.hardwareVolumeAvailable) session.readHardwareVolumeFraction() else null
}
