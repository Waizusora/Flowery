package com.waizusora.flowery

/**
 * Tracks a retryable pipeline teardown.
 *
 * Once closing starts, playback operations are rejected. Each successful
 * resource-release step is recorded independently so a later retry does not
 * repeat destructive operations for resources that have already been released.
 * The lifecycle becomes terminal only after every owned resource has closed.
 */
internal class EncodedPipelineLifecycle {
    private var closeStarted = false
    private var closed = false
    private val completedCloseSteps = mutableSetOf<String>()

    @Synchronized
    fun beginClose(): Boolean {
        if (closed) return false
        closeStarted = true
        return true
    }

    @Synchronized
    fun runCloseStep(name: String, action: () -> Unit) {
        check(closeStarted) { "Pipeline close has not started" }
        require(name.isNotBlank()) { "Close step name must not be blank" }
        if (name in completedCloseSteps) return
        action()
        completedCloseSteps.add(name)
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
