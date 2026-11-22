package com.neiro.aftertone.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueManualPriorityTest {
    @Test
    fun newlyQueuedTrackMovesBeforeTheFirstLibraryContinuationEvenIfShufflePlacedItBeforeCurrent() {
        val queue = PlaybackQueueSnapshot(
            items = listOf(
                entry("past", 0),
                entry("manual-added-later", 5),
                entry("current", 1),
                entry("manual-first", 3),
                entry("library-random-start", 2, isLibraryContinuation = true),
                entry("library-next", 4, isLibraryContinuation = true),
            ),
            currentIndex = 2,
        )

        val plan = requireNotNull(queue.planMoveUpcomingItemBeforeLibraryContinuation(playerIndex = 5))

        assertEquals(
            listOf("past", "current", "manual-first", "manual-added-later", "library-random-start", "library-next"),
            plan.snapshot.items.map(PlaybackQueueEntry::mediaId),
        )
        assertEquals(
            listOf(
                MediaItemMove(fromIndex = 3, toIndex = 1),
                MediaItemMove(fromIndex = 3, toIndex = 2),
                MediaItemMove(fromIndex = 5, toIndex = 3),
                MediaItemMove(fromIndex = 5, toIndex = 4),
            ),
            plan.moves,
        )
        assertEquals("current", plan.snapshot.currentEntry?.mediaId)
        assertEquals(1, plan.snapshot.currentIndex)
    }

    private fun entry(
        id: String,
        playerIndex: Int,
        isLibraryContinuation: Boolean = false,
    ) = PlaybackQueueEntry(
        mediaId = id,
        title = id,
        artist = "Artist",
        playerIndex = playerIndex,
        isLibraryContinuation = isLibraryContinuation,
    )
}
