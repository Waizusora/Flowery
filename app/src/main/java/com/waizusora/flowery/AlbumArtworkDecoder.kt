package com.waizusora.flowery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Decodes embedded artwork to a bounded bitmap for MediaSession metadata.
 *
 * Decoding is expected to run on the playback service's metadata I/O executor,
 * never on the audio rendering thread.
 */
internal object AlbumArtworkDecoder {
    private const val MAX_ENCODED_BYTES = 16 * 1024 * 1024
    private const val MAX_SOURCE_DIMENSION = 12_000
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    private const val TARGET_SIZE_PX = 320
    private const val CACHE_BUDGET_KIB = 2 * 1024

    data class DecodedArtwork(
        val cacheKey: String,
        val bitmap: Bitmap,
    )

    private val cache = object : LruCache<String, Bitmap>(CACHE_BUDGET_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    @Synchronized
    fun decode(encoded: ByteArray): DecodedArtwork? {
        if (encoded.isEmpty() || encoded.size > MAX_ENCODED_BYTES) return null
        val key = MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { byte -> "%02x".format(byte) }

        cache.get(key)?.let { return DecodedArtwork(key, it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ByteArrayInputStream(encoded).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 ||
            width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION ||
            width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
        ) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inDither = false
        }
        val decoded = ByteArrayInputStream(encoded).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val largestDimension = maxOf(decoded.width, decoded.height)
        val output = if (largestDimension > TARGET_SIZE_PX) {
            val scale = TARGET_SIZE_PX.toFloat() / largestDimension.toFloat()
            val targetWidth = (decoded.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        cache.put(key, output)
        return DecodedArtwork(key, output)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= TARGET_SIZE_PX) {
            sample *= 2
        }
        return sample
    }
}
