package com.waizusora.flowery

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Schedules at most one prepared next-track resource for a playback generation.
 *
 * Preparation runs off the playback/output thread. The slot remains the sole
 * owner until [take] transfers the resource to its consumer. Generation changes
 * invalidate published resources and make late results from cancelled work
 * self-dispose when they attempt to publish.
 *
 * This coordinator does not start, stop, or reconfigure an audio output.
 */
internal class PlaybackPrefetchCoordinator<T : AutoCloseable>(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FloweryPlaybackPrefetch").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val lock = Any()
    private val slot = PreparedPlaybackSlot<T>()
    private var generation = 0L
    private var requestSerial = 0L
    private var pending: Future<*>? = null
    private var closed = false

    fun beginGeneration(nextGeneration: Long) {
        require(nextGeneration > 0L) { "Playback generation must be positive." }
        synchronized(lock) {
            check(!closed) { "Prefetch coordinator is closed." }
            if (generation == nextGeneration) return
            generation = nextGeneration
            requestSerial += 1L
            pending?.cancel(true)
            pending = null
            slot.beginGeneration(nextGeneration)
        }
    }

    fun prefetch(targetGeneration: Long, key: String, prepare: () -> T): Boolean {
        require(key.isNotBlank()) { "Prefetch key must not be blank." }
        synchronized(lock) {
            check(!closed) { "Prefetch coordinator is closed." }
            if (targetGeneration != generation) return false
            pending?.cancel(true)
            requestSerial += 1L
            val request = requestSerial
            pending = executor.submit {
                val prepared = try {
                    prepare()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@submit
                } catch (_: Throwable) {
                    return@submit
                }
                val accepted = synchronized(lock) {
                    if (!closed && generation == targetGeneration && requestSerial == request) {
                        slot.publish(targetGeneration, key, prepared)
                    } else {
                        runCatching { prepared.close() }
                        false
                    }
                }
                // Accepted resources are now owned by the slot and will be closed
                // or transferred there. Superseded work is disposed above.
                if (!accepted) return@submit
            }
            return true
        }
    }

    fun take(targetGeneration: Long, key: String): T? = synchronized(lock) {
        if (closed || targetGeneration != generation) null
        else slot.take(targetGeneration, key)
    }

    fun invalidate() {
        synchronized(lock) {
            if (closed) return
            requestSerial += 1L
            pending?.cancel(true)
            pending = null
            slot.invalidate()
            generation = 0L
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            requestSerial += 1L
            pending?.cancel(true)
            pending = null
            slot.close()
            executor.shutdownNow()
        }
    }
}
