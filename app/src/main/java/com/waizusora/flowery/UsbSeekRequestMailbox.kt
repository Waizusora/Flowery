package com.waizusora.flowery

import java.util.concurrent.atomic.AtomicReference

/**
 * Coalesces pending seeks until the active playback writer can apply them.
 *
 * The writer consumes requests and owns the session-epoch transition and
 * transport flush, preventing UI-thread seeks from racing packet submission.
 */
internal class UsbSeekRequestMailbox {
    private val pendingPositionMs = AtomicReference<Long?>(null)

    fun request(positionMs: Long) {
        require(positionMs >= 0L) { "Seek position must not be negative" }
        pendingPositionMs.set(positionMs)
    }

    fun take(): Long? = pendingPositionMs.getAndSet(null)

    fun clear() {
        pendingPositionMs.set(null)
    }
}
