package com.neiro.aftertone.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import android.animation.ValueAnimator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.media3.common.Player
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.playback.PlaybackQueueEntry
import com.neiro.aftertone.playback.PlaybackQueueSnapshot
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackArtwork
import com.neiro.aftertone.ui.components.SquareTrackArtwork
import com.neiro.aftertone.ui.components.formatDuration
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneText
import com.neiro.aftertone.ui.theme.LocalAftertonePalette

@Composable
internal fun NowPlayingScreen(
    track: Track?,
    isFavorite: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    queue: PlaybackQueueSnapshot,
    canEditQueue: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Boolean,
    canSelectQueueItems: Boolean,
    onSelectQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
) {
    val queuePresentation = nowPlayingQueuePresentation(
        hasCurrentTrack = track != null,
        queue = queue,
        canEditQueue = canEditQueue,
        repeatMode = repeatMode,
    )
    var showQueueDialog by remember { mutableStateOf(false) }
    PlaybackQueueDialog(
        visible = showQueueDialog,
        presentation = queuePresentation,
        canEditQueue = queuePresentation.canEditUpcoming,
        canSelectQueueItems = canSelectQueueItems,
        onDismiss = { showQueueDialog = false },
        onSelectQueueItem = onSelectQueueItem,
        onMoveQueueItem = onMoveQueueItem,
        onRemoveQueueItem = onRemoveQueueItem,
    )
    if (track == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AftertoneText)
                }
                Spacer(Modifier.weight(1f))
                if (queuePresentation.showQueueEntryPoint) {
                    TextButton(onClick = { showQueueDialog = true }) {
                        Text(
                            "Очередь · ${formatTrackCount(queuePresentation.visibleTrackCount)}",
                            color = AftertoneText,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Пока ничего не играет", color = AftertoneText, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Выбери трек в медиатеке, чтобы начать.", color = AftertoneMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
                ActionPill(label = "В медиатеку", onClick = onBack)
            }
        }
        return
    }

    val effectiveDuration = durationMs.takeIf { it > 0 } ?: track.durationMs
    var scrubValue by remember(track.id) { mutableStateOf<Float?>(null) }
    var pendingSeekPositionMs by remember(track.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(track.id, currentPositionMs, pendingSeekPositionMs) {
        val requestedPositionMs = pendingSeekPositionMs ?: return@LaunchedEffect
        if (isSeekPositionAcknowledged(currentPositionMs, requestedPositionMs)) {
            pendingSeekPositionMs = null
        }
    }
    LaunchedEffect(track.id, pendingSeekPositionMs) {
        val requestedPositionMs = pendingSeekPositionMs ?: return@LaunchedEffect
        delay(SEEK_PREVIEW_TIMEOUT_MS)
        if (pendingSeekPositionMs == requestedPositionMs) pendingSeekPositionMs = null
    }
    val displayedPositionMs = playbackPositionMsForDisplay(currentPositionMs, pendingSeekPositionMs)
    val progress = if (effectiveDuration > 0) (displayedPositionMs.toFloat() / effectiveDuration).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AftertoneText)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("СЕЙЧАС ИГРАЕТ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { showQueueDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Очередь · ${formatTrackCount(queuePresentation.visibleTrackCount)}",
                            color = AftertoneText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            queuePresentation.nextEntry?.let { "Следующий: ${it.title}" } ?: "Следующий: нет",
                            color = AftertoneMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics { contentDescription = if (isFavorite) "Убрать из избранного" else "Добавить в избранное" },
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) AftertoneAccent else AftertoneMuted,
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artSize = minOf(maxWidth, 390.dp)
            AnimatedContent(
                targetState = track,
                modifier = Modifier.width(artSize),
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(initialScale = 0.97f, animationSpec = tween(240))) togetherWith fadeOut(tween(150))
                },
                label = "now-playing-artwork",
            ) { targetTrack ->
                SquareTrackArtwork(targetTrack)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.displayTitle, color = AftertoneText, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = listOf(track.displayArtist, track.album).filter(String::isNotBlank).joinToString(" · "),
                        color = AftertoneMuted,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Slider(
                    value = scrubValue ?: progress,
                    onValueChange = { scrubValue = it },
                    onValueChangeFinished = {
                        val requestedPositionMs = scrubValue?.let { (it * effectiveDuration).toLong() }
                        scrubValue = null
                        pendingSeekPositionMs = requestedPositionMs?.takeIf { onSeek(it) }
                    },
                    enabled = effectiveDuration > 0,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Позиция воспроизведения" },
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(scrubValue?.let { (it * effectiveDuration).toLong() } ?: displayedPositionMs), color = AftertoneMuted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(effectiveDuration), color = AftertoneMuted, fontSize = 11.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val shuffleColors = playbackToggleColors(
                    colorScheme = MaterialTheme.colorScheme,
                    mode = PlaybackToggleMode.SHUFFLE,
                    active = shuffleModeEnabled,
                )
                FilledIconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = if (shuffleModeEnabled) "Выключить перемешивание" else "Включить перемешивание"
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = shuffleColors.containerColor,
                        contentColor = shuffleColors.contentColor,
                    ),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp).semantics { contentDescription = "Предыдущий трек" }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = AftertoneText, modifier = Modifier.size(30.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = AftertoneAccent,
                    modifier = Modifier.size(68.dp),
                    onClick = onTogglePlayPause,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(35.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp).semantics { contentDescription = "Следующий трек" }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null, tint = AftertoneText, modifier = Modifier.size(30.dp))
                }
                val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat
                val repeatColors = playbackToggleColors(
                    colorScheme = MaterialTheme.colorScheme,
                    mode = PlaybackToggleMode.REPEAT,
                    active = repeatMode != Player.REPEAT_MODE_OFF,
                )
                FilledIconButton(
                    onClick = onToggleRepeat,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = when (repeatMode) {
                            Player.REPEAT_MODE_ALL -> "Повторять один трек"
                            Player.REPEAT_MODE_ONE -> "Выключить повтор"
                            else -> "Включить повтор очереди"
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = repeatColors.containerColor,
                        contentColor = repeatColors.contentColor,
                    ),
                ) {
                    Icon(repeatIcon, contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
            Text("Сохранено на этом телефоне", color = AftertoneMuted.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private data class PlaybackQueueRowContent(
    val title: String,
    val subtitle: String,
)

@Composable
private fun PlaybackQueueRow(
    entry: PlaybackQueueEntry,
    isCurrent: Boolean,
    isNext: Boolean,
    canEdit: Boolean,
    canSelect: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    colors: PlaybackQueueColors,
    motion: PlaybackQueueMotion,
    modifier: Modifier = Modifier,
) {
    val targetCardColor = if (isCurrent) colors.currentTrack else colors.upcomingTrack
    val cardColor by animateColorAsState(
        targetValue = targetCardColor,
        animationSpec = tween(motion.contentChangeDurationMillis),
        label = "queue-row-surface",
    )
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (isCurrent) colors.action.copy(alpha = 0.55f) else colors.outline.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().then(
                if (!isCurrent && canSelect) {
                    Modifier.clickable(onClick = onSelect).semantics {
                        role = Role.Button
                        contentDescription = if (isNext) {
                            "Следующий трек: ${entry.title}, ${entry.artist}. Нажмите для воспроизведения."
                        } else {
                            "Воспроизвести ${entry.title}, ${entry.artist}"
                        }
                    }
                } else {
                    Modifier
                },
            ).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(
                track = null,
                modifier = Modifier.size(44.dp),
                cornerRadius = 12,
                artworkPathOverride = entry.artworkPath,
                fallbackLabel = entry.title,
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val rowContent = PlaybackQueueRowContent(
                    title = entry.title,
                    subtitle = when {
                        isCurrent && isNext -> "Сейчас играет · повторится следующим · ${entry.artist}"
                        isCurrent -> "Сейчас играет · ${entry.artist}"
                        isNext -> "Следующий · ${entry.artist}"
                        else -> entry.artist
                    },
                )
                AnimatedContent(
                    targetState = rowContent,
                    transitionSpec = {
                        (fadeIn(tween(motion.contentChangeDurationMillis)) +
                            slideInVertically(
                                animationSpec = tween(motion.contentChangeDurationMillis),
                                initialOffsetY = { height -> (height * motion.verticalOffsetFraction).roundToInt() },
                            )) togetherWith
                            (fadeOut(tween(motion.contentChangeDurationMillis)) +
                                slideOutVertically(
                                    animationSpec = tween(motion.contentChangeDurationMillis),
                                    targetOffsetY = { height -> -(height * motion.verticalOffsetFraction).roundToInt() },
                                ))
                    },
                    label = "queue-row-content",
                ) { content ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            content.title,
                            color = colors.primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = content.subtitle,
                            color = colors.secondaryText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!isCurrent) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canEdit && canMoveUp,
                    modifier = Modifier.semantics { contentDescription = "Переместить ${entry.title} выше" },
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = colors.primaryText)
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canEdit && canMoveDown,
                    modifier = Modifier.semantics { contentDescription = "Переместить ${entry.title} ниже" },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.primaryText)
                }
                IconButton(
                    onClick = onRemove,
                    enabled = canEdit,
                    modifier = Modifier.semantics { contentDescription = "Убрать ${entry.title} из очереди" },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = colors.secondaryText)
                }
            }
        }
    }
}


