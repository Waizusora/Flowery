package com.waizusora.flowery

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Descriptor-backed master-channel volume control exposed by a UAC Feature Unit. */
internal data class UsbFeatureUnit(
    val controlInterface: Int,
    val unitId: Int,
    val protocol: Int
)

/** Finds a Feature Unit that advertises the master-channel Volume Control bit. */
internal object UsbFeatureUnitParser {
    fun find(raw: ByteArray): UsbFeatureUnit? {
        var offset = 0
        var interfaceNumber = -1
        var interfaceClass = -1
        var interfaceSubclass = -1
        var protocol = -1
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) return null
            when (raw[offset + 1].toInt() and 0xff) {
                0x04 -> if (length >= 9) {
                    interfaceNumber = raw[offset + 2].toInt() and 0xff
                    interfaceClass = raw[offset + 5].toInt() and 0xff
                    interfaceSubclass = raw[offset + 6].toInt() and 0xff
                    protocol = raw[offset + 7].toInt() and 0xff
                }
                0x24 -> if (interfaceClass == 1 && interfaceSubclass == 1 &&
                    (raw[offset + 2].toInt() and 0xff) == 0x06) {
                    val unitId = raw[offset + 3].toInt() and 0xff
                    val hasVolume = when (protocol) {
                        0x00 -> {
                            val controlSize = if (length >= 6) (raw[offset + 5].toInt() and 0xff) else 0
                            controlSize > 0 && length >= 7 + controlSize &&
                                ((raw[offset + 6].toInt() and 0xff) and 0x02) != 0
                        }
                        0x20 -> length >= 11 &&
                            (ByteBuffer.wrap(raw, offset + 5, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x02) != 0
                        else -> false
                    }
                    if (unitId > 0 && hasVolume) return UsbFeatureUnit(interfaceNumber, unitId, protocol)
                }
            }
            offset += length
        }
        return null
    }
}
