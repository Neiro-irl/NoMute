package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.filterLocalTracks
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackRow
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun LibraryScreen(
    library: LibrarySnapshot,
    isImporting: Boolean,
    onImport: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
    onRemoveFromDevice: (Track) -> Unit,
    onChooseArtwork: (String) -> Unit,
    onMoveTrackInLibrary: (String, Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var isReordering by rememberSaveable { mutableStateOf(false) }
    var previewTracks by remember { mutableStateOf<List<Track>?>(null) }
    val editingEnabled = libraryOrderEditingEnabled(query)
    val filteredTracks = filterLocalTracks(library.tracks, query)
    val displayedTracks = if (isReordering && editingEnabled) previewTracks ?: library.tracks else filteredTracks
    val dragStepPx = with(LocalDensity.current) { 90.dp.toPx() }

    LaunchedEffect(library.tracks) {
        previewTracks = null
    }

    fun beginDrag() {
        previewTracks = library.tracks
    }

    fun previewMove(trackId: String, destinationIndex: Int) {
        val currentTracks = previewTracks ?: library.tracks
        previewTracks = library.copy(tracks = currentTracks).moveTrack(trackId, destinationIndex).tracks
    }

    fun finishDrag(trackId: String, startIndex: Int, destinationIndex: Int) {
        if (startIndex != destinationIndex) onMoveTrackInLibrary(trackId, destinationIndex)
        previewTracks = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ТВОЯ МУЗЫКА · ОФЛАЙН", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    Text("Библиотека", color = AftertoneText, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                    Text(formatTrackCount(library.tracks.size), color = AftertoneMuted, fontSize = 13.sp)
                }
                ActionPill(label = if (isImporting) "Импорт…" else "Добавить", icon = Icons.Filled.Add, onClick = onImport)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { value ->
                    query = value.take(80)
                    if (query.isNotBlank()) {
                        isReordering = false
                        previewTracks = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AftertoneMuted) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Очистить поиск", tint = AftertoneMuted) } }
                } else null,
                placeholder = { Text("Название, артист или альбом", color = AftertoneMuted) },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = AftertoneOutline,
                    focusedContainerColor = AftertoneSurface,
                    unfocusedContainerColor = AftertoneSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        if (library.tracks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                isReordering = !isReordering
                                previewTracks = null
                            },
                            enabled = editingEnabled,
                        ) {
                            Icon(Icons.Filled.DragHandle, contentDescription = null, tint = AftertoneAccent)
                            Text(if (isReordering) "Готово" else "Изменить порядок", color = AftertoneAccent)
                        }
                    }
                    Text(
                        text = when {
                            !editingEnabled -> "Очисти поиск, чтобы изменить порядок медиатеки."
                            isReordering -> "Зажми ручку и перетащи трек; стрелки перемещают его на одну позицию."
                            else -> "Порядок треков используется при воспроизведении всей медиатеки."
                        },
                        color = AftertoneMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        when {
            filteredTracks.isEmpty() && query.isNotBlank() -> item {
                EmptyLibraryState(
                    icon = Icons.Filled.Search,
                    title = "Совпадений нет",
                    message = "Попробуй другое название, имя артиста или альбом.",
                )
            }
            library.tracks.isEmpty() -> item {
                EmptyLibraryState(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Здесь пока тихо",
                    message = "Добавь аудиофайлы, чтобы собрать свою офлайн-медиатеку.",
                    action = {
                        ActionPill(label = if (isImporting) "Импорт…" else "Выбрать аудио", icon = Icons.Filled.Add, onClick = onImport)
                    },
                    note = "При удалении NoMute приватная копия музыки удалится; оригинал останется.",
                )
            }
            else -> {
                item {
                    SectionHeading(title = if (query.isBlank()) "Все треки" else "Результаты · ${filteredTracks.size}")
                }
                itemsIndexed(displayedTracks, key = { _, track -> track.id }) { index, track ->
                    val currentIndex = rememberUpdatedState(index)
                    val itemCount = rememberUpdatedState(displayedTracks.size)
                    val beginDragState = rememberUpdatedState(::beginDrag)
                    val previewMoveState = rememberUpdatedState(::previewMove)
                    val finishDragState = rememberUpdatedState(::finishDrag)
                    val cancelDragState = rememberUpdatedState({ previewTracks = null })
                    Column {
                        TrackRow(
                            track = track,
                            isFavorite = track.id in library.favoriteTrackIds,
                            onClick = { onPlayTrack(track.id) },
                            onAddToQueue = if (isReordering) null else { { onAddToQueue(track) } },
                            onToggleFavorite = { onToggleFavorite(track.id) },
                            onAddToCollection = { onAddToCollection(track.id) },
                            onRemoveFromDevice = { onRemoveFromDevice(track) },
                            onChooseArtwork = onChooseArtwork,
                            reorderActions = if (isReordering && editingEnabled) {
                                {
                                    IconButton(
                                        onClick = { onMoveTrackInLibrary(track.id, index - 1) },
                                        enabled = index > 0,
                                    ) {
                                        Icon(
                                            Icons.Filled.KeyboardArrowUp,
                                            contentDescription = "Переместить ${track.displayTitle} выше",
                                            tint = AftertoneMuted,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "Перетащить ${track.displayTitle}" }
                                            .pointerInput(track.id) {
                                                var startIndex = currentIndex.value
                                                var movingIndex = currentIndex.value
                                                var accumulatedOffsetPx = 0f
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        startIndex = currentIndex.value
                                                        movingIndex = startIndex
                                                        accumulatedOffsetPx = 0f
                                                        beginDragState.value()
                                                    },
                                                    onDragEnd = {
                                                        finishDragState.value(track.id, startIndex, movingIndex)
                                                    },
                                                    onDragCancel = { cancelDragState.value() },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        accumulatedOffsetPx += dragAmount.y
                                                        var destination = nextLibraryReorderIndex(
                                                            currentIndex = movingIndex,
                                                            accumulatedOffsetPx = accumulatedOffsetPx,
                                                            rowStepPx = dragStepPx,
                                                            itemCount = itemCount.value,
                                                        )
                                                        while (destination != null) {
                                                            accumulatedOffsetPx -= if (destination > movingIndex) dragStepPx else -dragStepPx
                                                            movingIndex = destination
                                                            previewMoveState.value(track.id, destination)
                                                            destination = nextLibraryReorderIndex(
                                                                currentIndex = movingIndex,
                                                                accumulatedOffsetPx = accumulatedOffsetPx,
                                                                rowStepPx = dragStepPx,
                                                                itemCount = itemCount.value,
                                                            )
                                                        }
                                                        accumulatedOffsetPx = clearOutOfBoundsLibraryReorderOffset(
                                                            currentIndex = movingIndex,
                                                            accumulatedOffsetPx = accumulatedOffsetPx,
                                                            itemCount = itemCount.value,
                                                        )
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = AftertoneAccent)
                                    }
                                    IconButton(
                                        onClick = { onMoveTrackInLibrary(track.id, index + 1) },
                                        enabled = index < displayedTracks.lastIndex,
                                    ) {
                                        Icon(
                                            Icons.Filled.KeyboardArrowDown,
                                            contentDescription = "Переместить ${track.displayTitle} ниже",
                                            tint = AftertoneMuted,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                        HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.72f), thickness = 0.6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    action: @Composable (() -> Unit)? = null,
    note: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AftertoneMuted, modifier = Modifier.size(34.dp))
        Text(title, color = AftertoneText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
        action?.invoke()
        note?.let { Text(it, color = AftertoneMuted, fontSize = 12.sp, lineHeight = 17.sp) }
        Spacer(Modifier.height(1.dp))
    }
}
