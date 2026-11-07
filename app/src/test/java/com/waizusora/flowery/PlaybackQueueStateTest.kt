package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueStateTest {
    @Test
    fun actionValuesMatchTheNativeQueueContract() {
        assertEquals(0, PlaybackQueueState.Action.NEXT.nativeValue)
        assertEquals(1, PlaybackQueueState.Action.PREVIOUS.nativeValue)
        assertEquals(2, PlaybackQueueState.Action.COMPLETION.nativeValue)
    }
}