@Composable
private fun PlaybackQueueDialog(
    visible: Boolean,
    presentation: NowPlayingQueuePresentation,
    canEditQueue: Boolean,
    canSelectQueueItems: Boolean,
    onDismiss: () -> Unit,
    onSelectQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
) {
    val colors = playbackQueueColors(LocalAftertonePalette.current)
    val motion = playbackQueueMotion(animationsEnabled = ValueAnimator.areAnimatorsEnabled())
    val visibilityState = remember { MutableTransitionState(false) }
    SideEffect { visibilityState.targetState = visible }

    val upcomingItems = presentation.upcomingItems
    if (playbackQueueDialogShouldBeComposed(visibilityState.currentState, visibilityState.targetState)) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = true),
        ) {
            AnimatedVisibility(
                visibleState = visibilityState,
                enter = fadeIn(tween(motion.enterDurationMillis)) +
                    scaleIn(initialScale = motion.enterScale, animationSpec = tween(motion.enterDurationMillis)) +
                    slideInVertically(
                        animationSpec = tween(motion.enterDurationMillis),
                        initialOffsetY = { height -> (height * motion.verticalOffsetFraction).roundToInt() },
                    ),
                exit = fadeOut(tween(motion.exitDurationMillis)) +
                    scaleOut(targetScale = motion.exitScale, animationSpec = tween(motion.exitDurationMillis)) +
                    slideOutVertically(
                        animationSpec = tween(motion.exitDurationMillis),
                        targetOffsetY = { height -> -(height * motion.verticalOffsetFraction).roundToInt() },
                    ),
                label = "playback-queue-dialog",
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = colors.dialogSurface,
                    contentColor = colors.primaryText,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
                    ) {
                        Text(
                            text = "Очередь воспроизведения",
                            color = colors.primaryText,
                            fontSize = 22.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(16.dp))
                        if (presentation.visibleTrackCount == 0) {
                            Text("Очередь пуста", color = colors.secondaryText)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                                contentPadding = PaddingValues(bottom = 3.dp),
                            ) {
                                presentation.currentEntry?.let { currentEntry ->
                                    item(
                                        key = "current-${currentEntry.mediaId}",
                                        contentType = "current-track",
                                    ) {
                                        PlaybackQueueRow(
                                            entry = currentEntry,
                                            isCurrent = true,
                                            isNext = currentEntry.playerIndex == presentation.nextEntry?.playerIndex,
                                            canEdit = false,
                                            canSelect = false,
                                            canMoveUp = false,
                                            canMoveDown = false,
                                            onSelect = {},
                                            onMoveUp = {},
                                            onMoveDown = {},
                                            onRemove = {},
                                            colors = colors,
                                            motion = motion,
                                            modifier = Modifier.animateItem(
                                                fadeInSpec = tween(motion.contentChangeDurationMillis),
                                                placementSpec = tween(motion.rowPlacementDurationMillis),
                                                fadeOutSpec = tween(motion.exitDurationMillis),
                                            ),
                                        )
                                    }
                                }
                                itemsIndexed(
                                    items = upcomingItems,
                                    key = { _, item -> "upcoming-${item.queueIndex}-${item.entry.mediaId}" },
                                ) { visibleIndex, queueItem ->
                                    val queueIndex = queueItem.queueIndex
                                    val entry = queueItem.entry
                                    val previousItem = upcomingItems.getOrNull(visibleIndex - 1)
                                    val nextItem = upcomingItems.getOrNull(visibleIndex + 1)
                                    PlaybackQueueRow(
                                        entry = entry,
                                        isCurrent = false,
                                        isNext = entry.playerIndex == presentation.nextEntry?.playerIndex,
                                        canEdit = canEditQueue && visible,
                                        canSelect = canSelectQueueItems && visible,
                                        canMoveUp = visibleIndex > 0,
                                        canMoveDown = visibleIndex < upcomingItems.lastIndex,
                                        onMoveUp = { previousItem?.let { onMoveQueueItem(queueIndex, it.queueIndex) } },
                                        onMoveDown = { nextItem?.let { onMoveQueueItem(queueIndex, it.queueIndex) } },
                                        onRemove = { onRemoveQueueItem(queueIndex) },
                                        onSelect = {
                                            onSelectQueueItem(entry.playerIndex)
                                            onDismiss()
                                        },
                                        colors = colors,
                                        motion = motion,
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(motion.contentChangeDurationMillis),
                                            placementSpec = tween(motion.rowPlacementDurationMillis),
                                            fadeOutSpec = tween(motion.exitDurationMillis),
                                        ),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.action),
                            ) {
                                Text("Готово")
                            }
                        }
                    }
                }
            }
        }
    }
}
