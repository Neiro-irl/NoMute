package com.neiro.aftertone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.R
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneSurface

internal fun queueSwipeOffsetAfterDrag(currentOffsetPx: Float, dragAmountPx: Float, revealWidthPx: Float): Float =
    (currentOffsetPx + dragAmountPx).coerceIn(0f, revealWidthPx)

internal fun shouldQueueTrackOnSwipe(horizontalOffsetPx: Float, thresholdPx: Float): Boolean =
    horizontalOffsetPx.isFinite() && thresholdPx.isFinite() && thresholdPx > 0f && horizontalOffsetPx >= thresholdPx

@Composable
fun TrackRow(
    track: Track,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onChooseArtwork: (String) -> Unit,
    onRemoveFromCollection: (() -> Unit)? = null,
    reorderActions: (@Composable () -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    var swipeOffsetPx by remember(track.id) { mutableFloatStateOf(0f) }
    val currentOnAddToQueue by rememberUpdatedState(onAddToQueue)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val swipeRevealPx = with(density) { 104.dp.toPx() }
    val swipeModifier = if (onAddToQueue != null) {
        Modifier.pointerInput(track.id, swipeThresholdPx, swipeRevealPx) {
            var totalDragX = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    totalDragX = 0f
                    swipeOffsetPx = 0f
                },
                onDragEnd = {
                    if (shouldQueueTrackOnSwipe(totalDragX, swipeThresholdPx)) {
                        currentOnAddToQueue?.invoke()
                    }
                    swipeOffsetPx = 0f
                },
                onDragCancel = { swipeOffsetPx = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    totalDragX = queueSwipeOffsetAfterDrag(totalDragX, dragAmount, swipeRevealPx)
                    swipeOffsetPx = totalDragX
                    change.consume()
                },
            )
        }
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (onAddToQueue != null) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(AftertoneAccent.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
                    .padding(start = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(20.dp))
                Text("В очередь", color = AftertoneAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffsetPx }
                .then(
                    if (onAddToQueue != null) {
                        Modifier.background(AftertoneSurface, RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                )
                .then(swipeModifier)
                .clickable(onClick = onClick)
                .padding(vertical = 9.dp)
                .semantics {
                    contentDescription = "Воспроизвести ${track.displayTitle} — ${track.displayArtist}"
                    if (onAddToQueue != null) {
                        customActions = listOf(
                            CustomAccessibilityAction(label = "Добавить ${track.displayTitle} в очередь") {
                                onAddToQueue()
                                true
                            },
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(track, Modifier.size(56.dp), cornerRadius = 14)
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = track.displayTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(track.displayArtist, formatDuration(track.durationMs))
                        .filter(String::isNotBlank)
                        .joinToString("  ·  "),
                    color = AftertoneMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reorderActions != null) {
                reorderActions()
            } else {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.semantics {
                        contentDescription = if (isFavorite) "Убрать ${track.displayTitle} из избранного" else "Добавить ${track.displayTitle} в избранное"
                    },
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) AftertoneAccent else AftertoneMuted,
                    )
                }
                androidx.compose.foundation.layout.Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics { contentDescription = "Другие действия: ${track.displayTitle}" },
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AftertoneMuted)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (track.artworkPath == null) R.string.choose_track_artwork else R.string.change_track_artwork)) },
                            onClick = { menuExpanded = false; onChooseArtwork(track.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("Добавить в подборку") },
                            onClick = { menuExpanded = false; onAddToCollection() },
                        )
                        if (onRemoveFromCollection != null) {
                            DropdownMenuItem(
                                text = { Text("Убрать из этой подборки") },
                                onClick = { menuExpanded = false; onRemoveFromCollection() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Удалить копию с телефона", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onRemoveFromDevice() },
                        )
                    }
                }
            }
        }
    }
}

fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val totalSeconds = durationMs / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

fun formatTrackCount(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val noun = when {
        lastTwo in 11..14 -> "треков"
        last == 1 -> "трек"
        last in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $noun"
}
