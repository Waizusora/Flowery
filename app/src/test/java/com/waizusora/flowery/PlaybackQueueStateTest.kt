package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueStateTest {
    private fun state() = PlaybackQueueState { current, size, shuffle, repeat, action ->
        when (action) {
            0 -> if (current + 1 < size) current + 1 else if (repeat == 1) 0 else -1
            1 -> if (current > 0) current - 1 else if (repeat == 1) size - 1 else 0
            2 -> when {
                repeat == 2 -> -2
                current + 1 < size || repeat == 1 -> if (current + 1 < size) current + 1 else 0
                else -> -1
            }
            else -> -1
        }
    }

    @Test fun emptyQueueHasNoSelectionOrTransition() {
        val queue = state()
        queue.select(0, 0)
        assertEquals(-1, queue.currentIndex)
        assertEquals(-1, queue.transition(0, PlaybackQueueState.Action.NEXT))
    }

    @Test fun selectionIsClampedToQueueBounds() {
        val queue = state()
        queue.select(99, 3)
        assertEquals(2, queue.currentIndex)
        queue.select(-5, 3)
        assertEquals(0, queue.currentIndex)
    }

    @Test fun repeatModeCyclesAndShuffleToggles() {
        val queue = state()
        assertEquals(1, queue.cycleRepeatMode())
        assertEquals(2, queue.cycleRepeatMode())
        assertEquals(0, queue.cycleRepeatMode())
        assertTrue(queue.toggleShuffle())
        assertTrue(queue.shuffleEnabled)
    }

    @Test fun completionCanRequestReplayForRepeatOne() {
        val queue = state()
        queue.select(1, 3)
        queue.setRepeatMode(2)
        assertEquals(-2, queue.transition(3, PlaybackQueueState.Action.COMPLETION))
        assertEquals(2, queue.transition(3, PlaybackQueueState.Action.NEXT))
    }
    @Test fun forwardsQueueStateAndActionToNativeTransitionPolicy() {
        val arguments = mutableListOf<Any>()
        val queue = PlaybackQueueState { current, size, shuffle, repeat, action ->
            arguments += listOf(current, size, shuffle, repeat, action)
            2
        }
        queue.select(1, 4)
        queue.setShuffle(true)
        queue.setRepeatMode(1)

        assertEquals(2, queue.transition(4, PlaybackQueueState.Action.NEXT))
        assertEquals(listOf(1, 4, true, 1, 0), arguments)
    }

    @Test fun rejectsUnsupportedRepeatModes() {
        val queue = state()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            queue.setRepeatMode(3)
        }
    }

}
