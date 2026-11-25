package com.waizusora.flowery

/**
 * Owns one prepared decoder/session pair while a track is playing.
 *
 * A prepared source is transferable exactly once and only to the playback
 * generation and URI for which it was created. Replacing or invalidating a
 * slot closes its resources, preventing stale prefetch work from leaking SAF
 * descriptors or native handles.
 *
 * This class deliberately does not own or mutate an output stream.
 */
internal class PreparedPlaybackSlot<T : AutoCloseable> {
    private data class Entry<T>(
        val generation: Long,
        val key: String,
        val value: T,
    )

    private var generation: Long = 0L
    private var entry: Entry<T>? = null

    @Synchronized
    fun beginGeneration(nextGeneration: Long) {
        require(nextGeneration > 0L) { "Playback generation must be positive." }
        if (generation == nextGeneration) return
        generation = nextGeneration
        closeEntry()
    }

    @Synchronized
    fun publish(targetGeneration: Long, key: String, value: T): Boolean {
        require(key.isNotBlank()) { "Prepared playback key must not be blank." }
        if (targetGeneration != generation) {
            closeQuietly(value)
            return false
        }
        closeEntry()
        entry = Entry(targetGeneration, key, value)
        return true
    }

    @Synchronized
    fun take(targetGeneration: Long, key: String): T? {
        val current = entry ?: return null
        if (targetGeneration != generation || current.generation != targetGeneration || current.key != key) {
            return null
        }
        entry = null
        return current.value
    }

    @Synchronized
    fun clear() {
        closeEntry()
    }

    @Synchronized
    fun invalidate() {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        closeEntry()
    }

    @Synchronized
    fun close() {
        invalidate()
    }

    private fun closeEntry() {
        val old = entry
        entry = null
        old?.let { closeQuietly(it.value) }
    }

    private fun closeQuietly(value: T) {
        runCatching { value.close() }
    }
}
