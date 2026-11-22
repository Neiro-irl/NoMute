package com.neiro.aftertone.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {
    private val queue = PlaybackQueueSnapshot(
        items = listOf(
            entry("played", 0),
            entry("current", 1),
            entry("next-a", 2),
            entry("next-b", 3),
            entry("next-c", 4),
        ),
        currentIndex = 1,
    )

    @Test
    fun movingUpcomingItemChangesOnlyTheUpcomingOrder() {
        val plan = queue.planMoveUpcomingItem(fromIndex = 4, toIndex = 2)

        assertEquals(
            listOf("played", "current", "next-c", "next-a", "next-b"),
            plan?.snapshot?.items?.map(PlaybackQueueEntry::mediaId),
        )
        assertEquals(listOf(MediaItemMove(fromIndex = 4, toIndex = 2)), plan?.moves)
        assertEquals("current", plan?.snapshot?.currentEntry?.mediaId)
        assertEquals(1, plan?.snapshot?.currentIndex)
    }

    @Test
    fun queueEndNormalizationIsRequiredForShuffleAndRepeatAll() {
        assertTrue(requiresQueueEndReordering(shuffleModeEnabled = true, repeatMode = Player.REPEAT_MODE_OFF))
        assertTrue(requiresQueueEndReordering(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_ALL))
        assertFalse(requiresQueueEndReordering(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_OFF))
        assertFalse(requiresQueueEndReordering(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_ONE))
    }

    @Test
    fun movingNewShuffledItemToQueueEndKeepsCurrentTrackAndAppendsAfterUpcomingItems() {
        val shuffledQueue = PlaybackQueueSnapshot(
            items = listOf(
                entry("added", 4),
                entry("played", 2),
                entry("current", 0),
                entry("next-a", 3),
                entry("next-b", 1),
            ),
            currentIndex = 2,
        )

        val plan = shuffledQueue.planMoveToQueueEnd(playerIndex = 4)

        assertEquals(
            listOf("played", "current", "next-a", "next-b", "added"),
            plan?.snapshot?.items?.map(PlaybackQueueEntry::mediaId),
        )
        assertEquals(1, plan?.snapshot?.currentIndex)
        assertEquals("current", plan?.snapshot?.currentEntry?.mediaId)
        assertEquals(
            listOf("next-a", "next-b", "added"),
            plan?.snapshot?.upcomingItems?.map(PlaybackQueueEntry::mediaId),
        )

        val rawItems = shuffledQueue.items.sortedBy(PlaybackQueueEntry::playerIndex)
            .map(PlaybackQueueEntry::mediaId)
            .toMutableList()
        plan?.moves.orEmpty().forEach { move ->
            rawItems.add(move.toIndex, rawItems.removeAt(move.fromIndex))
        }
        val shuffleTraversal = listOf(4, 2, 0, 3, 1)
        assertEquals(
            listOf("played", "current", "next-a", "next-b", "added"),
            shuffleTraversal.map(rawItems::get),
        )
    }

    @Test
    fun removingUpcomingItemLeavesCurrentAndRemainingQueueItems() {
        val plan = queue.planRemoveUpcomingItem(index = 3)

        assertEquals(3, plan?.mediaItemIndex)
        assertEquals(
            listOf("played", "current", "next-a", "next-c"),
            plan?.snapshot?.items?.map(PlaybackQueueEntry::mediaId),
        )
        assertEquals("current", plan?.snapshot?.currentEntry?.mediaId)
        assertEquals(1, plan?.snapshot?.currentIndex)
    }

    @Test
    fun currentAndPreviouslyPlayedItemsCannotBeMovedOrRemoved() {
        assertNull(queue.planMoveUpcomingItem(fromIndex = 1, toIndex = 2))
        assertNull(queue.planMoveUpcomingItem(fromIndex = 2, toIndex = 1))
        assertNull(queue.planRemoveUpcomingItem(index = 1))
        assertNull(queue.planRemoveUpcomingItem(index = 0))
    }

    @Test
    fun outOfRangeQueueEditsAreRejected() {
        assertNull(queue.planMoveUpcomingItem(fromIndex = 2, toIndex = 5))
        assertNull(queue.planRemoveUpcomingItem(index = 5))
    }

    @Test
    fun shuffledUpcomingRowsMapEditsToTheUnderlyingPlayerIndices() {
        val shuffledQueue = PlaybackQueueSnapshot(
            items = listOf(
                entry("slot-2", 2),
                entry("current-slot-0", 0),
                entry("slot-3", 3),
                entry("slot-1", 1),
            ),
            currentIndex = 1,
        )

        assertEquals(listOf("slot-3", "slot-1"), shuffledQueue.upcomingItems.map(PlaybackQueueEntry::mediaId))

        val movePlan = shuffledQueue.planMoveUpcomingItem(fromIndex = 3, toIndex = 2)
        assertEquals(
            listOf("slot-2", "current-slot-0", "slot-1", "slot-3"),
            movePlan?.snapshot?.items?.map(PlaybackQueueEntry::mediaId),
        )
        assertEquals(
            listOf(MediaItemMove(3, 1), MediaItemMove(3, 2)),
            movePlan?.moves,
        )
        assertEquals(listOf("current-slot-0", "slot-3", "slot-2", "slot-1"), movePlan?.snapshot?.items?.sortedBy(PlaybackQueueEntry::playerIndex)?.map(PlaybackQueueEntry::mediaId))
        val rawItems = mutableListOf("current-slot-0", "slot-1", "slot-2", "slot-3")
        movePlan?.moves.orEmpty().forEach { move ->
            rawItems.add(move.toIndex, rawItems.removeAt(move.fromIndex))
        }
        val shuffledTraversalAfterMove = listOf(2, 0, 3, 1).map(rawItems::get)
        assertEquals(listOf("slot-2", "current-slot-0", "slot-1", "slot-3"), shuffledTraversalAfterMove)
        assertEquals(listOf("slot-1", "slot-3"), shuffledTraversalAfterMove.drop(2))
        assertEquals("current-slot-0", movePlan?.snapshot?.currentEntry?.mediaId)

        val removalPlan = shuffledQueue.planRemoveUpcomingItem(index = 3)
        assertEquals(1, removalPlan?.mediaItemIndex)
        assertEquals(
            listOf("slot-2", "current-slot-0", "slot-3"),
            removalPlan?.snapshot?.items?.map(PlaybackQueueEntry::mediaId),
        )
        assertEquals(listOf(1, 0, 2), removalPlan?.snapshot?.items?.map(PlaybackQueueEntry::playerIndex))
    }

    @Test
    fun controllerShuffleTraversalBuildsTheQueueInTheActualOrder() {
        val nextByIndex = mapOf(2 to 0, 0 to 3, 3 to 1, 1 to -1)

        val traversal = playbackTraversalIndices(
            itemCount = 4,
            currentPlayerIndex = 0,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleModeEnabled = true,
            firstWindowIndex = 2,
        ) { currentIndex, _, _ -> nextByIndex.getValue(currentIndex) }

        assertEquals(listOf(2, 0, 3, 1), traversal)
        assertEquals(1, traversal.indexOf(0))
    }

    @Test
    fun repeatAllStartsAtCurrentAndWrapsTheShuffledTraversalOnce() {
        val nextByIndex = mapOf(0 to 3, 3 to 1, 1 to 2, 2 to 0)

        val traversal = playbackTraversalIndices(
            itemCount = 4,
            currentPlayerIndex = 0,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleModeEnabled = true,
            firstWindowIndex = 2,
        ) { currentIndex, repeatMode, shuffleEnabled ->
            assertEquals(Player.REPEAT_MODE_ALL, repeatMode)
            assertEquals(true, shuffleEnabled)
            nextByIndex.getValue(currentIndex)
        }

        assertEquals(listOf(0, 3, 1, 2), traversal)
    }

    @Test
    fun repeatOneStillEnumeratesTheShuffledQueueForEditing() {
        val nextByIndex = mapOf(2 to 0, 0 to 3, 3 to 1, 1 to -1)

        val traversal = playbackTraversalIndices(
            itemCount = 4,
            currentPlayerIndex = 0,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleModeEnabled = true,
            firstWindowIndex = 2,
        ) { currentIndex, repeatMode, shuffleEnabled ->
            assertEquals(Player.REPEAT_MODE_OFF, repeatMode)
            assertEquals(true, shuffleEnabled)
            nextByIndex.getValue(currentIndex)
        }

        assertEquals(listOf(2, 0, 3, 1), traversal)
    }

    private fun entry(id: String, playerIndex: Int) = PlaybackQueueEntry(
        mediaId = id,
        title = "Title $id",
        artist = "Artist $id",
        playerIndex = playerIndex,
    )
}


