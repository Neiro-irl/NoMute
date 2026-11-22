package com.neiro.aftertone.ui.screens

import androidx.media3.common.Player
import com.neiro.aftertone.playback.PlaybackQueueEntry
import com.neiro.aftertone.playback.PlaybackQueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingQueueTest {
    @Test
    fun nonemptyQueueWithoutCurrentTrackKeepsQueueEntryPointAndUpcomingEditsAvailable() {
        val queue = PlaybackQueueSnapshot(
            items = listOf(
                entry("shuffle-first", 2),
                entry("shuffle-second", 0),
                entry("shuffle-third", 1),
            ),
            currentIndex = PlaybackQueueSnapshot.NO_CURRENT_QUEUE_INDEX,
        )

        val presentation = nowPlayingQueuePresentation(
            hasCurrentTrack = false,
            queue = queue,
            canEditQueue = true,
            repeatMode = Player.REPEAT_MODE_OFF,
        )

        assertTrue(presentation.showQueueEntryPoint)
        assertFalse(presentation.hasCurrentTrack)
        assertEquals(listOf("shuffle-first", "shuffle-second", "shuffle-third"), presentation.upcomingItems.map { it.entry.mediaId })
        assertTrue(presentation.canEditUpcoming)
        assertTrue(queue.planMoveUpcomingItem(fromIndex = 2, toIndex = 1) != null)
        assertTrue(queue.planRemoveUpcomingItem(index = 2) != null)
    }

    @Test
    fun queueEntryPointIsHiddenWhenThereIsNoTrackAndNoQueue() {
        val presentation = nowPlayingQueuePresentation(
            hasCurrentTrack = false,
            queue = PlaybackQueueSnapshot(),
            canEditQueue = true,
            repeatMode = Player.REPEAT_MODE_OFF,
        )

        assertFalse(presentation.showQueueEntryPoint)
        assertFalse(presentation.canEditUpcoming)
    }

    @Test
    fun presentationExcludesPlayedContinuationAndDuplicateCurrentFromVisibleQueue() {
        val queue = PlaybackQueueSnapshot(
            items = listOf(
                entry("played", 0),
                entry("current", 1),
                entry("manual-next", 2),
                entry("library-tail", 3, isLibraryContinuation = true),
                entry("current", 4),
                entry("manual-later", 5),
            ),
            currentIndex = 1,
        )

        val presentation = nowPlayingQueuePresentation(
            hasCurrentTrack = true,
            queue = queue,
            canEditQueue = true,
            repeatMode = Player.REPEAT_MODE_OFF,
        )

        assertEquals("current", presentation.currentEntry?.mediaId)
        assertEquals(
            listOf("manual-next", "manual-later"),
            presentation.upcomingItems.map { it.entry.mediaId },
        )
        assertEquals(listOf(2, 5), presentation.upcomingItems.map { it.queueIndex })
        assertEquals(3, presentation.visibleTrackCount)
        assertEquals("manual-next", presentation.nextEntry?.mediaId)
        assertTrue(presentation.canEditUpcoming)
    }

    @Test
    fun continuationOnlyQueueWithoutCurrentDoesNotCreateVisibleQueueEntryPoint() {
        val presentation = nowPlayingQueuePresentation(
            hasCurrentTrack = false,
            queue = PlaybackQueueSnapshot(
                items = listOf(entry("library-tail", 0, isLibraryContinuation = true)),
                currentIndex = PlaybackQueueSnapshot.NO_CURRENT_QUEUE_INDEX,
            ),
            canEditQueue = true,
            repeatMode = Player.REPEAT_MODE_OFF,
        )

        assertTrue(presentation.upcomingItems.isEmpty())
        assertFalse(presentation.showQueueEntryPoint)
        assertFalse(presentation.canEditUpcoming)
    }

    private fun entry(
        id: String,
        playerIndex: Int,
        isLibraryContinuation: Boolean = false,
    ) = PlaybackQueueEntry(
        mediaId = id,
        title = "Title $id",
        artist = "Artist $id",
        playerIndex = playerIndex,
        isLibraryContinuation = isLibraryContinuation,
    )
}
