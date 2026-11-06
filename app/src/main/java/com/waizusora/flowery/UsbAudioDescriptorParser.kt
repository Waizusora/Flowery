package com.waizusora.flowery

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Android adapter for USB Audio Class descriptor data parsed by the Rust native engine. */
internal object UsbAudioDescriptorParser {
    fun clockSources(raw: ByteArray): List<Pair<Int, Int>> {
        val values = JSONArray(NativeAudio.nativeParseUsbDacClockSources(raw))
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            value.getInt("clockId") to value.getInt("controlInterface")
        }
    }

    fun playbackClockSources(raw: ByteArray, streamingInterface: Int): List<Pair<Int, Int>> {
        val values = JSONArray(NativeAudio.nativeParseUsbDacPlaybackClocks(raw, streamingInterface))
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            value.getInt("clockId") to value.getInt("controlInterface")
        }
    }

    /** A bounded raw trace is retained only for diagnostics when a clock link cannot be resolved. */
    fun playbackDescriptorTrace(raw: ByteArray, streamingInterface: Int): String {
        val lines = mutableListOf<String>()
        var currentInterface = -1
        var currentClass = -1
        var currentSubclass = -1
        var offset = 0
        while (offset + 2 <= raw.size && lines.size < 100) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) {
                lines += "offset=$offset malformed_length=$length remaining=${raw.size - offset}"
                break
            }
            if (type == 0x04 && length >= 9) {
                currentInterface = raw[offset + 2].toInt() and 0xff
                currentClass = raw[offset + 5].toInt() and 0xff
                currentSubclass = raw[offset + 6].toInt() and 0xff
                if (currentClass == 0x01) {
                    lines += "IF offset=$offset number=$currentInterface alt=${raw[offset + 3].toInt() and 0xff} subclass=$currentSubclass protocol=0x${(raw[offset + 7].toInt() and 0xff).toString(16)}"
                }
            } else if (currentClass == 0x01 && (type == 0x24 || type == 0x05)) {
                val bytes = (offset until minOf(offset + length, offset + 24))
                    .joinToString(" ") { raw[it].toUByte().toString(16).padStart(2, '0') }
                val scope = if (currentInterface == streamingInterface) "SELECTED_STREAM" else "AUDIO"
                lines += "$scope offset=$offset type=0x${type.toString(16)} subclass=$currentSubclass bytes=$bytes"
            }
            offset += length
        }
        if (lines.isEmpty()) return "No audio descriptors found in raw descriptor block (bytes=${raw.size})."
        if (offset < raw.size && lines.size >= 100) lines += "Trace truncated at 100 entries."
        return lines.joinToString("\n")
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
        val parsed = JSONObject(NativeAudio.nativeDescribeUsbAudioDescriptors(raw))
        val streams = parsed.optJSONArray("streams") ?: JSONArray()
        val lines = mutableListOf<String>()
        lines += "Product: ${product?.takeIf { it.isNotBlank() } ?: "USB Audio Device"}"
        manufacturer?.takeIf { it.isNotBlank() }?.let { lines += "Manufacturer: $it" }
        lines += "VID: 0x${vid.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
        lines += "PID: 0x${pid.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
        lines += "USB descriptor bytes: ${parsed.optInt("descriptorBytes", raw.size)}"
        if (parsed.optBoolean("malformed")) lines += "Descriptor warning: malformed or truncated descriptor sequence."
        if (streams.length() == 0) {
            lines += "USB Audio Streaming interfaces: not found in the raw descriptor block."
        } else {
            lines += "USB Audio Streaming alternate settings: ${streams.length()}"
            for (index in 0 until streams.length()) {
                val stream = streams.getJSONObject(index)
                val protocolValue = stream.optInt("protocol")
                val protocol = when (protocolValue) {
                    0x20 -> "UAC2"
                    0x00 -> "UAC1"
                    else -> "protocol 0x${protocolValue.toString(16)}"
                }
                lines += "Interface ${stream.optInt("interfaceNumber")}, alternate ${stream.optInt("alternateSetting")} ($protocol)"
                appendValues(lines, "Channels", stream.optJSONArray("channels"))
                appendValues(lines, "PCM subslot bytes", stream.optJSONArray("subslotBytes"))
                appendValues(lines, "PCM bit resolutions", stream.optJSONArray("bitResolutions"))
                appendValues(lines, "Advertised sample rates", stream.optJSONArray("sampleRates"), " Hz")
                val ranges = stream.optJSONArray("sampleRateRanges") ?: JSONArray()
                for (rangeIndex in 0 until ranges.length()) {
                    val range = ranges.getJSONArray(rangeIndex)
                    lines += "  Continuous sample-rate range: ${range.getLong(0)}-${range.getLong(1)} Hz"
                }
                if (protocolValue == 0x20 && clockRangeReports.isEmpty()) {
                    lines += "  Sample rates: no readable UAC2 clock RANGE result; no rates are inferred from the model name."
                }
                val endpoints = stream.optJSONArray("endpoints") ?: JSONArray()
                var hasIsochronous = false
                for (endpointIndex in 0 until endpoints.length()) {
                    val endpoint = endpoints.getJSONObject(endpointIndex)
                    val transfer = endpoint.optString("transfer")
                    if (transfer == "isochronous") hasIsochronous = true
                    lines += "  ep 0x${endpoint.optInt("address").toString(16).uppercase(Locale.ROOT)} " +
                        "${endpoint.optString("direction")}, $transfer, max packet ${endpoint.optInt("maxPacketBytes")} bytes, " +
                        "sync ${endpoint.optString("synchronization")}"
                }
                if (!hasIsochronous) lines += "  No isochronous endpoint found in this alternate setting."
            }
        }
        if (clockRangeReports.isNotEmpty()) {
            lines += "UAC2 clock-source GET_RANGE results (clock/interface association is reported as advertised):"
            clockRangeReports.forEach { lines += "  $it" }
        }
        lines += "Note: descriptor inspection is not an active playback test and does not prove exclusive or bit-perfect output."
        return lines.joinToString("\n")
    }

    private fun appendValues(lines: MutableList<String>, label: String, values: JSONArray?, suffix: String = "") {
        if (values == null || values.length() == 0) return
        val entries = (0 until values.length()).map { values.get(it).toString() }
        lines += "  $label: ${entries.joinToString()}$suffix"
    }
}
