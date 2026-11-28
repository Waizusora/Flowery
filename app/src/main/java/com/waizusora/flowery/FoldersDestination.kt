package com.waizusora.flowery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Folder browser for the scanned library.
 *
 * Top level shows a flat list of every folder path that contains at least one
 * indexed audio track, plus the virtual CUE & Playlists entry when present.
 * Selecting a folder lists only the tracks that belong to that exact path;
 * subfolders are not nested further. Track rows show title above artist.
 * When embedded tags are missing, title falls back to the file stem and
 * artist falls back to "Unknown artist".
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // Regular (non-playlist / non-CUE) tracks only for the folder index.
    val libraryTracks = remember(tracks) {
        tracks.filter { it.cuePlaylist == null && it.playlistName == null }
    }

    // Distinct folder paths that contain at least one regular track, sorted.
    val scannedFolderPaths = remember(libraryTracks) {
        libraryTracks
            .map { it.folder }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    if (selectedFolder == null && !virtualCueFolder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onBackToPlayer() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Now playing", tint = foreground)
            }
            Heading("Folders", foreground)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onScan, enabled = !libraryScanActive) {
                Icon(
                    Icons.Default.Refresh,
                    "Refresh library",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = if (libraryScanActive) refreshRotation else 0f
                    }
                )
            }
        }
        Text(
            "SCANNED FOLDERS",
            color = muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )

        val cueVirtualTracks = tracks.filter { it.cuePlaylist != null }
        val playlistVirtualTracks = tracks.filter { it.playlistName != null }
        if (cueVirtualTracks.isNotEmpty() || playlistVirtualTracks.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onVirtualCueFolder(true)
                        onSelectedFolder(null)
                    }
                    .padding(horizontal = 4.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.FolderSpecial,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(27.dp)
                )
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        "CUE & PLAYLISTS",
                        color = foreground,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Virtual playlists · CUE, M3U, M3U8, PLS",
                        color = muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    (cueVirtualTracks.size + playlistVirtualTracks.size).toString(),
                    color = muted
                )
            }
            HorizontalDivider(color = muted.copy(alpha = .16f))
        }

        if (scannedFolderPaths.isEmpty() && folders.isEmpty()) {
            Text(
                "No folders added. Add folders in Settings → Library.",
                color = muted
            )
        } else if (scannedFolderPaths.isEmpty()) {
            Text(
                "No songs indexed yet. Use Refresh after adding folders in Settings → Library.",
                color = muted
            )
        }

        scannedFolderPaths.forEach { path ->
            val folderTracks = libraryTracks.filter { it.folder == path }
            val displayName = path.substringAfterLast('/')
            val secondary = if (path.contains('/')) path else "FLOWERY LIBRARY"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelectedFolder(LibraryFolder(uri = "", name = path, enabled = true))
                        onVirtualCueFolder(false)
                    }
                    .padding(horizontal = 4.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(27.dp)
                )
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        displayName,
                        color = foreground,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    Text(
                        secondary,
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                }
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    tint = muted,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    "  ${folderTracks.size}",
                    color = muted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            HorizontalDivider(color = muted.copy(alpha = .16f))
        }
    } else if (virtualCueFolder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onVirtualCueFolder(false) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to folders", tint = foreground)
            }
            Heading("CUE & PLAYLISTS", foreground)
        }
        val cueTracks = tracks.filter { it.cuePlaylist != null }
        val m3uTracks = tracks.filter { it.playlistName != null }
        cueTracks.groupBy { it.cuePlaylist }.forEach { (name, entries) ->
            Text(
                name ?: "CUE playlist",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            entries.sortedBy { it.cueStartMs ?: 0L }.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(item, entries) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(25.dp)
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            item.title,
                            color = foreground,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Text(
                            item.artist.ifBlank { name ?: "CUE" },
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                    if (selectedTrack?.uri == item.uri &&
                        selectedTrack?.cueStartMs == item.cueStartMs
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            "Playing",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                HorizontalDivider(color = muted.copy(alpha = .16f))
            }
        }
        m3uTracks.groupBy { it.playlistName }.forEach { (name, entries) ->
            Text(
                name ?: "Playlist",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            entries.sortedBy { it.playlistOrder ?: Int.MAX_VALUE }.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(item, entries) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(25.dp)
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            item.title,
                            color = foreground,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Text(
                            item.artist.ifBlank { name ?: "Playlist" },
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                }
                HorizontalDivider(color = muted.copy(alpha = .16f))
            }
        }
        if (cueTracks.isEmpty() && m3uTracks.isEmpty()) {
            Text("No CUE or playlist files were indexed.", color = muted)
        }
    } else {
        val currentPath = selectedFolder!!.name
        val displayName = currentPath.substringAfterLast('/')
        val folderTracks = libraryTracks.filter { it.folder == currentPath }
        val rootName = currentPath.substringBefore('/')

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelectedFolder(null) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to folders", tint = foreground)
            }
            Column(Modifier.weight(1f)) {
                Heading(displayName, foreground)
                Text(
                    if (currentPath.contains('/')) currentPath else "FLOWERY LIBRARY",
                    color = muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
            }
            val rootFolder = folders.firstOrNull {
                it.name.equals(rootName, ignoreCase = true)
            } ?: LibraryFolder(uri = "", name = rootName, enabled = true)
            IconButton(
                onClick = { onRefreshFolder(rootFolder) },
                enabled = !libraryScanActive
            ) {
                Icon(
                    Icons.Default.Refresh,
                    "Refresh folder",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = if (libraryScanActive) refreshRotation else 0f
                    }
                )
            }
        }

        Text(
            "${folderTracks.size} songs",
            color = muted,
            style = MaterialTheme.typography.labelMedium
        )

        folderTracks.forEach { track ->
            val displayArtist = track.artist.ifBlank { "Unknown artist" }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(track, folderTracks) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        track.title,
                        color = foreground,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    Text(
                        displayArtist,
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                }
                if (selectedTrack?.uri == track.uri &&
                    selectedTrack?.cueStartMs == null &&
                    selectedTrack?.playlistName == null
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        "Playing",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider(color = muted.copy(alpha = .2f))
        }

        if (folderTracks.isEmpty()) {
            Text(
                "No songs indexed in this folder. Use Refresh to update the library.",
                color = muted
            )
        }
    }
}
