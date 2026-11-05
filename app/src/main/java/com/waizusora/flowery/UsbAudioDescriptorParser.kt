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
        val sampleRates: MutableSet<Int> = linkedSetOf(),
        val sampleRateRanges: MutableList<Pair<Int, Int>> = mutableListOf()
    )

    fun clockSources(raw: ByteArray): List<Pair<Int, Int>> {
        val sources = linkedSetOf<Pair<Int, Int>>()
        var currentControlInterface: Int? = null
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            if (type == USB_DT_INTERFACE && length >= 9) {
                val audioControl = (raw[offset + 5].toInt() and 0xff) == USB_CLASS_AUDIO &&
                    (raw[offset + 6].toInt() and 0xff) == 1
                currentControlInterface = if (audioControl) raw[offset + 2].toInt() and 0xff else null
            } else if (type == USB_DT_CS_INTERFACE && length >= 4 && currentControlInterface != null) {
                val subtype = raw[offset + 2].toInt() and 0xff
                if (subtype == 0x0A) {
                    val clockId = raw[offset + 3].toInt() and 0xff
                    sources += clockId to currentControlInterface!!
                }
            }
            offset += length
        }
        return sources.toList()
    }


    /**
     * Resolves the clock source linked by the selected UAC2 playback
     * Streaming interface through its terminal link and AudioControl entities.
     * Unrelated capture clocks are deliberately excluded.
     */
    fun playbackClockSources(raw: ByteArray, streamingInterface: Int): List<Pair<Int, Int>> {
        var currentInterface = -1
        var currentSubclass = -1
        val terminalClockIds = mutableMapOf<Int, Int>()
        val clockSourceIds = linkedSetOf<Int>()
        val playbackTerminalLinks = linkedSetOf<Int>()
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            if (type == USB_DT_INTERFACE && length >= 9) {
                currentInterface = raw[offset + 2].toInt() and 0xff
                currentSubclass = raw[offset + 6].toInt() and 0xff
            } else if (type == USB_DT_CS_INTERFACE && length >= 4) {
                val subtype = raw[offset + 2].toInt() and 0xff
                if (currentSubclass == 1) {
                    when (subtype) {
                        0x02 -> if (length >= 8) {
                            val terminalId = raw[offset + 3].toInt() and 0xff
                            val clockId = raw[offset + 7].toInt() and 0xff
                            terminalClockIds[terminalId] = clockId
                        }
                        0x03 -> if (length >= 9) {
                            val terminalId = raw[offset + 3].toInt() and 0xff
                            val clockId = raw[offset + 8].toInt() and 0xff
                            if (clockId != 0) terminalClockIds[terminalId] = clockId
                        }
                        0x0A -> clockSourceIds += raw[offset + 3].toInt() and 0xff
                    }
                } else if (currentInterface == streamingInterface &&
                    currentSubclass == USB_SUBCLASS_AUDIO_STREAMING &&
                    subtype == 0x01 && length >= 4
                ) {
                    playbackTerminalLinks += raw[offset + 3].toInt() and 0xff
                }
            }
            offset += length
        }
        return playbackTerminalLinks.mapNotNull { terminalId ->
            val clockId = terminalClockIds[terminalId]
            if (clockId != null && clockId in clockSourceIds) clockId to 0 else null
        }.distinct()
            .map { (clockId, _) ->
                var controlInterface = -1
                var scan = 0
                while (scan + 9 <= raw.size) {
                    val descriptorLength = raw[scan].toInt() and 0xff
                    if (descriptorLength < 9 || scan + descriptorLength > raw.size) break
                    if ((raw[scan + 1].toInt() and 0xff) == USB_DT_INTERFACE &&
                        (raw[scan + 5].toInt() and 0xff) == USB_CLASS_AUDIO &&
                        (raw[scan + 6].toInt() and 0xff) == 1
                    ) {
                        controlInterface = raw[scan + 2].toInt() and 0xff
                        var entityOffset = scan + descriptorLength
                        while (entityOffset + 4 <= raw.size) {
                            val entityLength = raw[entityOffset].toInt() and 0xff
                            if (entityLength < 2 || entityOffset + entityLength > raw.size) break
                            if ((raw[entityOffset + 1].toInt() and 0xff) == USB_DT_INTERFACE) break
                            if ((raw[entityOffset + 1].toInt() and 0xff) == USB_DT_CS_INTERFACE &&
                                entityLength >= 4 &&
                                (raw[entityOffset + 2].toInt() and 0xff) == 0x0A &&
                                (raw[entityOffset + 3].toInt() and 0xff) == clockId
                            ) break
                            entityOffset += entityLength
                        }
                        if (entityOffset + 4 <= raw.size &&
                            (raw[entityOffset + 1].toInt() and 0xff) == USB_DT_CS_INTERFACE &&
                            (raw[entityOffset + 2].toInt() and 0xff) == 0x0A &&
                            (raw[entityOffset + 3].toInt() and 0xff) == clockId
                        ) break
                    }
                    scan += descriptorLength
                }
                clockId to controlInterface
            }
            .filter { it.second >= 0 }
    }

    fun report(
        raw: ByteArray,
        vid: Int,
        pid: Int,
        product: String?,
        manufacturer: String?,
        clockRangeReports: List<String> = emptyList()
    ): String {
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
                    if (subtype == 0x01 && current!!.protocol == 0x20 && length >= 16) {
                        current!!.channels += raw[offset + 10].toInt() and 0xff
                    }
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
                                if (frequencyCount == 0 && length >= 14) {
                                    val minimum = u24(raw, offset + 8)
                                    val maximum = u24(raw, offset + 11)
                                    if (minimum > 0 && maximum >= minimum) {
                                        current!!.sampleRateRanges += minimum to maximum
                                    }
                                }
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
                stream.sampleRateRanges.forEach { (minimum, maximum) ->
                    lines += "  Continuous sample-rate range: $minimum-$maximum Hz"
                }
                if (stream.protocol == 0x20 && clockRangeReports.isEmpty()) {
                    lines += "  Sample rates: no readable UAC2 clock RANGE result; no rates are inferred from the model name."
                }
                stream.endpoints.forEach { lines += "  $it" }
                if (stream.endpoints.none { it.contains("isochronous") }) lines += "  No isochronous endpoint found in this alternate setting."
            }
        }
        if (clockRangeReports.isNotEmpty()) {
            lines += "UAC2 clock-source GET_RANGE results (clock/interface association is reported as advertised):"
            clockRangeReports.forEach { lines += "  $it" }
        }
        lines += "Note: descriptor inspection is not an active playback test and does not prove exclusive or bit-perfect output."
        return lines.joinToString("\n")
    }

    private fun u24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}