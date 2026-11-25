package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedPlaybackResourcesTest {
    private fun create(
        events: MutableList<String>,
        closeSource: () -> Unit = { events += "source" },
        releaseSession: (Long) -> Unit = { events += "session:$it" },
        releaseDecoder: (Long) -> Unit = { events += "decoder:$it" },
    ) = PreparedPlaybackResources(
        decoderHandle = 11L,
        sessionHandle = 22L,
        route = 0,
        sourceFileDescriptor = 7,
        sourceSampleRateHz = 96_000,
        channels = 2,
        sourceValidBits = 24,
        sourceDataFormat = 1,
        closeSource = AutoCloseable(closeSource),
        releaseSession = releaseSession,
        releaseDecoder = releaseDecoder,
    )

    @Test
    fun closesSessionDecoderAndSourceInDependencyOrder() {
        val events = mutableListOf<String>()

        create(events).close()

        assertEquals(listOf("session:22", "decoder:11", "source"), events)
    }

    @Test
    fun closeIsIdempotent() {
        val events = mutableListOf<String>()
        val resources = create(events)

        resources.close()
        resources.close()

        assertEquals(listOf("session:22", "decoder:11", "source"), events)
    }

    @Test
    fun teardownContinuesWhenNativeReleaseFails() {
        val events = mutableListOf<String>()
        val resources = create(
            events,
            releaseSession = { events += "session:$it"; error("session close failed") },
            releaseDecoder = { events += "decoder:$it"; error("decoder close failed") },
        )

        resources.close()

        assertEquals(listOf("session:22", "decoder:11", "source"), events)
    }
}
