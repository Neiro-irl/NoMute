package com.neiro.aftertone.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRowGestureTest {
    @Test
    fun positiveDragMovesTheRowAndStopsAtTheRevealWidth() {
        val firstOffset = queueSwipeOffsetAfterDrag(
            currentOffsetPx = 0f,
            dragAmountPx = 48f,
            revealWidthPx = 104f,
        )

        assertEquals(48f, firstOffset, 0f)
        assertEquals(104f, queueSwipeOffsetAfterDrag(firstOffset, 100f, 104f), 0f)
    }

    @Test
    fun negativeDragDoesNotRevealTheQueueAction() {
        assertEquals(0f, queueSwipeOffsetAfterDrag(0f, -48f, 104f), 0f)
    }

    @Test
    fun rightwardSwipeQueuesOnlyAfterThreshold() {
        assertFalse(shouldQueueTrackOnSwipe(horizontalOffsetPx = -100f, thresholdPx = 40f))
        assertFalse(shouldQueueTrackOnSwipe(horizontalOffsetPx = 39f, thresholdPx = 40f))
        assertTrue(shouldQueueTrackOnSwipe(horizontalOffsetPx = 40f, thresholdPx = 40f))
    }
}