class LibraryPlaybackQueueSelectionTest {
    @org.junit.Test
    fun collectionQueueRemainsInPlaylistOrderAfterGlobalLibraryReorder() {
        val savedLibrary = com.neiro.aftertone.model.LibrarySnapshot(
            tracks = listOf(
                com.neiro.aftertone.model.Track("first", "First", "Artist", filePath = "/audio/first.mp3"),
                com.neiro.aftertone.model.Track("selected", "Selected", "Artist", filePath = "/audio/selected.mp3"),
                com.neiro.aftertone.model.Track("last", "Last", "Artist", filePath = "/audio/last.mp3"),
            ),
        ).moveTrack("last", destinationIndex = 0)
        val collection = com.neiro.aftertone.model.Playlist(
            id = "collection",
            name = "Collection",
            trackIds = listOf("selected", "last"),
        )
        val collectionTracks = collection.tracksInOrder(savedLibrary.tracks)

        val request = playbackQueueRequest(collectionTracks, selectedTrackId = "last")

        org.junit.Assert.assertEquals(listOf("selected", "last"), request.tracks.map { it.id })
        org.junit.Assert.assertEquals(1, request.startIndex)
    }

    @org.junit.Test
    fun selectedLibraryTrackKeepsEveryTrackInLibraryOrderAndStartsAtItsIndex() {
        val libraryTracks = com.neiro.aftertone.model.LibrarySnapshot(
            tracks = listOf(
                com.neiro.aftertone.model.Track("first", "First", "Artist", filePath = "/audio/first.mp3"),
                com.neiro.aftertone.model.Track("selected", "Selected", "Artist", filePath = "/audio/selected.mp3"),
                com.neiro.aftertone.model.Track("last", "Last", "Artist", filePath = "/audio/last.mp3"),
            ),
        ).moveTrack("last", destinationIndex = 0).tracks

        val request = playbackQueueRequest(libraryTracks, selectedTrackId = "selected")

        org.junit.Assert.assertEquals(listOf("last", "first", "selected"), request.tracks.map { it.id })
        org.junit.Assert.assertEquals(2, request.startIndex)
    }
}
