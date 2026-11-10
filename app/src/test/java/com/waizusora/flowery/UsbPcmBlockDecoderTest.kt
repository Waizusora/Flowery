package com.waizusora.flowery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPcmBlockDecoderTest {
    private fun block(
        endOfStreamFlag: Int = 0,
        epoch: Int = 2,
        frames: Int = 2,
        startFrames: Long = 128L,
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ByteArray {
        return ByteBuffer.allocate(17 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(endOfStreamFlag.toByte())
            .putInt(epoch)
            .putInt(frames)
            .putLong(startFrames)
            .put(payload)
            .array()
    }

    @Test
    fun decodesPcmDataBlockAndPreservesMetadata() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val decoded = UsbPcmBlockDecoder.decode(block(payload = payload))

        assertFalse(decoded.endOfStream)
        assertEquals(2, decoded.epoch)
        assertEquals(2, decoded.frames)
        assertEquals(128L, decoded.startFrames)
        assertArrayEquals(payload, decoded.pcm)
    }

    @Test
    fun decodesEmptyEndOfStreamMarker() {
        val decoded = UsbPcmBlockDecoder.decode(
            block(endOfStreamFlag = 1, frames = 0, payload = byteArrayOf())
        )

        assertTrue(decoded.endOfStream)
        assertEquals(0, decoded.frames)
        assertTrue(decoded.pcm.isEmpty())
    }

    @Test
    fun rejectsTruncatedHeader() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(ByteArray(16))
        }
        assertTrue(error.message.orEmpty().contains("truncated"))
    }

    @Test
    fun rejectsInvalidEndOfStreamFlagAndNegativeEpoch() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(block(endOfStreamFlag = 2))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(block(epoch = -1))
        }
    }

    @Test
    fun rejectsDataBlockWithoutPayloadAndInvalidEndMarkerPayload() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(block(payload = byteArrayOf()))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(
                block(endOfStreamFlag = 1, frames = 0, payload = byteArrayOf(1))
            )
        }
    }

    @Test
    fun rejectsNegativeFrameMetadata() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(block(frames = -1))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(block(startFrames = -1L))
        }
    }
}
