package com.waizusora.flowery

/** Conservative output level required before the first direct USB PCM transfer. */
internal object UsbStartupVolumePolicy {
    const val SAFE_STARTUP_DB = -40f
    const val SAFE_SOFTWARE_GAIN = 0.01f

    /** Returns a supported raw UAC volume value no louder than targetDb. */
    fun targetRaw(minimumRaw: Int, maximumRaw: Int, targetDb: Float = SAFE_STARTUP_DB): Int? {
        if (maximumRaw <= minimumRaw || !targetDb.isFinite()) return null
        val targetRaw = (targetDb * 256f).toInt()
        if (minimumRaw > targetRaw) return null
        return minOf(targetRaw, maximumRaw).takeIf { it >= minimumRaw }
    }

    fun confirmsHardwareStartup(setAccepted: Boolean, requestedRaw: Int?, readBackRaw: Int?): Boolean =
        setAccepted && requestedRaw != null && readBackRaw != null && readBackRaw <= requestedRaw
}

/** A successful SET_CUR is not enough: require a readable GET_CUR confirmation. */
internal object UsbHardwareVolumeReadback {
    fun confirmedFraction(setAccepted: Boolean, readBack: Float?): Float? =
        if (setAccepted) readBack?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) else null
}

internal data class UsbPlaybackVolumeState(
    val softwareVolume: Float,
    val hardwareVolumeAvailable: Boolean,
    val softwareGainEnabled: Boolean,
    val bitPerfectActive: Boolean,
)

/**
 * Applies the per-DAC hardware/software volume policy outside the playback coordinator.
 *
 * Startup is deliberately conservative: hardware attenuation must be confirmed at
 * <= -40 dB, or software PCM gain starts at -40 dB. A declared but unresponsive
 * Feature Unit fails closed because software gain cannot prove the DAC's output level.
 */
internal class UsbPlaybackVolumePolicy(
    private val preferences: OutputVolumeController,
) {
    fun attach(deviceKey: String, session: UsbDacSession): UsbPlaybackVolumeState {
        if (session.hardwareVolumeAvailable) {
            val hardwareVolume = session.armSafeStartupVolume()
                ?: throw IllegalStateException(
                    "USB DAC hardware volume could not confirm the safe -40 dB startup level. Playback was blocked."
                )
            preferences.usbProfile(deviceKey, true)
            return UsbPlaybackVolumeState(
                softwareVolume = hardwareVolume,
                hardwareVolumeAvailable = true,
                softwareGainEnabled = false,
                bitPerfectActive = true,
            )
        }

        preferences.usbProfile(deviceKey, false)
        return UsbPlaybackVolumeState(
            softwareVolume = UsbStartupVolumePolicy.SAFE_SOFTWARE_GAIN,
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
            val applied = UsbHardwareVolumeReadback.confirmedFraction(setAccepted, confirmed)
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
