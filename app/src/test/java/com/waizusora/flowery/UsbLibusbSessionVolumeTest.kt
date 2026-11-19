package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLibusbSessionVolumeTest {
    private class VolumeTransferFake : UsbControlTransfer {
        var currentDb256 = -10 * 256
        val rangeRequestLengths = mutableListOf<Int>()

        override fun transfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int,
            timeoutMs: Int
        ): Int {
            if (requestType == 0xA1 && request == 0x82) {
                rangeRequestLengths += length
                if (length < 8) return -1
                buffer[0] = 1
                buffer[1] = 0
                putS16(buffer, 2, -60 * 256)
                putS16(buffer, 4, 0)
                putS16(buffer, 6, 256)
                return 8
            }
            if (requestType == 0xA1 && request == 0x81) {
                putS16(buffer, 0, currentDb256)
                return 2
            }
            if (requestType == 0x21 && request == 0x01) {
                currentDb256 = readS16(buffer, 0)
                return 2
            }
            return -1
        }

        private fun putS16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
        }

        private fun readS16(bytes: ByteArray, offset: Int): Int {
            val raw = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)
            return if (raw and 0x8000 != 0) raw - 0x10000 else raw
        }
    }

    @Test
    fun readsUac2VolumeRangeWithOneCompleteTripletRequest() {
        val fake = VolumeTransferFake()
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(
            controlInterface = 0,
            unitId = 7,
            protocol = 0x20,
            readableChannels = listOf(1),
            writableChannels = listOf(1)
        )

        val result = session.readUacVolume(control)

        assertTrue(result.isSuccess)
        assertEquals(listOf(8), fake.rangeRequestLengths)
        assertEquals(-10 * 256, result.getOrThrow().currentDb256)
        assertEquals(-60 * 256, result.getOrThrow().minimumDb256)
        assertEquals(0, result.getOrThrow().maximumDb256)
        assertEquals(256, result.getOrThrow().resolutionDb256)
    }

    @Test
    fun writesAndVerifiesUac2VolumeAfterReadingRange() {
        val fake = VolumeTransferFake()
        val session = UsbLibusbSession(null, 1L, fake)
        val control = UacVolumeControl(
            controlInterface = 0,
            unitId = 7,
            protocol = 0x20,
            readableChannels = listOf(1),
            writableChannels = listOf(1)
        )

        val result = session.setUacVolume(control, 0.5f)

        assertTrue(result.isSuccess)
        assertEquals(-6 * 256, fake.currentDb256)
        assertEquals(listOf(8), fake.rangeRequestLengths)
        assertEquals(-6 * 256, result.getOrThrow().currentDb256)
    }
}
