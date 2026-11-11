package com.waizusora.flowery

/** A successful SET_CUR is not enough: require a readable GET_CUR confirmation. */
internal object UsbHardwareVolumeReadback {
    fun confirmedFraction(
        setAccepted: Boolean,
        readBack: Float?,
        maximumSafeFraction: Float = 0.02f,
    ): Float? {
        if (!setAccepted || readBack == null || !readBack.isFinite()) return null
        val normalized = readBack.coerceIn(0f, 1f)
        return normalized.takeIf { it <= maximumSafeFraction }
    }
}

internal data class UsbPlaybackVolumeState(
    val softwareVolume: Float,
    val hardwareVolumeAvailable: Boolean,
    val softwareGainEnabled: Boolean,
    val bitPerfectActive: Boolean,
)

/**
 * Applies the per-DAC hardware/software volume policy before the PCM producer is opened.
 *
 * A DAC with a Feature Unit must accept SET_CUR(minimum) and confirm a near-minimum
 * GET_CUR before playback is allowed. A DAC without a Feature Unit starts at a
 * deliberately conservative software gain instead of inheriting Android's media volume.
 */
internal class UsbPlaybackVolumePolicy(
    private val preferences: OutputVolumeController,
) {
    companion object {
        // -40 dB amplitude attenuation. The user can deliberately raise this in the player.
        const val SAFE_SOFTWARE_STARTUP_VOLUME = 0.01f
        private const val SAFE_HARDWARE_READBACK_FRACTION = 0.02f
    }

    fun attach(deviceKey: String, session: UsbDacSession): UsbPlaybackVolumeState {
        preferences.usbProfile(deviceKey, session.hardwareVolumeAvailable)
        if (session.hardwareVolumeAvailable) {
            val setAccepted = runCatching {
                session.setHardwareVolumeFraction(0f)
            }.getOrDefault(false)
            val readBack = if (setAccepted) {
                runCatching { session.readHardwareVolumeFraction() }.getOrNull()
            } else null
            val confirmed = UsbHardwareVolumeReadback.confirmedFraction(
                setAccepted = setAccepted,
                readBack = readBack,
                maximumSafeFraction = SAFE_HARDWARE_READBACK_FRACTION,
            )
            checkNotNull(confirmed) {
                "USB DAC startup volume could not be set to its minimum and confirmed by GET_CUR; playback is blocked for safety."
            }
            preferences.setUsbVolume(deviceKey, confirmed)
            return UsbPlaybackVolumeState(
                softwareVolume = confirmed,
                hardwareVolumeAvailable = true,
                softwareGainEnabled = false,
                bitPerfectActive = true,
            )
        }

        // Never inherit a potentially high Android media volume or a prior per-DAC value.
        preferences.setUsbVolume(deviceKey, SAFE_SOFTWARE_STARTUP_VOLUME)
        return UsbPlaybackVolumeState(
            softwareVolume = SAFE_SOFTWARE_STARTUP_VOLUME,
            hardwareVolumeAvailable = false,
            softwareGainEnabled = true,
            bitPerfectActive = false,
        )
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
        if (session?.hardwareVolumeAvailable == true) {
            val setAccepted = runCatching {
                session.setHardwareVolumeFraction(normalized)
            }.getOrDefault(false)
            val confirmed = if (setAccepted) {
                runCatching { session.readHardwareVolumeFraction() }.getOrNull()
            } else null
            val applied = confirmed?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (applied != null) {
                preferences.setUsbVolume(deviceKey, applied)
                return UsbPlaybackVolumeState(
                    softwareVolume = applied,
                    hardwareVolumeAvailable = true,
                    softwareGainEnabled = false,
                    bitPerfectActive = true,
                )
            }
        }
        // A Feature Unit that is merely advertised, but cannot confirm GET_CUR,
        // must not be treated as a working hardware volume control. Fall back to
        // the software gain path even if this session initially read hardware volume.
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
