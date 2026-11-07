package com.waizusora.flowery

/**
 * Describes one descriptor-validated PCM negotiation attempt.
 *
 * Exact source-preserving attempts are tried first. Automatic fallback is
 * allowed to select only a descriptor-advertised rate and encoding.
 */
internal data class UsbPcmProfileAttempt(
    val bitResolution: Int,
    val allowSampleRateConversion: Boolean,
    val requiredSubslotBytes: Int?,
    val encoding: String,
    val outputEncoding: String,
    val sourcePreservingCandidate: Boolean,
)

internal data class UsbPcmRouteSelection(
    val device: UsbDacController.Device,
    val session: UsbDacSession,
    val outputEncoding: String,
    val nativeCandidate: Boolean,
    val ieeeFloatCandidate: Boolean,
)

/** Keeps DAC profile preference and fallback policy out of the playback coordinator. */
internal class UsbPcmRouteNegotiator(
    private val controller: UsbDacController,
) {
    fun open(
        devices: List<UsbDacController.Device>,
        decoderHandle: Long,
        sourceBits: Int,
        sourceRate: Int,
        channels: Int,
        ieeeFloatSource: Boolean,
        equalizerFlat: Boolean,
        onFallback: (Throwable) -> Unit = {},
    ): UsbPcmRouteSelection {
        val attempts = profileAttempts(ieeeFloatSource, sourceBits, equalizerFlat)
        var lastError: Throwable? = null
        for ((index, attempt) in attempts.withIndex()) {
            try {
                val (device, session) = controller.openFirstCompatiblePlaybackSession(
                    devices = devices,
                    bitResolution = attempt.bitResolution,
                    sampleRate = sourceRate,
                    allowSampleRateConversion = attempt.allowSampleRateConversion,
                    requiredSubslotBytes = attempt.requiredSubslotBytes,
                    channels = channels,
                    encoding = attempt.encoding,
                )
                return UsbPcmRouteSelection(
                    device = device,
                    session = session,
                    outputEncoding = attempt.outputEncoding,
                    nativeCandidate = attempt.sourcePreservingCandidate &&
                        attempt.encoding == "signed_integer",
                    ieeeFloatCandidate = attempt.sourcePreservingCandidate &&
                        attempt.encoding == "ieee_float32",
                )
            } catch (error: Throwable) {
                lastError = error
                if (index < attempts.lastIndex) onFallback(error)
            }
        }
        throw IllegalStateException(
            "No compatible USB DAC profile could be negotiated for the decoded source.",
            lastError,
        )
    }

    companion object {
        fun profileAttempts(
            ieeeFloatSource: Boolean,
            sourceBits: Int,
            equalizerFlat: Boolean,
        ): List<UsbPcmProfileAttempt> {
            val automaticInteger = UsbPcmProfileAttempt(
                bitResolution = 0,
                allowSampleRateConversion = true,
                requiredSubslotBytes = null,
                encoding = "signed_integer",
                outputEncoding = "signed_integer",
                sourcePreservingCandidate = false,
            )
            if (!equalizerFlat) return listOf(automaticInteger)

            if (ieeeFloatSource) {
                return listOf(
                    UsbPcmProfileAttempt(
                        bitResolution = 32,
                        allowSampleRateConversion = false,
                        requiredSubslotBytes = 4,
                        encoding = "ieee_float32",
                        outputEncoding = "ieee_float32",
                        sourcePreservingCandidate = true,
                    ),
                    automaticInteger,
                )
            }

            if (sourceBits !in 8..32) return listOf(automaticInteger)
            return listOf(
                UsbPcmProfileAttempt(
                    bitResolution = sourceBits,
                    allowSampleRateConversion = false,
                    requiredSubslotBytes = null,
                    encoding = "signed_integer",
                    outputEncoding = "signed_integer",
                    sourcePreservingCandidate = true,
                ),
                automaticInteger,
            )
        }
    }
}
