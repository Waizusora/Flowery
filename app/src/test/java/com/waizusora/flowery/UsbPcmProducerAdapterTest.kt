package com.waizusora.flowery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPcmBlockDecoderTest {
    @Test
    fun decodesBoundedPcmBlockEnvelope() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val encoded = ByteBuffer.allocate(17 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putInt(7)
            .putInt(2)
            .putLong(96L)
            .put(payload)
            .array()

        val block = UsbPcmBlockDecoder.decode(encoded)

        assertFalse(block.endOfStream)
        assertEquals(7, block.epoch)
        assertEquals(2, block.frames)
        assertEquals(96L, block.startFrames)
        assertArrayEquals(payload, block.pcm)
    }

    @Test
    fun acceptsExplicitEndOfStreamEnvelope() {
        val encoded = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .put(1)
            .putInt(3)
            .putInt(0)
            .putLong(192L)
            .array()

        val block = UsbPcmBlockDecoder.decode(encoded)

        assertTrue(block.endOfStream)
        assertEquals(0, block.frames)
        assertEquals(192L, block.startFrames)
        assertEquals(0, block.pcm.size)
    }

    @Test
    fun rejectsTruncatedOrEmptyNonTerminalBlocks() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(ByteArray(16))
        }
        val empty = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putInt(0)
            .putInt(0)
            .putLong(0L)
            .array()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UsbPcmBlockDecoder.decode(empty)
        }
    }
}
