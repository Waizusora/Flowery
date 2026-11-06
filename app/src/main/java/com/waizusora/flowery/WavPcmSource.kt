package com.waizusora.flowery

import android.os.ParcelFileDescriptor
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Reads unmodified interleaved integer PCM from a little-endian RIFF/WAVE file.
 *
 * The reader intentionally accepts only stereo PCM16/24/32. It does not decode,
 * normalize, resample, change volume, or apply DSP to the sample payload.
 */
internal class WavPcmSource private constructor(
    private val input: ParcelFileDescriptor.AutoCloseInputStream,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    private val dataOffset: Long,
    private val dataBytes: Long
) : AutoCloseable {
    private val channel = input.channel
    private val bytesPerFrame = channels * (bitDepth / 8)
    val totalFrames: Long = dataBytes / bytesPerFrame
    private var framesRead = 0L
    private var lastGain = 1f

    init {
        channel.position(dataOffset)
    }

    fun readFrames(maxFrames: Int, gain: Float = 1f): ByteArray {
        require(maxFrames > 0)
        val remaining = totalFrames - framesRead
        if (remaining <= 0) return ByteArray(0)
        val requested = minOf(maxFrames.toLong(), remaining).toInt()
        val bytes = ByteArray(requested * bytesPerFrame)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) throw EOFException("WAV data chunk ended unexpectedly")
        }
        framesRead += requested
        applySoftwareGain(bytes, gain.coerceIn(0f, 1f))
        return bytes
    }

    /**
     * Applies a short per-block gain ramp to integer PCM. Unity gain leaves the
     * original bytes untouched; non-unity gain intentionally changes sample data.
     */
    private fun applySoftwareGain(bytes: ByteArray, targetGain: Float) {
        if (bytes.isEmpty()) {
            lastGain = targetGain
            return
        }
        if (targetGain == 1f && lastGain == 1f) return

        val sampleBytes = bitDepth / 8
        val sampleCount = bytes.size / sampleBytes
        val startGain = lastGain
        for (index in 0 until sampleCount) {
            val offset = index * sampleBytes
            val fraction = if (sampleCount <= 1) 1f else index.toFloat() / (sampleCount - 1)
            val gain = startGain + (targetGain - startGain) * fraction
            when (bitDepth) {
                16 -> {
                    var value = (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8)
                    if (value and 0x8000 != 0) value -= 0x1_0000
                    val scaled = (value * gain).roundToLong().coerceIn(-32_768L, 32_767L).toInt()
                    bytes[offset] = scaled.toByte()
                    bytes[offset + 1] = (scaled shr 8).toByte()
                }
                24 -> {
                    var value = (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16)
                    if (value and 0x800000 != 0) value = value or -0x1000000
                    val scaled = (value * gain).roundToLong()
                        .coerceIn(-8_388_608L, 8_388_607L).toInt()
                    bytes[offset] = scaled.toByte()
                    bytes[offset + 1] = (scaled shr 8).toByte()
                    bytes[offset + 2] = (scaled shr 16).toByte()
                }
                32 -> {
                    val value = (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                        (bytes[offset + 3].toInt() shl 24)
                    val scaled = (value.toDouble() * gain).roundToLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    bytes[offset] = scaled.toByte()
                    bytes[offset + 1] = (scaled shr 8).toByte()
                    bytes[offset + 2] = (scaled shr 16).toByte()
                    bytes[offset + 3] = (scaled shr 24).toByte()
                }
                else -> error("Unsupported WAV PCM bit depth: $bitDepth")
            }
        }
        lastGain = targetGain
    }

    fun seekToFrame(frame: Long): Long {
        val target = frame.coerceIn(0L, totalFrames)
        channel.position(dataOffset + target * bytesPerFrame)
        framesRead = target
        return target
    }

    override fun close() {
        input.close()
    }

    companion object {
        fun open(source: ParcelFileDescriptor): WavPcmSource {
            val duplicate = ParcelFileDescriptor.dup(source.fileDescriptor)
            val input = ParcelFileDescriptor.AutoCloseInputStream(duplicate)
            try {
                val header = ByteArray(12)
                readFully(input, header)
                require(header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                    header.copyOfRange(8, 12).decodeToString() == "WAVE") {
                    "USB bit-perfect mode currently accepts RIFF/WAVE PCM files only."
                }

                var formatCode = -1
                var channels = 0
                var sampleRate = 0
                var blockAlign = 0
                var bitDepth = 0
                var dataOffset = -1L
                var dataBytes = -1L

                while (dataOffset < 0L) {
                    val chunkHeader = ByteArray(8)
                    readFully(input, chunkHeader)
                    val chunkId = chunkHeader.copyOfRange(0, 4).decodeToString()
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
                    require(chunkSize <= Int.MAX_VALUE.toLong()) { "WAV chunk is too large for this reader." }

                    when (chunkId) {
                        "fmt " -> {
                            require(chunkSize >= 16L) { "WAV format chunk is incomplete." }
                            val formatBytes = ByteArray(minOf(chunkSize, 40L).toInt())
                            readFully(input, formatBytes)
                            val format = ByteBuffer.wrap(formatBytes).order(ByteOrder.LITTLE_ENDIAN)
                            formatCode = format.short.toInt() and 0xffff
                            channels = format.short.toInt() and 0xffff
                            sampleRate = format.int
                            format.int
                            blockAlign = format.short.toInt() and 0xffff
                            bitDepth = format.short.toInt() and 0xffff
                            skipFully(input, chunkSize - formatBytes.size)
                        }
                        "data" -> {
                            dataOffset = input.channel.position()
                            dataBytes = chunkSize
                            skipFully(input, chunkSize)
                        }
                        else -> skipFully(input, chunkSize)
                    }
                    if ((chunkSize and 1L) != 0L) skipFully(input, 1L)
                }

                require(formatCode == 1) {
                    "USB bit-perfect mode requires integer PCM WAV; compressed or floating-point WAV is not supported."
                }
                require(channels == 2) { "USB bit-perfect mode currently requires stereo audio." }
                require(bitDepth in setOf(16, 24, 32)) {
                    "USB bit-perfect mode supports PCM 16-bit, 24-bit, and 32-bit files."
                }
                require(sampleRate > 0 && blockAlign == channels * (bitDepth / 8)) {
                    "The WAV PCM format has an invalid sample rate or block alignment."
                }
                require(dataBytes >= 0 && dataBytes % blockAlign == 0L) {
                    "The WAV data chunk is not aligned to complete PCM frames."
                }
                return WavPcmSource(
                    input, sampleRate, bitDepth, channels,
                    dataOffset, dataBytes
                )
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }

        private fun readFully(input: ParcelFileDescriptor.AutoCloseInputStream, bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException("Unexpected end of WAV header")
                offset += count
            }
        }

        private fun skipFully(input: ParcelFileDescriptor.AutoCloseInputStream, count: Long) {
            require(count >= 0)
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) throw EOFException("Unexpected end of WAV chunk")
                    remaining--
                }
            }
        }
    }
}
