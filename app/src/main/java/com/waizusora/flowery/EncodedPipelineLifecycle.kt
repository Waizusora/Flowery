package com.waizusora.flowery

/**
 * Tracks a retryable pipeline teardown.
 *
 * Once closing starts, playback operations are rejected. A failed teardown may
 * call beginClose again so native resources whose release was not confirmed can
 * be retried. The lifecycle becomes terminal only after every owned resource
 * has closed successfully.
 */
internal class EncodedPipelineLifecycle {
    private var closeStarted = false
    private var closed = false

    @Synchronized
    fun beginClose(): Boolean {
        if (closed) return false
        closeStarted = true
        return true
    }

    @Synchronized
    fun completeClose() {
        check(closeStarted) { "Pipeline close has not started" }
        closed = true
    }

    @Synchronized
    fun checkOpen() {
        check(!closeStarted) { "Encoded playback pipeline is closing or closed" }
    }

    @Synchronized
    fun acceptsControlRequests(): Boolean = !closeStarted
}
