package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackArtwork
import com.neiro.aftertone.ui.components.TrackRow
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun CollectionDetailScreen(
    library: LibrarySnapshot,
    playlist: Playlist?,
    favorites: Boolean,
    onBack: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onPlayTracks: (List<Track>, String) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
    onRemoveFromDevice: (Track) -> Unit,
    onChooseArtwork: (String) -> Unit,
    onChoosePlaylistArtwork: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onMoveTrack: (String, String, Int) -> Unit,
) {
    val tracks = if (favorites) {
        library.tracks.filter { it.id in library.favoriteTrackIds }
    } else {
        playlist?.tracksInOrder(library.tracks).orEmpty()
    }
    val title = if (favorites) "Избранное" else playlist?.name ?: "Подборка"
    val heroTrack = tracks.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AftertoneText)
                }
                Text(if (favorites) "ИЗБРАННОЕ" else "ПОДБОРКА", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (heroTrack != null || playlist?.artworkPath != null) {
                    TrackArtwork(
                        track = heroTrack,
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                        cornerRadius = 22,
                        artworkPathOverride = playlist?.artworkPath,
                        artworkDescription = playlist?.let { "Обложка подборки: ${it.name}" },
                        fallbackLabel = title,
                    )
                }
                Text(title, color = AftertoneText, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatTrackCount(tracks.size)} · хранится на этом устройстве", color = AftertoneMuted, fontSize = 13.sp)
                if (playlist != null) {
                    ActionPill(
                        label = if (playlist.artworkPath == null) "Выбрать обложку" else "Изменить обложку",
                        icon = Icons.Filled.Add,
                        onClick = { onChoosePlaylistArtwork(playlist.id) },
                    )
                }
                if (tracks.isNotEmpty()) {
                    ActionPill(
                        label = "Слушать подборку",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onPlayTracks(tracks, tracks.first().id) },
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Spacer(Modifier.height(34.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(if (favorites) Icons.Filled.Favorite else Icons.Filled.MusicNote, contentDescription = null, tint = AftertoneMuted, modifier = Modifier.size(35.dp))
                    Text(if (favorites) "Сохранённые треки появятся здесь." else "В этой подборке пока пусто.", color = AftertoneText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Добавляй музыку через меню в библиотеке.", color = AftertoneMuted, fontSize = 13.sp)
                }
            }
        } else {
            item {
                Text("ТРЕКИ В ПОДБОРКЕ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                Column {
                    TrackRow(
                        track = track,
                        isFavorite = track.id in library.favoriteTrackIds,
                        onClick = { onPlayTrack(track.id) },
                        onAddToQueue = { onAddToQueue(track) },
                        onToggleFavorite = { onToggleFavorite(track.id) },
                        onAddToCollection = { onAddToCollection(track.id) },
                        onRemoveFromDevice = { onRemoveFromDevice(track) },
                        onChooseArtwork = onChooseArtwork,
                        onRemoveFromCollection = playlist?.id?.let { playlistId ->
                            { onRemoveFromPlaylist(playlistId, track.id) }
                        },
                    )
                    if (playlist != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { onMoveTrack(playlist.id, track.id, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Переместить ${track.displayTitle} выше",
                                    tint = AftertoneMuted,
                                )
                            }
                            IconButton(
                                onClick = { onMoveTrack(playlist.id, track.id, index + 1) },
                                enabled = index < tracks.lastIndex,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Переместить ${track.displayTitle} ниже",
                                    tint = AftertoneMuted,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.72f), thickness = 0.6.dp)
                }
            }
        }
    }
}
