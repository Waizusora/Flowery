package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Owns the SAF file descriptor for the complete lifetime of a native decoder.
 *
 * The descriptor must remain open until the native decoder has stopped and
 * released its duplicated/retained file access. Callers should use this class
 * with try/finally or close it after native decoder teardown.
 */
internal class PlaybackSourceDescriptor private constructor(
    private val descriptor: ParcelFileDescriptor,
    val extension: String,
) : AutoCloseable {
    val fileDescriptor: Int get() = descriptor.fd

    override fun close() {
        descriptor.close()
    }

    companion object {
        fun open(
            context: Context,
            uri: Uri,
            resolveExtension: (Uri) -> String,
        ): PlaybackSourceDescriptor {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Unable to open the selected audio document")
            return try {
                PlaybackSourceDescriptor(descriptor, resolveExtension(uri))
            } catch (error: Throwable) {
                runCatching { descriptor.close() }
                throw error
            }
        }
    }
}
