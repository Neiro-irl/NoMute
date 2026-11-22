package com.neiro.aftertone.playback

import androidx.media3.common.Player
import com.neiro.aftertone.model.Track

internal data class PlaybackQueueRequest(
    val tracks: List<Track>,
    val startIndex: Int,
)

internal fun playbackQueueRequest(
    tracks: List<Track>,
    selectedTrackId: String,
): PlaybackQueueRequest = PlaybackQueueRequest(
    tracks = tracks,
    startIndex = tracks.indexOfFirst { it.id == selectedTrackId }.coerceAtLeast(0),
)

internal data class PlaybackQueueEntry(
    val mediaId: String,
    val title: String,
    val artist: String,
    val playerIndex: Int,
    val isLibraryContinuation: Boolean = false,
    val artworkPath: String? = null,
)

internal data class MediaItemMove(
    val fromIndex: Int,
    val toIndex: Int,
)

internal data class PlaybackQueueMovePlan(
    val snapshot: PlaybackQueueSnapshot,
    val moves: List<MediaItemMove>,
)

internal data class PlaybackQueueRemovalPlan(
    val snapshot: PlaybackQueueSnapshot,
    val mediaItemIndex: Int,
)

/**
 * Queue entries are ordered by the player's playback traversal, while [PlaybackQueueEntry.playerIndex]
 * retains the corresponding playlist index required by MediaController edit commands.
 */
internal data class PlaybackQueueSnapshot(
    val items: List<PlaybackQueueEntry> = emptyList(),
    val currentIndex: Int = NO_CURRENT_QUEUE_INDEX,
) {
    init {
        require(currentIndex == NO_CURRENT_QUEUE_INDEX || currentIndex in items.indices) {
            "The current queue position must refer to a queue entry."
        }
        require(items.map(PlaybackQueueEntry::playerIndex).distinct().size == items.size) {
            "Every queue entry must map to a unique player index."
        }
        require(items.all { it.playerIndex in items.indices }) {
            "Queue entry player indices must cover the current playlist range."
        }
    }

    val currentEntry: PlaybackQueueEntry?
        get() = items.getOrNull(currentIndex)

    val upcomingItems: List<PlaybackQueueEntry>
        get() = items.drop((currentIndex + 1).coerceAtLeast(0))

    /** Plans a display-order move and translates it into MediaController playlist moves. */
    fun planMoveUpcomingItem(fromIndex: Int, toIndex: Int): PlaybackQueueMovePlan? {
        if (!isUpcomingIndex(fromIndex) || !isUpcomingIndex(toIndex)) return null
        if (fromIndex == toIndex) return PlaybackQueueMovePlan(this, emptyList())

        val reorderedItems = items.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        return planReorderedItems(reorderedItems, currentIndex)
    }

    /** Moves a newly added item to the end of the traversal without changing the active entry. */
    fun planMoveToQueueEnd(playerIndex: Int): PlaybackQueueMovePlan? {
        val fromIndex = items.indexOfFirst { it.playerIndex == playerIndex }
        if (fromIndex < 0 || currentEntry?.playerIndex == playerIndex) return null
        if (fromIndex == items.lastIndex) return PlaybackQueueMovePlan(this, emptyList())

        val reorderedItems = items.toMutableList().apply {
            add(removeAt(fromIndex))
        }
        val nextCurrentIndex = currentEntry?.let { current ->
            reorderedItems.indexOfFirst { it.playerIndex == current.playerIndex }
        } ?: NO_CURRENT_QUEUE_INDEX
        return planReorderedItems(reorderedItems, nextCurrentIndex)
    }

    /** Moves a non-current Media3 [playerIndex] immediately before an upcoming traversal position. */
    fun planMoveItemBeforeTraversalIndex(
        playerIndex: Int,
        beforeTraversalIndex: Int,
    ): PlaybackQueueMovePlan? {
        val fromIndex = items.indexOfFirst { it.playerIndex == playerIndex }
        val firstUpcomingIndex = (currentIndex + 1).coerceAtLeast(0)
        if (fromIndex < 0 || currentEntry?.playerIndex == playerIndex) return null
        if (beforeTraversalIndex !in firstUpcomingIndex..items.size) return null

        val insertionIndex = if (fromIndex < beforeTraversalIndex) beforeTraversalIndex - 1 else beforeTraversalIndex
        if (fromIndex == insertionIndex) return PlaybackQueueMovePlan(this, emptyList())

        val reorderedItems = items.toMutableList().apply {
            add(insertionIndex, removeAt(fromIndex))
        }
        val nextCurrentIndex = currentEntry?.let { current ->
            reorderedItems.indexOfFirst { it.playerIndex == current.playerIndex }
        } ?: NO_CURRENT_QUEUE_INDEX
        return planReorderedItems(reorderedItems, nextCurrentIndex)
    }

    /** Keeps a newly queued manual track ahead of the remaining marked library continuation. */
    fun planMoveUpcomingItemBeforeLibraryContinuation(playerIndex: Int): PlaybackQueueMovePlan? {
        val firstUpcomingIndex = (currentIndex + 1).coerceAtLeast(0)
        val continuationOffset = upcomingItems.indexOfFirst(PlaybackQueueEntry::isLibraryContinuation)
        if (continuationOffset < 0) return null
        return planMoveItemBeforeTraversalIndex(playerIndex, firstUpcomingIndex + continuationOffset)
    }

    private fun planReorderedItems(
        reorderedItems: List<PlaybackQueueEntry>,
        nextCurrentIndex: Int,
    ): PlaybackQueueMovePlan {
        val desiredRawEntries = arrayOfNulls<PlaybackQueueEntry>(items.size)
        items.indices.forEach { traversalIndex ->
            val playerIndex = items[traversalIndex].playerIndex
            desiredRawEntries[playerIndex] = reorderedItems[traversalIndex]
        }

        val desiredRawTokens = desiredRawEntries.map { entry ->
            requireNotNull(entry) { "Playback traversal must include every playlist item." }.playerIndex
        }
        val workingTokens = items.sortedBy(PlaybackQueueEntry::playerIndex)
            .map(PlaybackQueueEntry::playerIndex)
            .toMutableList()
        val moves = mutableListOf<MediaItemMove>()
        desiredRawTokens.forEachIndexed { targetIndex, token ->
            val sourceIndex = workingTokens.indexOf(token)
            check(sourceIndex >= 0) { "Queue edit plan lost a playlist item." }
            if (sourceIndex != targetIndex) {
                moves += MediaItemMove(fromIndex = sourceIndex, toIndex = targetIndex)
                workingTokens.add(targetIndex, workingTokens.removeAt(sourceIndex))
            }
        }

        val newPlayerIndexByToken = desiredRawTokens.withIndex().associate { (newIndex, token) ->
            token to newIndex
        }
        val plannedItems = reorderedItems.map { entry ->
            entry.copy(playerIndex = newPlayerIndexByToken.getValue(entry.playerIndex))
        }
        return PlaybackQueueMovePlan(
            snapshot = copy(items = plannedItems, currentIndex = nextCurrentIndex),
            moves = moves,
        )
    }

    /** Plans removal of one upcoming entry; the returned index is the current player playlist index. */
    fun planRemoveUpcomingItem(index: Int): PlaybackQueueRemovalPlan? {
        if (!isUpcomingIndex(index)) return null
        val removedPlayerIndex = items[index].playerIndex
        val remainingItems = items.filterIndexed { itemIndex, _ -> itemIndex != index }
            .map { entry ->
                if (entry.playerIndex > removedPlayerIndex) entry.copy(playerIndex = entry.playerIndex - 1) else entry
            }
        return PlaybackQueueRemovalPlan(
            snapshot = copy(items = remainingItems),
            mediaItemIndex = removedPlayerIndex,
        )
    }

    private fun isUpcomingIndex(index: Int): Boolean =
        index in items.indices && index > currentIndex

    companion object {
        const val NO_CURRENT_QUEUE_INDEX = -1
    }
}

