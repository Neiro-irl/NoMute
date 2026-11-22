package com.neiro.aftertone.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay

internal data class PlaybackUiState(
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

internal fun nextRepeatMode(currentMode: Int): Int = when (currentMode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

@Composable
internal fun rememberMediaController(context: Context): MediaController? {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        var active = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (active) controller = connected else connected?.release()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active = false
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

@Composable
internal fun rememberPlaybackUiState(controller: MediaController?): PlaybackUiState {
    var playback by remember(controller) { mutableStateOf(controller?.readPlaybackUiState() ?: PlaybackUiState()) }
    val latestQueueSnapshot by rememberUpdatedState(playback.queue)

    DisposableEffect(controller) {
        val activeController = controller
        if (activeController == null) {
            playback = PlaybackUiState()
            onDispose { }
        } else {
            fun synchronize() {
                playback = activeController.readPlaybackUiState()
            }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = synchronize()
                override fun onIsPlayingChanged(isPlaying: Boolean) = synchronize()
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = synchronize()
                override fun onPlaybackStateChanged(playbackState: Int) = synchronize()
                override fun onRepeatModeChanged(repeatMode: Int) = synchronize()
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = synchronize()
            }
            activeController.addListener(listener)
            synchronize()
            onDispose { activeController.removeListener(listener) }
        }
    }

    LaunchedEffect(controller, playback.isPlaying) {
        val activeController = controller ?: return@LaunchedEffect
        while (activeController.isPlaying) {
            playback = activeController.readPlaybackUiState(queueSnapshot = latestQueueSnapshot)
            delay(450)
        }
    }
    return playback
}

private fun MediaController.readPlaybackUiState(
    queueSnapshot: PlaybackQueueSnapshot? = null,
): PlaybackUiState {
    val playerDuration = duration.takeIf { it >= 0 && it != C.TIME_UNSET } ?: 0L
    return PlaybackUiState(
        currentTrackId = currentMediaItem?.mediaId?.takeIf(String::isNotBlank),
        isPlaying = isPlaying,
        currentPositionMs = currentPosition.coerceAtLeast(0),
        durationMs = playerDuration,
        queue = queueSnapshot ?: readQueueSnapshot(),
        shuffleModeEnabled = shuffleModeEnabled,
        repeatMode = repeatMode,
    )
}

internal fun MediaController.readQueueSnapshot(): PlaybackQueueSnapshot {
    val mediaItems = (0 until mediaItemCount).map { index ->
        val mediaItem = getMediaItemAt(index)
        val metadata = mediaItem.mediaMetadata
        PlaybackQueueEntry(
            mediaId = mediaItem.mediaId,
            title = metadata.title?.toString()?.takeIf(String::isNotBlank) ?: "Без названия",
            artist = metadata.artist?.toString()?.takeIf(String::isNotBlank) ?: "Неизвестный исполнитель",
            playerIndex = index,
            isLibraryContinuation = metadata.extras?.getBoolean(LIBRARY_CONTINUATION_EXTRA_KEY, false) == true,
            artworkPath = metadata.artworkUri?.path,
        )
    }
    val timeline = currentTimeline
    val traversalIndices = if (timeline.windowCount == mediaItems.size) {
        playbackTraversalIndices(
            itemCount = mediaItems.size,
            currentPlayerIndex = currentMediaItemIndex,
            repeatMode = repeatMode,
            shuffleModeEnabled = shuffleModeEnabled,
            firstWindowIndex = timeline.getFirstWindowIndex(shuffleModeEnabled),
            nextWindowIndex = { index, mode, shuffle ->
                timeline.getNextWindowIndex(index, mode, shuffle)
            },
        )
    } else {
        emptyList()
    }
    val playbackOrder = if (traversalIndices.size == mediaItems.size) {
        traversalIndices.map(mediaItems::get)
    } else {
        mediaItems
    }
    val activeIndex = playbackOrder.indexOfFirst { it.playerIndex == currentMediaItemIndex }
    return PlaybackQueueSnapshot(
        items = playbackOrder,
        currentIndex = activeIndex.takeIf { it >= 0 }
            ?: PlaybackQueueSnapshot.NO_CURRENT_QUEUE_INDEX,
    )
}
