package com.waizusora.flowery

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }
    private val folders = mutableStateListOf<LibraryFolder>()
    private val tracks = mutableStateListOf<AudioTrackItem>()
    private var status by mutableStateOf("Choose a music folder to begin")
    private var selectedTrack by mutableStateOf<AudioTrackItem?>(null)
    private var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    private var currentTag by mutableStateOf<AudioTag?>(null)
    private var infoTag by mutableStateOf<AudioTag?>(null)
    private var editingTag by mutableStateOf<AudioTag?>(null)
    private var volume by mutableStateOf(1f)
    private val equalizerGains = mutableStateListOf<Float>().apply { repeat(31) { add(0f) } }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var player: FloweryPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreFolders()
        player = FloweryPlayer(this) { message -> runOnUiThread { status = message } }
        setContent {
            FloweryApp(
                folders = folders,
                tracks = tracks,
                status = status,
                selectedTrack = selectedTrack,
                currentTag = currentTag,
                lyrics = lyrics,
                volume = volume,
                equalizerGains = equalizerGains,
                onBandGain = { index, gain -> equalizerGains[index] = gain; player.setEqualizerGains(equalizerGains.toFloatArray()) },
                onAddFolder = { folderPicker.launch(null) },
                onRemoveFolder = ::removeFolder,
                onToggleFolder = ::toggleFolder,
                onScan = ::scanLibrary,
                onPlay = ::playTrack,
                onInspect = ::inspectTrack,
                onEdit = { currentTag?.let { if (it.writable) editingTag = it else infoTag = it } },
                onPause = { player.pause() },
                onResume = { player.resume() },
                onStop = { player.stop(); status = "Stopped" },
                onVolume = { volume = it; player.setVolume(it) },
            )
            infoTag?.let { tag -> MaterialTheme { TagInfoDialog(tag, onDismiss = { infoTag = null }) } }
            editingTag?.let { tag ->
                MaterialTheme { TagEditorDialog(
                    tag = tag,
                    onDismiss = { editingTag = null },
                    onSave = { edited -> editingTag = null; saveTags(edited) }
                ) }
            }
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

    private fun extensionOf(track: AudioTrackItem): String =
        Uri.decode(track.uri.lastPathSegment.orEmpty()).substringAfterLast('.', "")

    private fun readTag(track: AudioTrackItem): AudioTag {
        val bytes = contentResolver.openInputStream(track.uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to open selected audio file")
        val json = JSONObject(NativeAudio.nativeReadTags(bytes, extensionOf(track), cacheDir.absolutePath))
        return AudioTag(
            uri = track.uri,
            fileName = Uri.decode(track.uri.lastPathSegment.orEmpty()),
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            albumArtist = json.optString("albumArtist"),
            genre = json.optString("genre"),
            year = json.optString("year"),
            track = json.optString("track"),
            disc = json.optString("disc"),
            comment = json.optString("comment"),
            composer = json.optString("composer"),
            lyrics = json.optString("lyrics"),
            pictureMime = json.optString("pictureMime"),
            pictureBase64 = json.optString("pictureBase64"),
            tagType = json.optString("tagType"),
            fileType = json.optString("fileType"),
            writable = json.optBoolean("writable", true),
            sampleRate = json.optInt("sampleRate"),
            bitDepth = json.optInt("bitDepth"),
            channels = json.optInt("channels"),
            bitrateKbps = json.optInt("bitrateKbps"),
            durationMs = json.optLong("durationMs")
        )
    }

    private fun playTrack(track: AudioTrackItem) {
        selectedTrack = track
        currentTag = null
        lyrics = emptyList()
        status = "Reading tags and preparing ${track.title}…"
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(track.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to open selected audio file")
                val tag = runCatching { readTag(track) }.getOrNull()
                val parsedLyrics = track.sidecarLyricsUri?.let { lyricUri ->
                    contentResolver.openInputStream(lyricUri)?.bufferedReader()?.use { LrcParser.parse(it.readText()) }
                }.orEmpty()
                runOnUiThread {
                    currentTag = tag
                    lyrics = parsedLyrics
                    if (tag == null) status = "Tags unavailable for this format · playback continues"
                }
                player.play(bytes)
            } catch (error: Throwable) {
                runOnUiThread { status = "Unable to load file: ${error.message}" }
            }
        }
    }

    private fun inspectTrack(track: AudioTrackItem) {
        status = "Reading audio metadata…"
        io.execute {
            try {
                val tag = readTag(track)
                runOnUiThread { infoTag = tag; status = "Metadata loaded with Lofty" }
            } catch (error: Throwable) {
                runOnUiThread { status = "Metadata error: ${error.message}" }
            }
        }
    }

    private fun saveTags(tag: AudioTag) {
        status = "Writing metadata with Lofty…"
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(tag.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to read original audio file")
                val extension = extensionOf(tracks.firstOrNull { it.uri == tag.uri } ?: AudioTrackItem(tag.uri, tag.fileName, "", null))
                val edited = NativeAudio.nativeWriteTags(
                    bytes, extension, cacheDir.absolutePath, tag.title, tag.artist, tag.album, tag.albumArtist,
                    tag.genre, tag.year, tag.track, tag.disc, tag.comment, tag.composer, tag.lyrics
                )
                contentResolver.openOutputStream(tag.uri, "wt")?.use { output ->
                    output.write(edited)
                    output.flush()
                } ?: throw IllegalStateException("The selected document provider does not allow writing this file")
                val updated = readTag(tracks.firstOrNull { it.uri == tag.uri } ?: AudioTrackItem(tag.uri, tag.fileName, "", null))
                runOnUiThread {
                    currentTag = updated
                    infoTag = updated
                    status = "Tags saved to original audio file"
                }
            } catch (error: Throwable) {
                runOnUiThread { status = "Tag write failed: ${error.message}" }
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

data class AudioTag(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val track: String,
    val disc: String,
    val comment: String,
    val composer: String,
    val lyrics: String,
    val pictureMime: String,
    val pictureBase64: String,
    val tagType: String,
    val fileType: String,
    val writable: Boolean,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val bitrateKbps: Int,
    val durationMs: Long
)

private val ISO_BANDS = listOf("20", "25", "31.5", "40", "50", "63", "80", "100", "125", "160", "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k")
private val Gold = Color(0xFFFFC400)
private val DeepGold = Color(0xFFB98A00)
private val Ink = Color(0xFF29251B)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun FloweryApp(
    folders: List<LibraryFolder>,
    tracks: List<AudioTrackItem>,
    status: String,
    selectedTrack: AudioTrackItem?,
    currentTag: AudioTag?,
    lyrics: List<LyricLine>,
    volume: Float,
    equalizerGains: List<Float>,
    onBandGain: (Int, Float) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit,
    onToggleFolder: (LibraryFolder, Boolean) -> Unit,
    onScan: () -> Unit,
    onPlay: (AudioTrackItem) -> Unit,
    onInspect: (AudioTrackItem) -> Unit,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    MaterialTheme {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("FLOWERY", fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                            Text("NOVEL INTO MUSIC", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFD33D), Color(0xFFB68C13), Color(0xFF777982))))
                    .verticalScroll(rememberScrollState())
                    .padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F2E9))
                ) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(300.dp)
                                .combinedClickable(onClick = {}, onLongClick = { selectedTrack?.let(onInspect) }),
                            contentAlignment = Alignment.Center
                        ) {
                            val art = remember(currentTag?.pictureBase64) {
                                runCatching {
                                    val raw = currentTag?.pictureBase64?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
                                    raw?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                                }.getOrNull()
                            }
                            if (art != null) {
                                androidx.compose.foundation.Image(bitmap = art, contentDescription = "Embedded album artwork", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            } else {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFFFFDF5), Color(0xFFFFE9A1)))), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("FLOWERY", color = DeepGold, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
                                        Text("✦", color = Gold, style = MaterialTheme.typography.displayLarge)
                                        Text(currentTag?.album?.ifBlank { "NOVEL INTO MUSIC" } ?: "NOVEL INTO MUSIC", color = DeepGold, style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
                                    }
                                }
                            }
                            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x55000000)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("HOLD ARTWORK · FILE DETAILS", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                Text(currentTag?.fileType ?: "AUDIO", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onEdit).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(currentTag?.title?.ifBlank { selectedTrack?.title ?: "Nothing playing" } ?: selectedTrack?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
                    Text(currentTag?.artist?.ifBlank { "Unknown artist" } ?: "Unknown artist", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.92f))
                    Text("HOLD TITLE OR ARTIST TO EDIT TAGS", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPause, modifier = Modifier.background(DeepGold.copy(alpha = 0.55f), RoundedCornerShape(30.dp))) { Icon(Icons.Default.Pause, "Pause", tint = Color.White) }
                    IconButton(onClick = onResume, modifier = Modifier.size(76.dp).background(DeepGold, RoundedCornerShape(40.dp))) { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(46.dp)) }
                    IconButton(onClick = onStop, modifier = Modifier.background(DeepGold.copy(alpha = 0.55f), RoundedCornerShape(30.dp))) { Icon(Icons.Default.Stop, "Stop", tint = Color.White) }
                }
                Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (currentTag != null) {
                    Text(
                        listOf(currentTag.bitDepth.takeIf { it > 0 }?.let { "$it BIT" }, currentTag.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} KHZ" }, currentTag.bitrateKbps.takeIf { it > 0 }?.let { "$it KBPS" }, currentTag.fileType).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (lyrics.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0x44FFFFFF))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("LYRICS · LRC", color = Color.White, fontWeight = FontWeight.Bold)
                            lyrics.take(3).forEach { Text(it.text, color = Color.White) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onAddFolder, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(" Folder") }
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Text(" Scan") }
                }
                Text("MUSIC FOLDERS", color = Color.White, fontWeight = FontWeight.Bold)
                folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color.White)
                        Text(folder.name, color = Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Checkbox(checked = folder.enabled, onCheckedChange = { onToggleFolder(folder, it) })
                        IconButton(onClick = { onRemoveFolder(folder) }) { Icon(Icons.Default.Delete, "Remove folder", tint = Color.White) }
                    }
                }
                Text("LIBRARY · ${tracks.size} TRACKS", color = Color.White, fontWeight = FontWeight.Bold)
                tracks.forEach { track ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { onPlay(track) }, onLongClick = { onInspect(track) }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = Color.White)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(track.title, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(track.folder, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                        if (selectedTrack?.uri == track.uri) Icon(Icons.Default.PlayArrow, "Selected", tint = Gold)
                    }
                }
                Text("SOFTWARE VOLUME", color = Color.White)
                Slider(value = volume, onValueChange = onVolume)
                Text("31-BAND ISO EQUALIZER · ±12 dB", color = Color.White, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ISO_BANDS.forEachIndexed { index, band ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
                            Text(band, color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Text("${equalizerGains[index].toInt()} dB", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Slider(value = equalizerGains[index], onValueChange = { onBandGain(index, it) }, valueRange = -12f..12f)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TagInfoDialog(tag: AudioTag, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio file information") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "File" to tag.fileName, "Title" to tag.title, "Artist" to tag.artist,
                    "Album" to tag.album, "Album artist" to tag.albumArtist, "Genre" to tag.genre,
                    "Year" to tag.year, "Track" to tag.track, "Disc" to tag.disc,
                    "Composer" to tag.composer, "Comment" to tag.comment, "Tag type" to tag.tagType,
                    "Container" to tag.fileType, "Embedded lyrics" to tag.lyrics,
                    "Artwork" to if (tag.pictureBase64.isBlank()) "Not embedded" else tag.pictureMime,
                    "Tag writing" to if (tag.writable) "Supported by Lofty" else "Read-only for this format",
                    "Sample rate" to tag.sampleRate.takeIf { it > 0 }?.let { "$it Hz" }.orEmpty(),
                    "Bit depth" to tag.bitDepth.takeIf { it > 0 }?.let { "$it bit" }.orEmpty(),
                    "Channels" to tag.channels.takeIf { it > 0 }?.toString().orEmpty(),
                    "Bitrate" to tag.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty(),
                    "Duration" to tag.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60000, (it / 1000) % 60) }.orEmpty()
                ).forEach { (label, value) ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@androidx.compose.runtime.Composable
private fun TagEditorDialog(tag: AudioTag, onDismiss: () -> Unit, onSave: (AudioTag) -> Unit) {
    var title by remember(tag) { mutableStateOf(tag.title) }
    var artist by remember(tag) { mutableStateOf(tag.artist) }
    var album by remember(tag) { mutableStateOf(tag.album) }
    var albumArtist by remember(tag) { mutableStateOf(tag.albumArtist) }
    var genre by remember(tag) { mutableStateOf(tag.genre) }
    var year by remember(tag) { mutableStateOf(tag.year) }
    var track by remember(tag) { mutableStateOf(tag.track) }
    var disc by remember(tag) { mutableStateOf(tag.disc) }
    var composer by remember(tag) { mutableStateOf(tag.composer) }
    var comment by remember(tag) { mutableStateOf(tag.comment) }
    var lyrics by remember(tag) { mutableStateOf(tag.lyrics) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit audio tags · Lofty") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).height(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagEditorField("Title", title) { title = it }
                TagEditorField("Artist", artist) { artist = it }
                TagEditorField("Album", album) { album = it }
                TagEditorField("Album artist", albumArtist) { albumArtist = it }
                TagEditorField("Genre", genre) { genre = it }
                TagEditorField("Year / release date", year) { year = it }
                TagEditorField("Track number", track) { track = it }
                TagEditorField("Disc number", disc) { disc = it }
                TagEditorField("Composer", composer) { composer = it }
                TagEditorField("Comment", comment, singleLine = false) { comment = it }
                TagEditorField("Lyrics", lyrics, singleLine = false) { lyrics = it }
                Text("Artwork is preserved when the selected tag format supports it.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(tag.copy(title=title, artist=artist, album=album, albumArtist=albumArtist, genre=genre, year=year, track=track, disc=disc, composer=composer, comment=comment, lyrics=lyrics)) }) { Text("Save to file") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@androidx.compose.runtime.Composable
private fun TagEditorField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine
    )
}
