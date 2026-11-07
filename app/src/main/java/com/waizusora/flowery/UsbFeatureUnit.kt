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
