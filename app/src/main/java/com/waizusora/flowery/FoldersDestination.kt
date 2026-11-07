package com.waizusora.flowery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun FoldersDestination(
    folders: List<LibraryFolder>,
    tracks: List<AudioTrackItem>,
    selectedFolder: LibraryFolder?,
    virtualCueFolder: Boolean,
    selectedTrack: AudioTrackItem?,
    foreground: Color,
    muted: Color,
    libraryScanActive: Boolean,
    refreshRotation: Float,
    onBackToPlayer: () -> Unit,
    onScan: () -> Unit,
    onRefreshFolder: (LibraryFolder) -> Unit,
    onSelectedFolder: (LibraryFolder?) -> Unit,
    onVirtualCueFolder: (Boolean) -> Unit,
    onPlay: (AudioTrackItem, List<AudioTrackItem>) -> Unit
) {

                    if (selectedFolder == null && !virtualCueFolder) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onBackToPlayer() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Now playing", tint = foreground)
                            }
                            Heading("Folders", foreground)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onScan, enabled = !libraryScanActive) {
                                Icon(Icons.Default.Refresh, "Refresh library", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.graphicsLayer { rotationZ = if (libraryScanActive) refreshRotation else 0f })
                            }
                        }
                        Text("SCANNED FOLDERS", color = muted, style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold)
                        val cueVirtualTracks = tracks.filter { it.cuePlaylist != null }
                        val playlistVirtualTracks = tracks.filter { it.playlistName != null }
                        if (cueVirtualTracks.isNotEmpty() || playlistVirtualTracks.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().clickable { onVirtualCueFolder(true); onSelectedFolder(null) }
                                .padding(horizontal = 4.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderSpecial, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(27.dp))
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text("CUE & PLAYLISTS", color = foreground, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                    Text("Virtual playlists · CUE, M3U, M3U8, PLS", color = muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Text((cueVirtualTracks.size + playlistVirtualTracks.size).toString(), color = muted)
                            }
                            HorizontalDivider(color = muted.copy(alpha = .16f))
                        }
                        folders.forEach { folder ->
                            val folderTracks = tracks.filter { it.folder == folder.name }
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelectedFolder(folder); onVirtualCueFolder(false) }
                                    .padding(horizontal = 4.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFFD66B9A),
                                    modifier = Modifier.size(27.dp))
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(folder.name, color = foreground, fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    Text("FLOWERY LIBRARY", color = muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Default.MusicNote, null, tint = muted, modifier = Modifier.size(15.dp))
                                Text("  ${folderTracks.size}", color = muted, style = MaterialTheme.typography.labelMedium)
                            }
                            HorizontalDivider(color = muted.copy(alpha = .16f))
                        }
                        if (folders.isEmpty()) Text("No folders added. Add folders in Settings → Library.", color = muted)
                    } else if (virtualCueFolder) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onVirtualCueFolder(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to folders", tint = foreground) }
                            Heading("CUE & PLAYLISTS", foreground)
                        }
                        val cueTracks = tracks.filter { it.cuePlaylist != null }
                        val m3uTracks = tracks.filter { it.playlistName != null }
                        cueTracks.groupBy { it.cuePlaylist }.forEach { (name, entries) ->
                            Text(name ?: "CUE playlist", color = Color(0xFFD66B9A), fontWeight = FontWeight.SemiBold)
                            entries.sortedBy { it.cueStartMs ?: 0L }.forEach { item ->
                                Row(Modifier.fillMaxWidth().clickable { onPlay(item, entries) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(25.dp))
                                    Text(item.title, color = foreground, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                    if (selectedTrack?.uri == item.uri && selectedTrack?.cueStartMs == item.cueStartMs) Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary)
                                }
                                HorizontalDivider(color = muted.copy(alpha = .16f))
                            }
                        }
                        m3uTracks.groupBy { it.playlistName }.forEach { (name, entries) ->
                            Text(name ?: "Playlist", color = Color(0xFFD66B9A), fontWeight = FontWeight.SemiBold)
                            entries.sortedBy { it.playlistOrder ?: Int.MAX_VALUE }.forEach { item ->
                                Row(Modifier.fillMaxWidth().clickable { onPlay(item, entries) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(25.dp))
                                    Text(item.title, color = foreground, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                }
                                HorizontalDivider(color = muted.copy(alpha = .16f))
                            }
                        }
                        if (cueTracks.isEmpty() && m3uTracks.isEmpty()) Text("No CUE or playlist files were indexed.", color = muted)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onSelectedFolder(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to folders", tint = foreground)
                            }
                            Column(Modifier.weight(1f)) {
                                Heading(selectedFolder!!.name, foreground)
                                Text("FLOWERY LIBRARY", color = muted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onRefreshFolder(selectedFolder!!) }, enabled = !libraryScanActive) {
                                Icon(Icons.Default.Refresh, "Refresh folder", tint = Color(0xFFD66B9A),
                                    modifier = Modifier.graphicsLayer { rotationZ = if (libraryScanActive) refreshRotation else 0f })
                            }
                        }
                        val folderTracks = tracks.filter { it.folder == selectedFolder!!.name }
                        Text("${folderTracks.size} songs", color = muted)
                        folderTracks.forEach { track ->
                            Row(Modifier.fillMaxWidth().clickable { onPlay(track, folderTracks) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(28.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(track.title, color = foreground, fontWeight = FontWeight.Medium)
                                    Text(track.mimeType ?: track.folder, color = muted, style = MaterialTheme.typography.bodySmall)
                                }
                                if (selectedTrack?.uri == track.uri) Icon(Icons.Default.GraphicEq, "Playing", tint = Color(0xFF1E90FF))
                            }
                            HorizontalDivider(color = muted.copy(alpha = .2f))
                        }
                        if (folderTracks.isEmpty()) Text("No songs indexed yet. Use Refresh to update this folder.", color = muted)
                    }
                
}
