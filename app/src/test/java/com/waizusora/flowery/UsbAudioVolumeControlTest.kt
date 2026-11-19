package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioVolumeControlTest {
    private class FakeVolumeControl : UsbControlTransfer {
        var currentByChannel = mutableMapOf(0 to -6_144, 1 to -6_144, 2 to -6_144)
        val writes = mutableListOf<Pair<Int, Int>>()
        var failVerify = false

        override fun transfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int,
            timeoutMs: Int
        ): Int {
            val channel = value and 0xff
            val selector = value ushr 8
            if (selector != 2 || index ushr 8 != 5) return -1
            fun put(value: Int) {
                buffer[0] = value.toByte()
                buffer[1] = (value ushr 8).toByte()
            }
            fun readSigned(): Int {
                val raw = (buffer[0].toInt() and 0xff) or ((buffer[1].toInt() and 0xff) shl 8)
                return if (raw and 0x8000 != 0) raw - 0x10000 else raw
            }
            if (requestType == 0xA1 && request in 0x82..0x84 && length == 2) {
                put(when (request) { 0x82 -> -24_576; 0x83 -> 0; else -> 256 })
                return 2
            }
            if (requestType == 0xA1 && request == 0x81 && length == 2) {
                if (failVerify) {
                    failVerify = false
                    put(currentByChannel.getValue(channel) - 256)
                } else put(currentByChannel.getValue(channel))
                return 2
            }
            if (requestType == 0x21 && request == 0x01 && length == 2) {
                val next = readSigned()
                currentByChannel[channel] = next
                writes += channel to next
                return 2
            }
            return -1
        }
    }

    @Test fun readsFeatureUnitCurrentAndAdvertisedRange() {
        val fake = FakeVolumeControl()
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(0, 5, 0x00, listOf(0, 1, 2), listOf(0, 1, 2))
        val result = session.readUacVolume(control).getOrThrow()
        assertEquals(-6_144, result.currentDb256)
        assertEquals(-24_576, result.minimumDb256)
        assertEquals(0, result.maximumDb256)
        assertTrue(result.writable)
    }

    @Test fun writesAndVerifiesAllWritableChannels() {
        val fake = FakeVolumeControl()
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(0, 5, 0x00, listOf(0, 1, 2), listOf(0, 1, 2))
        val result = session.setUacVolume(control, 0.5f).getOrThrow()
        assertEquals(3, fake.writes.size)
        assertTrue(fake.writes.all { it.second in -2_000..-1_000 })
        assertEquals(fake.currentByChannel.getValue(0), result.currentDb256)
        assertEquals(fake.currentByChannel.getValue(0), fake.currentByChannel.getValue(1))
        assertEquals(fake.currentByChannel.getValue(1), fake.currentByChannel.getValue(2))
    }

    @Test fun usesUac2GetRangeForHardwareVolume() {
        var current = -6_144
        var rangeRead = false
        val fake = object : UsbControlTransfer {
            override fun transfer(
                requestType: Int, request: Int, value: Int, index: Int,
                buffer: ByteArray, length: Int, timeoutMs: Int
            ): Int {
                // UAC2 RANGE (0x02) / CUR (0x01) — not UAC1 0x82 / 0x81.
                if (requestType == 0xA1 && request == 0x02 && length >= 8) {
                    rangeRead = true
                    buffer[0] = 1
                    buffer[1] = 0
                    val values = intArrayOf(-24_576, 0, 256)
                    values.forEachIndexed { item, number ->
                        val offset = 2 + item * 2
                        buffer[offset] = number.toByte()
                        buffer[offset + 1] = (number ushr 8).toByte()
                    }
                    return 8
                }
                if (requestType == 0xA1 && request == 0x01 && length == 2) {
                    buffer[0] = current.toByte()
                    buffer[1] = (current ushr 8).toByte()
                    return 2
                }
                if (requestType == 0x21 && request == 0x01 && length == 2) {
                    current = (buffer[0].toInt() and 0xff) or ((buffer[1].toInt() and 0xff) shl 8)
                    if (current and 0x8000 != 0) current -= 0x10000
                    return 2
                }
                return -1
            }
        }
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(2, 7, 0x20, listOf(0), listOf(0))
        val result = session.setUacVolume(control, 0.25f).getOrThrow()
        assertTrue(rangeRead)
        assertTrue(result.currentDb256 in -3_300..-2_900)
    }

    @Test fun readOnlyFeatureUnitRejectsVolumeWrites() {
        val fake = FakeVolumeControl()
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(0, 5, 0x20, listOf(0), emptyList())
        assertTrue(session.setUacVolume(control, 0.5f).isFailure)
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun restoresPreviouslyChangedChannelsWhenLaterChannelFails() {
        val fake = object : UsbControlTransfer {
            var current = mutableMapOf(0 to -6_144, 1 to -6_144)
            var setCount = 0
            override fun transfer(
                requestType: Int, request: Int, value: Int, index: Int,
                buffer: ByteArray, length: Int, timeoutMs: Int
            ): Int {
                val channel = value and 0xff
                fun put(v: Int) {
                    buffer[0] = v.toByte()
                    buffer[1] = (v ushr 8).toByte()
                }
                fun get(): Int {
                    val raw = (buffer[0].toInt() and 0xff) or ((buffer[1].toInt() and 0xff) shl 8)
                    return if (raw and 0x8000 != 0) raw - 0x10000 else raw
                }
                if (requestType == 0xA1 && request in 0x82..0x84) {
                    put(when (request) { 0x82 -> -24_576; 0x83 -> 0; else -> 256 }); return 2
                }
                if (requestType == 0xA1 && request == 0x81) { put(current.getValue(channel)); return 2 }
                if (requestType == 0x21 && request == 0x01) {
                    setCount++
                    if (setCount == 2) return -1 // fail the second channel write
                    current[channel] = get()
                    return 2
                }
                return -1
            }
        }
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(0, 5, 0x00, listOf(0, 1), listOf(0, 1))
        assertTrue(session.setUacVolume(control, 0.25f).isFailure)
        assertEquals(-6_144, fake.current.getValue(0))
        assertEquals(-6_144, fake.current.getValue(1))
    }
}
