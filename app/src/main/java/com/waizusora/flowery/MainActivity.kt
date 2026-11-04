package com.waizusora.flowery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }
    private val folders = mutableStateListOf<LibraryFolder>()
    private val tracks = mutableStateListOf<AudioTrackItem>()
    private var status by mutableStateOf("Choose a music folder to begin")
    private var selectedTrack by mutableStateOf<AudioTrackItem?>(null)
    private var volume by mutableStateOf(1f)
    private var bass by mutableStateOf(0f)
    private var mid by mutableStateOf(0f)
    private var treble by mutableStateOf(0f)
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var player: FloweryPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreFolders()
        player = FloweryPlayer { message -> runOnUiThread { status = message } }
        setContent {
            FloweryApp(
                folders = folders,
                tracks = tracks,
                status = status,
                selectedTrack = selectedTrack,
                volume = volume,
                bass = bass,
                mid = mid,
                treble = treble,
                onAddFolder = { folderPicker.launch(null) },
                onRemoveFolder = ::removeFolder,
                onToggleFolder = ::toggleFolder,
                onScan = ::scanLibrary,
                onPlay = ::playTrack,
                onPause = { player.pause() },
                onResume = { player.resume() },
                onStop = { player.stop(); status = "Stopped" },
                onVolume = { volume = it; player.setVolume(it) },
                onBass = { bass = it; player.bassDb = it },
                onMid = { mid = it; player.midDb = it },
                onTreble = { treble = it; player.trebleDb = it }
            )
        }
    }

    private fun addFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (folders.none { it.uri == uri.toString() }) {
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Folder"
            folders += LibraryFolder(uri.toString(), name, true)
            persistFolders()
            status = "Folder added: $name"
        }
    }

    private fun removeFolder(folder: LibraryFolder) {
        folders.remove(folder)
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        persistFolders()
        status = "Folder removed"
    }

    private fun toggleFolder(folder: LibraryFolder, enabled: Boolean) {
        val index = folders.indexOf(folder)
        if (index >= 0) folders[index] = folder.copy(enabled = enabled)
        persistFolders()
    }

    private fun scanLibrary() {
        status = "Scanning selected folders…"
        io.execute {
            try {
                val found = LibraryScanner(this).scan(folders.toList())
                runOnUiThread {
                    tracks.clear()
                    tracks.addAll(found)
                    status = "Scan complete · ${found.size} audio files"
                }
            } catch (error: Throwable) {
                runOnUiThread { status = "Scan failed: ${error.message}" }
            }
        }
    }

    private fun playTrack(track: AudioTrackItem) {
        selectedTrack = track
        status = "Reading ${track.title}…"
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(track.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to open selected audio file")
                player.play(bytes)
            } catch (error: Throwable) {
                runOnUiThread { status = "Unable to play file: ${error.message}" }
            }
        }
    }

    private fun restoreFolders() {
        val stored = getSharedPreferences("library", MODE_PRIVATE)
            .getStringSet("folders", emptySet()).orEmpty()
        stored.forEach { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size == 3) folders += LibraryFolder(parts[0], parts[1], parts[2].toBoolean())
        }
    }

    private fun persistFolders() {
        getSharedPreferences("library", MODE_PRIVATE).edit()
            .putStringSet("folders", folders.map { "${it.uri}|${it.name}|${it.enabled}" }.toSet())
            .apply()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.stop()
        io.shutdownNow()
        super.onDestroy()
    }
}

data class LibraryFolder(val uri: String, val name: String, val enabled: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun FloweryApp(
    folders: List<LibraryFolder>,
    tracks: List<AudioTrackItem>,
    status: String,
    selectedTrack: AudioTrackItem?,
    volume: Float,
    bass: Float,
    mid: Float,
    treble: Float,
    onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit,
    onToggleFolder: (LibraryFolder, Boolean) -> Unit,
    onScan: () -> Unit,
    onPlay: (AudioTrackItem) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onBass: (Float) -> Unit,
    onMid: (Float) -> Unit,
    onTreble: (Float) -> Unit
) {
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Flowery") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Folder selection", style = MaterialTheme.typography.titleLarge)
                LazyColumn(modifier = Modifier.weight(0.8f, fill = true)) {
                    items(folders, key = { it.uri }) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                                Text(folder.uri, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Checkbox(checked = folder.enabled, onCheckedChange = { onToggleFolder(folder, it) })
                            IconButton(onClick = { onRemoveFolder(folder) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove folder")
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onAddFolder, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(" Add folder")
                    }
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(" Scan")
                    }
                }
                Divider()
                Text("Library · ${tracks.size} tracks", style = MaterialTheme.typography.titleLarge)
                LazyColumn(modifier = Modifier.weight(1.2f, fill = true)) {
                    items(tracks, key = { it.uri.toString() }) { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(track.title, style = MaterialTheme.typography.bodyLarge)
                                Text(track.folder, style = MaterialTheme.typography.bodySmall)
                            }
                            if (selectedTrack?.uri == track.uri) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Selected")
                            }
                        }
                    }
                }
                Text(selectedTrack?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
                    IconButton(onClick = onPause) { Icon(Icons.Default.Pause, contentDescription = "Pause") }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
                }
                Text("Software volume")
                Slider(value = volume, onValueChange = onVolume)
                Text("Equalizer · Bass ${bass.toInt()} dB · Mid ${mid.toInt()} dB · Treble ${treble.toInt()} dB")
                Slider(value = bass, onValueChange = onBass, valueRange = -12f..12f)
                Slider(value = mid, onValueChange = onMid, valueRange = -12f..12f)
                Slider(value = treble, onValueChange = onTreble, valueRange = -12f..12f)
            }
        }
    }
}
