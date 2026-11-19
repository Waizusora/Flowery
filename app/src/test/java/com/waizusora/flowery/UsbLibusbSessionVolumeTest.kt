package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLibusbSessionVolumeTest {
    /** Device that STALLs every GET_RANGE but honours GET_CUR and SET_CUR. */
    private class RangeRefusingFake(var supportsGetCur: Boolean = true) : UsbControlTransfer {
        var currentDb256 = -10 * 256
        val rangeRequestLengths = mutableListOf<Int>()
        val writes = mutableListOf<Int>()

        override fun transfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int,
            timeoutMs: Int
        ): Int {
            // UAC2 RANGE is bRequest 0x02 (not UAC1 GET_MIN 0x82).
            if (requestType == 0xA1 && request == 0x02) {
                rangeRequestLengths += length
                return -1
            }
            // UAC2 CUR is bRequest 0x01 (not UAC1 GET_CUR 0x81).
            if (requestType == 0xA1 && request == 0x01) {
                if (!supportsGetCur) return -1
                buffer[0] = currentDb256.toByte()
                buffer[1] = (currentDb256 ushr 8).toByte()
                return 2
            }
            if (requestType == 0x21 && request == 0x01) {
                val raw = (buffer[0].toInt() and 0xff) or ((buffer[1].toInt() and 0xff) shl 8)
                currentDb256 = if (raw and 0x8000 != 0) raw - 0x10000 else raw
                writes += currentDb256
                return 2
            }
            return -1
        }
    }

    private fun uac2Control() = UacVolumeControl(
        controlInterface = 0,
        unitId = 7,
        protocol = 0x20,
        readableChannels = listOf(1, 2),
        writableChannels = listOf(1, 2)
    )

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
            if (requestType == 0xA1 && request == 0x02) {
                rangeRequestLengths += length
                if (length < 8) return -1
                buffer[0] = 1
                buffer[1] = 0
                putS16(buffer, 2, -60 * 256)
                putS16(buffer, 4, 0)
                putS16(buffer, 6, 256)
                return 8
            }
            if (requestType == 0xA1 && request == 0x01) {
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

    @Test
    fun usesAttenuationOnlyRangeWhenGetRangeIsRefused() {
        val fake = RangeRefusingFake()
        val session = UsbLibusbSession(null, 1L, fake)

        val result = session.readUacVolume(uac2Control())

        assertTrue(result.isSuccess)
        assertEquals(-10 * 256, result.getOrThrow().currentDb256)
        assertEquals(-90 * 256, result.getOrThrow().minimumDb256)
        assertEquals(0, result.getOrThrow().maximumDb256)
    }

    @Test
    fun writesAndVerifiesVolumeWhenGetRangeIsRefused() {
        val fake = RangeRefusingFake()
        val session = UsbLibusbSession(null, 1L, fake)

        val result = session.setUacVolume(uac2Control(), 0.5f)

        assertTrue(result.isSuccess)
        // 20 * log10(0.5) dB in 1/256 dB units. The fake shares one value across
        // channels, so the second channel already reads back the target.
        assertEquals(listOf(-1541), fake.writes)
        assertEquals(-1541, result.getOrThrow().currentDb256)
    }

    @Test
    fun neverBoostsAboveUnityGainWhenGetRangeIsRefused() {
        val fake = RangeRefusingFake().apply { currentDb256 = -256 }
        val session = UsbLibusbSession(null, 1L, fake)

        val result = session.setUacVolume(uac2Control(), 1.0f)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().currentDb256)
    }

    @Test
    fun doesNotRepeatRefusedGetRangeOnSubsequentReads() {
        val fake = RangeRefusingFake()
        val session = UsbLibusbSession(null, 1L, fake)
        val control = uac2Control()

        session.readUacVolume(control)
        val afterFirst = fake.rangeRequestLengths.count { it == 8 }
        session.readUacVolume(control)
        session.readUacVolume(control)

        assertEquals(1, afterFirst)
        assertEquals(1, fake.rangeRequestLengths.count { it == 8 })
    }

    @Test
    fun failsWhenNeitherGetRangeNorGetCurIsAvailable() {
        val fake = RangeRefusingFake(supportsGetCur = false)
        val session = UsbLibusbSession(null, 1L, fake)

        assertTrue(session.readUacVolume(uac2Control()).isFailure)
        assertTrue(session.setUacVolume(uac2Control(), 0.5f).isFailure)
        assertTrue(fake.writes.isEmpty())
    }
}