internal fun requiresQueueEndReordering(shuffleModeEnabled: Boolean, repeatMode: Int): Boolean =
    shuffleModeEnabled || repeatMode == Player.REPEAT_MODE_ALL

/**
 * Builds a complete player-index traversal using the active Timeline's next-window relation.
 * Repeat-one is treated like repeat-off here so the rest of the queue remains available to edit.
 */
internal fun playbackTraversalIndices(
    itemCount: Int,
    currentPlayerIndex: Int,
    repeatMode: Int,
    shuffleModeEnabled: Boolean,
    firstWindowIndex: Int,
    nextWindowIndex: (currentIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) -> Int,
): List<Int> {
    if (itemCount <= 0) return emptyList()

    val traversalRepeatMode = if (repeatMode == Player.REPEAT_MODE_ALL) {
        Player.REPEAT_MODE_ALL
    } else {
        Player.REPEAT_MODE_OFF
    }
    val firstIndex = firstWindowIndex.takeIf { it in 0 until itemCount } ?: 0
    val startIndex = if (traversalRepeatMode == Player.REPEAT_MODE_ALL && currentPlayerIndex in 0 until itemCount) {
        currentPlayerIndex
    } else {
        firstIndex
    }

    val result = ArrayList<Int>(itemCount)
    val visited = hashSetOf<Int>()
    var index = startIndex
    while (index in 0 until itemCount && visited.add(index)) {
        result += index
        index = nextWindowIndex(index, traversalRepeatMode, shuffleModeEnabled)
    }
    return result
}
