package com.waizusora.flowery

/**
 * A descriptor-derived USB Audio Class playback alternate setting.
 *
 * This is capability metadata only. It does not claim the interface or stream
 * isochronous packets and must never be used as proof of bit-perfect playback.
 */
internal data class UsbDacPlaybackProfile(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val endpointAddress: Int,
    val maxPacketBytes: Int,
    val synchronization: String
) {
    val endpointHex: String
        get() = "0x%02X".format(endpointAddress)

    companion object {
        private const val DESCRIPTOR_INTERFACE = 0x04
        private const val DESCRIPTOR_ENDPOINT = 0x05
        private const val DESCRIPTOR_CS_INTERFACE = 0x24
        private const val AUDIO_CLASS = 0x01
        private const val AUDIO_STREAMING_SUBCLASS = 0x02
        private const val UAC2_PROTOCOL = 0x20
        private const val TRANSFER_ISOCHRONOUS = 1

        /**
         * Finds stereo UAC2 OUT isochronous alternates matching the requested
         * PCM resolution. Sample rate is deliberately not inferred here:
         * UAC2 clock RANGE/SET_CUR negotiation is a separate operation.
         */
        fun findStereoUac2PlaybackProfiles(
            raw: ByteArray,
            requestedBitResolution: Int? = null
        ): List<UsbDacPlaybackProfile> {
            val profiles = mutableListOf<UsbDacPlaybackProfile>()
            var interfaceNumber = -1
            var alternateSetting = -1
            var protocol = -1
            var channels = 0
            var subslotBytes = 0
            var bitResolution = 0
            var endpointAddress = -1
            var maxPacketBytes = 0
            var synchronization = "unknown"

            fun finishAlternate() {
                if (protocol == UAC2_PROTOCOL &&
                    channels == 2 &&
                    subslotBytes > 0 &&
                    bitResolution > 0 &&
                    endpointAddress >= 0 &&
                    (endpointAddress and 0x80) == 0 &&
                    maxPacketBytes > 0 &&
                    (requestedBitResolution == null || bitResolution == requestedBitResolution)
                ) {
                    profiles += UsbDacPlaybackProfile(
                        interfaceNumber = interfaceNumber,
                        alternateSetting = alternateSetting,
                        channels = channels,
                        subslotBytes = subslotBytes,
                        bitResolution = bitResolution,
                        endpointAddress = endpointAddress,
                        maxPacketBytes = maxPacketBytes,
                        synchronization = synchronization
                    )
                }
            }

            var offset = 0
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) break

                when (type) {
                    DESCRIPTOR_INTERFACE -> {
                        finishAlternate()
                        if (length >= 9) {
                            interfaceNumber = raw[offset + 2].toInt() and 0xff
                            alternateSetting = raw[offset + 3].toInt() and 0xff
                            val interfaceClass = raw[offset + 5].toInt() and 0xff
                            val subclass = raw[offset + 6].toInt() and 0xff
                            protocol = raw[offset + 7].toInt() and 0xff
                            if (interfaceClass != AUDIO_CLASS || subclass != AUDIO_STREAMING_SUBCLASS) {
                                protocol = -1
                            }
                        } else {
                            protocol = -1
                        }
                        channels = 0
                        subslotBytes = 0
                        bitResolution = 0
                        endpointAddress = -1
                        maxPacketBytes = 0
                        synchronization = "unknown"
                    }
                    DESCRIPTOR_CS_INTERFACE -> if (protocol == UAC2_PROTOCOL && length >= 6) {
                        when (raw[offset + 2].toInt() and 0xff) {
                            0x01 -> if (length >= 11) {
                                channels = raw[offset + 10].toInt() and 0xff
                            }
                            0x02 -> if ((raw[offset + 3].toInt() and 0xff) == 1) {
                                subslotBytes = raw[offset + 4].toInt() and 0xff
                                bitResolution = raw[offset + 5].toInt() and 0xff
                            }
                        }
                    }
                    DESCRIPTOR_ENDPOINT -> if (protocol == UAC2_PROTOCOL && length >= 7) {
                        val address = raw[offset + 2].toInt() and 0xff
                        val attributes = raw[offset + 3].toInt() and 0xff
                        val transferType = attributes and 0x03
                        val packet = (raw[offset + 4].toInt() and 0xff) or
                            ((raw[offset + 5].toInt() and 0xff) shl 8)
                        if ((address and 0x80) == 0 && transferType == TRANSFER_ISOCHRONOUS) {
                            endpointAddress = address
                            maxPacketBytes = packet and 0x07ff
                            synchronization = when ((attributes shr 2) and 0x03) {
                                1 -> "asynchronous"
                                2 -> "adaptive"
                                3 -> "synchronous"
                                else -> "none"
                            }
                        }
                    }
                }
                offset += length
            }
            finishAlternate()
            return profiles.distinctBy { it.interfaceNumber to it.alternateSetting }
                .sortedWith(compareBy<UsbDacPlaybackProfile> { it.bitResolution }.thenBy { it.alternateSetting })
        }
    }
}
