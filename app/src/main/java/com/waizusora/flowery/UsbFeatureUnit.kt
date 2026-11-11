package com.waizusora.flowery

/** Descriptor-backed master-channel volume control exposed by a UAC Feature Unit. */
internal data class UsbFeatureUnit(
    val controlInterface: Int,
    val unitId: Int,
    val protocol: Int
)

/** Android adapter for the Rust-owned UAC Feature Unit descriptor parser. */
internal object UsbFeatureUnitParser {
    fun find(raw: ByteArray): UsbFeatureUnit? {
        val encoded = NativeAudio.nativeFindUsbFeatureUnitVolumeControl(raw)
        if (encoded == "null") return null
        val value = org.json.JSONObject(encoded)
        return UsbFeatureUnit(
            controlInterface = value.getInt("controlInterface"),
            unitId = value.getInt("unitId"),
            protocol = value.getInt("protocol")
        )
    }
}


/** Parses the signed 1/256 dB ranges returned by UAC2 Feature Unit GET_RANGE. */
internal object UsbFeatureUnitVolumeRange {
    private const val UAC2_SUBRANGE_BYTES = 6

    fun parseUac2(response: ByteArray, transferred: Int): Pair<Int, Int>? {
        if (transferred < 2 || transferred > response.size) return null
        val buffer = java.nio.ByteBuffer.wrap(response, 0, transferred)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = buffer.short.toInt() and 0xffff
        if (count <= 0 || transferred < 2 + count * UAC2_SUBRANGE_BYTES) return null

        var minimum = Int.MAX_VALUE
        var maximum = Int.MIN_VALUE
        repeat(count) {
            val rangeMinimum = buffer.short.toInt()
            val rangeMaximum = buffer.short.toInt()
            val resolution = buffer.short.toInt()
            if (rangeMaximum >= rangeMinimum && resolution >= 0) {
                minimum = minOf(minimum, rangeMinimum)
                maximum = maxOf(maximum, rangeMaximum)
            }
        }
        return if (minimum < maximum) minimum to maximum else null
    }

    fun parseUac1(minimum: ByteArray, maximum: ByteArray): Pair<Int, Int>? {
        if (minimum.size < 2 || maximum.size < 2) return null
        val min = java.nio.ByteBuffer.wrap(minimum).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        val max = java.nio.ByteBuffer.wrap(maximum).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        return if (max > min) min to max else null
    }
}
