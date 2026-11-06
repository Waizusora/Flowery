package com.waizusora.flowery

import org.json.JSONObject

/**
 * Descriptor-derived UAC1/UAC2 playback capability metadata.
 *
 * The profile is parsed by Rust and does not prove that an interface is claimed,
 * a stream is active, or the resulting output is bit-perfect.
 */
internal data class UsbDacPlaybackProfile(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val endpointAddress: Int,
    val maxPacketBytes: Int,
    val interval: Int,
    val synchronization: String,
    val protocol: Int,
    val sampleRates: List<Int>,
    val sampleRateRange: Pair<Int, Int>?
) {
    val endpointHex: String
        get() = "0x%02X".format(endpointAddress)

    companion object {
        fun fromNativeJson(value: JSONObject): UsbDacPlaybackProfile = UsbDacPlaybackProfile(
            interfaceNumber = value.getInt("interfaceNumber"),
            alternateSetting = value.getInt("alternateSetting"),
            channels = value.getInt("channels"),
            subslotBytes = value.getInt("subslotBytes"),
            bitResolution = value.getInt("bitResolution"),
            endpointAddress = value.getInt("endpointAddress"),
            maxPacketBytes = value.getInt("maxPacketBytes"),
            interval = value.getInt("interval"),
            synchronization = value.getString("synchronization"),
            protocol = value.optInt("protocol", 0x20),
            sampleRates = value.optJSONArray("sampleRates")?.let { rates ->
                (0 until rates.length()).map { rates.getInt(it) }
            } ?: emptyList(),
            sampleRateRange = value.optJSONArray("sampleRateRange")?.let { range ->
                if (range.length() >= 2) range.getInt(0) to range.getInt(1) else null
            }
        )
    }
}
