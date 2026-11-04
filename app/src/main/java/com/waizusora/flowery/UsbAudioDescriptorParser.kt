package com.waizusora.flowery

import java.util.Locale

/** Parses the standard USB and USB Audio Class descriptors exposed by Android. */
internal object UsbAudioDescriptorParser {
    private const val USB_DT_INTERFACE = 0x04
    private const val USB_DT_ENDPOINT = 0x05
    private const val USB_DT_CS_INTERFACE = 0x24
    private const val USB_CLASS_AUDIO = 0x01
    private const val USB_SUBCLASS_AUDIO_STREAMING = 0x02
    private const val UAC_FORMAT_TYPE = 0x02

    private data class StreamInterface(
        val number: Int,
        val alternate: Int,
        val protocol: Int,
        val endpoints: MutableList<String> = mutableListOf(),
        val channels: MutableSet<Int> = linkedSetOf(),
        val subslotBytes: MutableSet<Int> = linkedSetOf(),
        val bitResolutions: MutableSet<Int> = linkedSetOf(),
        val sampleRates: MutableSet<Int> = linkedSetOf()
    )

    fun report(raw: ByteArray, vid: Int, pid: Int, product: String?, manufacturer: String?): String {
        if (raw.isEmpty()) return "USB descriptors are unavailable. Android returned an empty descriptor block."
        val streams = mutableListOf<StreamInterface>()
        var current: StreamInterface? = null
        var offset = 0
        var malformed = false
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) { malformed = true; break }
            when (type) {
                USB_DT_INTERFACE -> if (length >= 9) {
                    val interfaceClass = raw[offset + 5].toInt() and 0xff
                    val subclass = raw[offset + 6].toInt() and 0xff
                    current = if (interfaceClass == USB_CLASS_AUDIO && subclass == USB_SUBCLASS_AUDIO_STREAMING) {
                        StreamInterface(
                            number = raw[offset + 2].toInt() and 0xff,
                            alternate = raw[offset + 3].toInt() and 0xff,
                            protocol = raw[offset + 7].toInt() and 0xff
                        ).also(streams::add)
                    } else null
                }
                USB_DT_ENDPOINT -> if (length >= 7 && current != null) {
                    val address = raw[offset + 2].toInt() and 0xff
                    val attributes = raw[offset + 3].toInt() and 0xff
                    val maxPacket = u16(raw, offset + 4) and 0x07ff
                    val transferType = attributes and 0x03
                    val direction = if ((address and 0x80) != 0) "IN" else "OUT"
                    val transfer = when (transferType) { 1 -> "isochronous"; 2 -> "bulk"; 3 -> "interrupt"; else -> "control" }
                    val sync = if (transferType == 1) when ((attributes shr 2) and 0x03) {
                        1 -> "asynchronous"; 2 -> "adaptive"; 3 -> "synchronous"; else -> "none"
                    } else "n/a"
                    current?.endpoints?.add(
                        "ep 0x${address.toString(16).uppercase(Locale.ROOT)} $direction, $transfer, max packet $maxPacket bytes, sync $sync"
                    )
                }
                USB_DT_CS_INTERFACE -> if (length >= 3 && current != null) {
                    val subtype = raw[offset + 2].toInt() and 0xff
                    if (subtype == UAC_FORMAT_TYPE && length >= 6) {
                        val formatType = raw[offset + 3].toInt() and 0xff
                        if (formatType == 1) {
                            if (current!!.protocol == 0x20) {
                                current!!.subslotBytes += raw[offset + 4].toInt() and 0xff
                                current!!.bitResolutions += raw[offset + 5].toInt() and 0xff
                            } else if (length >= 8) {
                                current!!.channels += raw[offset + 4].toInt() and 0xff
                                current!!.subslotBytes += raw[offset + 5].toInt() and 0xff
                                current!!.bitResolutions += raw[offset + 6].toInt() and 0xff
                                val frequencyCount = raw[offset + 7].toInt() and 0xff
                                if (frequencyCount != 0) {
                                    val available = (length - 8) / 3
                                    for (index in 0 until minOf(frequencyCount, available)) {
                                        val start = offset + 8 + index * 3
                                        val rate = (raw[start].toInt() and 0xff) or
                                            ((raw[start + 1].toInt() and 0xff) shl 8) or
                                            ((raw[start + 2].toInt() and 0xff) shl 16)
                                        if (rate > 0) current!!.sampleRates += rate
                                    }
                                }
                            }
                        }
                    }
                }
            }
            offset += length
        }

        val lines = mutableListOf<String>()
        lines += "Product: ${product?.takeIf { it.isNotBlank() } ?: "USB Audio Device"}"
        manufacturer?.takeIf { it.isNotBlank() }?.let { lines += "Manufacturer: $it" }
        lines += "VID: 0x${vid.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
        lines += "PID: 0x${pid.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
        lines += "USB descriptor bytes: ${raw.size}"
        if (malformed) lines += "Descriptor warning: malformed or truncated descriptor sequence."
        if (streams.isEmpty()) {
            lines += "USB Audio Streaming interfaces: not found in the raw descriptor block."
        } else {
            lines += "USB Audio Streaming alternate settings: ${streams.size}"
            streams.forEach { stream ->
                val protocol = when (stream.protocol) { 0x20 -> "UAC2"; 0x00 -> "UAC1"; else -> "protocol 0x${stream.protocol.toString(16)}" }
                lines += "Interface ${stream.number}, alternate ${stream.alternate} ($protocol)"
                if (stream.channels.isNotEmpty()) lines += "  Channels: ${stream.channels.sorted().joinToString()}"
                if (stream.subslotBytes.isNotEmpty()) lines += "  PCM subslot bytes: ${stream.subslotBytes.sorted().joinToString()}"
                if (stream.bitResolutions.isNotEmpty()) lines += "  PCM bit resolutions: ${stream.bitResolutions.sorted().joinToString()}"
                if (stream.sampleRates.isNotEmpty()) lines += "  Advertised sample rates: ${stream.sampleRates.sorted().joinToString()} Hz"
                else if (stream.protocol == 0x20) lines += "  Sample rates: UAC2 clock-source RANGE query required; not inferred from the model name."
                stream.endpoints.forEach { lines += "  $it" }
                if (stream.endpoints.none { it.contains("isochronous") }) lines += "  No isochronous endpoint found in this alternate setting."
            }
        }
        lines += "Note: descriptor inspection is not an active playback test and does not prove exclusive or bit-perfect output."
        return lines.joinToString("\n")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}