package com.neiro.aftertone.ui.screens

import androidx.media3.common.Player
import com.neiro.aftertone.playback.PlaybackQueueEntry
import com.neiro.aftertone.playback.PlaybackQueueSnapshot

internal data class NowPlayingQueueItem(
    val entry: PlaybackQueueEntry,
    val queueIndex: Int,
)

internal data class NowPlayingQueuePresentation(
    val showQueueEntryPoint: Boolean,
    val hasCurrentTrack: Boolean,
    val currentEntry: PlaybackQueueEntry?,
    val nextEntry: PlaybackQueueEntry?,
    val upcomingItems: List<NowPlayingQueueItem>,
    val visibleTrackCount: Int,
    val canEditUpcoming: Boolean,
)

internal fun nowPlayingQueuePresentation(
    hasCurrentTrack: Boolean,
    queue: PlaybackQueueSnapshot,
    canEditQueue: Boolean,
    repeatMode: Int,
): NowPlayingQueuePresentation {
    val currentEntry = queue.currentEntry
    val firstUpcomingQueueIndex = (queue.currentIndex + 1).coerceAtLeast(0)
    val upcomingItems = queue.upcomingItems.mapIndexedNotNull { upcomingIndex, entry ->
        if (entry.isLibraryContinuation || entry.mediaId == currentEntry?.mediaId) {
            null
        } else {
            NowPlayingQueueItem(
                entry = entry,
                queueIndex = firstUpcomingQueueIndex + upcomingIndex,
            )
        }
    }
    val visibleTrackCount = upcomingItems.size + if (currentEntry != null) 1 else 0
    val nextEntry = if (repeatMode == Player.REPEAT_MODE_ONE) {
        currentEntry
    } else {
        upcomingItems.firstOrNull()?.entry
    }

    return NowPlayingQueuePresentation(
        showQueueEntryPoint = hasCurrentTrack || visibleTrackCount > 0,
        hasCurrentTrack = hasCurrentTrack,
        currentEntry = currentEntry,
        nextEntry = nextEntry,
        upcomingItems = upcomingItems,
        visibleTrackCount = visibleTrackCount,
        canEditUpcoming = canEditQueue && upcomingItems.isNotEmpty(),
    )
}
