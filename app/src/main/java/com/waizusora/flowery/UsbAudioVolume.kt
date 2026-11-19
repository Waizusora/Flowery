package com.waizusora.flowery

import kotlin.math.pow

internal data class UacVolumeRange(val minimum: Int, val maximum: Int, val resolution: Int)
internal data class UacVolumeSnapshot(
    val currentDb256: Int,
    val minimumDb256: Int,
    val maximumDb256: Int,
    val resolutionDb256: Int,
    val writable: Boolean,
    val channel: Int
) {
    fun asLinearFraction(): Float =
        (10.0.pow(currentDb256 / (256.0 * 20.0))).toFloat().coerceIn(0f, 1f)
}

/**
 * Descriptor-derived USB Audio Feature Unit volume capability.
 *
 * Only controls explicitly advertised by a Feature Unit are queried. No VID/PID
 * heuristics are used, and reserved UAC2 control encodings are never treated as writable.
 */
internal data class UacVolumeControl(
    val controlInterface: Int,
    val unitId: Int,
    val protocol: Int,
    val readableChannels: List<Int>,
    val writableChannels: List<Int>
) {
    val supportsRead: Boolean get() = readableChannels.isNotEmpty()
    val supportsWrite: Boolean get() = writableChannels.isNotEmpty()
}

internal object UsbAudioVolumeDescriptorParser {
    fun parse(descriptors: ByteArray): UacVolumeControl? {
        var offset = 0
        var interfaceNumber = -1
        var interfaceClass = -1
        var interfaceSubclass = -1
        var protocol = -1
        val featureUnits = mutableListOf<UacVolumeControl>()
        val playbackOutputSources = mutableSetOf<Int>()

        while (offset + 2 <= descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val type = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) break
            val d = descriptors.copyOfRange(offset, offset + length)

            when (type) {
                0x04 -> if (length >= 9) {
                    interfaceNumber = d[2].toInt() and 0xff
                    interfaceClass = d[5].toInt() and 0xff
                    interfaceSubclass = d[6].toInt() and 0xff
                    protocol = d[7].toInt() and 0xff
                }
                0x24 -> if (interfaceClass == 0x01 && interfaceSubclass == 0x01 && d.size >= 3) {
                    when (d[2].toInt() and 0xff) {
                        0x03 -> if (d.size >= 8) {
                            // Ignore the USB-streaming Output Terminal used by capture.
                            // A physical output terminal (speaker/headphone/etc.) is
                            // the playback path whose source may be a Feature Unit.
                            val terminalType = (d[4].toInt() and 0xff) or
                                ((d[5].toInt() and 0xff) shl 8)
                            val sourceId = d[7].toInt() and 0xff
                            if (terminalType != 0x0101 && sourceId != 0) {
                                playbackOutputSources += sourceId
                            }
                        }
                        0x06 -> {
                            val parsed = parseFeatureUnit(d, interfaceNumber, protocol)
                            if (parsed != null && parsed.supportsRead) featureUnits += parsed
                        }
                    }
                }
            }
            offset += length
        }

        // Associate the volume unit with a physical playback Output Terminal.
        // This prevents accidentally controlling a microphone/capture Feature Unit
        // on full-duplex USB audio devices.
        return featureUnits
            .filter { it.unitId in playbackOutputSources }
            .maxByOrNull { if (it.supportsWrite) 1 else 0 }
    }

    private fun parseFeatureUnit(
        descriptor: ByteArray,
        interfaceNumber: Int,
        protocol: Int
    ): UacVolumeControl? {
        if (interfaceNumber !in 0..255 || descriptor.size < 7) return null
        val unitId = descriptor[3].toInt() and 0xff
        if (unitId == 0) return null

        val readable = mutableListOf<Int>()
        val writable = mutableListOf<Int>()
        when (protocol) {
            0x00 -> {
                // UAC1: bControlSize followed by one bitmap per master/channel.
                val controlSize = descriptor[5].toInt() and 0xff
                if (controlSize !in 1..4) return null
                val controlBytes = descriptor.size - 7 // exclude header and iFeature
                if (controlBytes <= 0 || controlBytes % controlSize != 0) return null
                val channelCount = controlBytes / controlSize
                for (channel in 0 until channelCount) {
                    val value = readUnsigned(descriptor, 6 + channel * controlSize, controlSize)
                    if ((value and (1L shl 1)) != 0L) {
                        readable += channel
                        writable += channel
                    }
                }
            }
            0x20 -> {
                // UAC2: four-byte bmControls per master/channel; volume is bits 3:2.
                val controlBytes = descriptor.size - 6 // exclude header and iFeature
                if (controlBytes <= 0 || controlBytes % 4 != 0) return null
                val channelCount = controlBytes / 4
                for (channel in 0 until channelCount) {
                    val controls = readUnsigned(descriptor, 5 + channel * 4, 4).toInt()
                    when ((controls ushr 2) and 0x03) {
                        1 -> readable += channel // 01b: host read-only
                        3 -> { readable += channel; writable += channel } // 11b: host read/write
                        // 00b = absent; 10b = reserved and must not be used.
                    }
                }
            }
            else -> return null
        }
        if (readable.isEmpty()) return null
        return UacVolumeControl(interfaceNumber, unitId, protocol, readable, writable)
    }

    private fun readUnsigned(bytes: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (index in 0 until size) {
            value = value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }
        return value
    }
}
