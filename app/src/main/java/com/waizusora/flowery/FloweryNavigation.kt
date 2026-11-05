package com.waizusora.flowery

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private enum class Destination { PLAYER, FOLDERS, DSP, SETTINGS }
private enum class SettingsPage { ROOT, AUDIO, AUDIO_INFO, LIBRARY, APPEARANCE, OTHER, ABOUT, OPEN_SOURCE, TRANSLATOR, CONTRIBUTORS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FloweryNavigation(
    context: Context, folders: List<LibraryFolder>, tracks: List<AudioTrackItem>,
    isPlaying: Boolean, shuffleEnabled: Boolean, repeatMode: Int, selectedTrack: AudioTrackItem?,
    currentTag: AudioTag?, lyrics: List<LyricLine>, volume: Float, positionMs: Long, durationMs: Long,
    equalizerGains: List<Float>, status: String,
    onBandGain: (Int, Float) -> Unit, onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit, onToggleFolder: (LibraryFolder, Boolean) -> Unit,
    onScan: () -> Unit, onRefreshFolder: (LibraryFolder) -> Unit,
    onPlay: (AudioTrackItem, List<AudioTrackItem>) -> Unit,
    onInspect: (AudioTrackItem) -> Unit, onEdit: () -> Unit, onTogglePlayback: () -> Unit,
    onVolume: (Float) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onSeek: (Long) -> Unit,
    onBatterySettings: () -> Unit, onCheckUpdate: () -> Unit, onUsbDacClick: () -> Unit
) {
    var destination by remember { mutableStateOf(Destination.PLAYER) }
    var settingsPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var selectedFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var lyricsVisible by remember { mutableStateOf(false) }
    val prefs = remember(context) { context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "Auto") ?: "Auto") }
    val systemDark = isSystemInDarkTheme()
    val lightTheme = theme == "Light" || (theme == "Auto" && !systemDark)
    val foreground = if (lightTheme) Color(0xFF20212A) else Color.White
    val muted = foreground.copy(alpha = .72f)
    val background = Brush.verticalGradient(when (theme) {
        "Light" -> listOf(Color(0xFFF2F3F7), Color(0xFFD9DCE4), Color(0xFFC5C9D2))
        "Black" -> listOf(Color(0xFF101014), Color(0xFF191922), Color(0xFF24242D))
        else -> if (systemDark) listOf(Color(0xFF24262C), Color(0xFF3B3E45), Color(0xFF53565D))
            else listOf(Color(0xFFF2F3F7), Color(0xFFD9DCE4), Color(0xFFC5C9D2))
    })

    Scaffold(containerColor = Color.Transparent, bottomBar = {
        Row(Modifier.fillMaxWidth().background(Color.Transparent).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            NavIcon(Icons.Default.Folder, "Folders", destination == Destination.FOLDERS, foreground) {
                destination = Destination.FOLDERS; selectedFolder = null
            }
            NavIcon(Icons.Default.Lyrics, "Lyrics", lyricsVisible, foreground) {
                if (destination != Destination.PLAYER) destination = Destination.PLAYER
                lyricsVisible = !lyricsVisible
            }
            val usbExclusiveActive = status.contains("USB exclusive")
            IconButton(onClick = onUsbDacClick) {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = if (usbExclusiveActive) "USB DAC bit-perfect active" else "USB DAC",
                    tint = if (usbExclusiveActive) Color(0xFF1E90FF) else foreground
                )
            }
            NavIcon(Icons.Default.Equalizer, "DSP", destination == Destination.DSP, foreground) { destination = Destination.DSP }
            NavIcon(Icons.Default.Settings, "Settings", destination == Destination.SETTINGS, foreground) {
                destination = Destination.SETTINGS; settingsPage = SettingsPage.ROOT
            }
        }
    }) { inset ->
        Column(Modifier.fillMaxSize().background(background).padding(inset)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (destination) {
                Destination.PLAYER -> {
                    Card(Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .08f))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (lyricsVisible) {
                                Column(Modifier.fillMaxSize().clickable { lyricsVisible = false }
                                    .verticalScroll(rememberScrollState()).padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("LYRICS · LRC", color = foreground, fontWeight = FontWeight.Bold)
                                    if (lyrics.isEmpty()) Text("No synchronized LRC lyrics found.", color = muted)
                                    else lyrics.take(14).forEach { Text(it.text, color = foreground, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                                    Text("Tap to show album artwork", color = muted, style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                val art = remember(currentTag?.pictureBase64) {
                                    runCatching {
                                        val bytes = currentTag?.pictureBase64?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
                                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                                    }.getOrNull()
                                }
                                if (art != null) Image(art, "Album artwork", Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                                else Icon(Icons.Default.MusicNote, "No artwork", tint = muted, modifier = Modifier.size(80.dp))

                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(currentTag?.title?.ifBlank { selectedTrack?.title ?: "Nothing playing" } ?: selectedTrack?.title ?: "Nothing playing",
                            Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = foreground,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, maxLines = 1)
                        Text(currentTag?.artist?.ifBlank { "Unknown artist" } ?: "Unknown artist",
                            Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = muted,
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleEnabled) Color(0xFF1E90FF) else foreground) }
                        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = foreground, modifier = Modifier.size(30.dp)) }
                        IconButton(onClick = onTogglePlayback, Modifier.size(76.dp).background(foreground.copy(alpha = .13f), RoundedCornerShape(40.dp))) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = foreground, modifier = Modifier.size(46.dp))
                        }
                        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = foreground, modifier = Modifier.size(30.dp)) }
                        IconButton(onClick = onRepeat) { Icon(if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Repeat", tint = if (repeatMode == 0) foreground else Color(0xFF1E90FF)) }
                    }
                    if (durationMs > 0) {
                        Slider(value = positionMs.coerceIn(0L, durationMs).toFloat(), onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..durationMs.toFloat())
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(
                                onClick = { selectedTrack?.let(onInspect) },
                                enabled = selectedTrack != null,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Info, "File information", tint = Color(0xFF7452A6),
                                    modifier = Modifier.size(30.dp))
                            }
                            Text(
                                "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
                                color = foreground,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                            IconButton(
                                onClick = onEdit,
                                enabled = selectedTrack != null,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Edit, "Edit audio tags", tint = Color(0xFFD66B9A),
                                    modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                    currentTag?.let { tag ->
                        val details = listOfNotNull(tag.bitDepth.takeIf { it > 0 }?.let { "Bit depth: $it-bit" },
                            tag.sampleRate.takeIf { it > 0 }?.let { "Sample rate: ${"%.1f".format(Locale.ROOT, it / 1000.0)} kHz" },
                            tag.bitrateKbps.takeIf { it > 0 }?.let { "Bit rate: $it kbps" },
                            tag.channels.takeIf { it > 0 }?.let { "Channel: $it ch" })
                        if (details.isNotEmpty()) Text(details.joinToString("  ·  "), color = muted,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                Destination.FOLDERS -> {
                    if (selectedFolder == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { destination = Destination.PLAYER; lyricsVisible = false }) {
                                Icon(Icons.Default.ArrowBack, "Now playing", tint = foreground)
                            }
                            Heading("Folders", foreground)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onScan) {
                                Icon(Icons.Default.Refresh, "Refresh library", tint = Color(0xFFD66B9A))
                            }
                        }
                        Text("Your scanned folders", color = muted, style = MaterialTheme.typography.labelSmall)
                        folders.forEach { folder ->
                            val folderTracks = tracks.filter { it.folder == folder.name }
                            Card(
                                Modifier.fillMaxWidth().clickable { selectedFolder = folder },
                                colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .08f)),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(28.dp))
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(folder.name, color = foreground, fontWeight = FontWeight.SemiBold)
                                        Text("${folderTracks.size} songs", color = muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = muted)
                                }
                            }
                        }
                        if (folders.isEmpty()) Text("No folders added. Add folders in Settings → Library.", color = muted)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedFolder = null }) {
                                Icon(Icons.Default.ArrowBack, "Back to folders", tint = foreground)
                            }
                            Column(Modifier.weight(1f)) {
                                Heading(selectedFolder!!.name, foreground)
                                Text("FLOWERY LIBRARY", color = muted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onRefreshFolder(selectedFolder!!) }) {
                                Icon(Icons.Default.Refresh, "Refresh folder", tint = Color(0xFFD66B9A))
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
                Destination.DSP -> {
                    Heading("DSP", foreground)
                    Text("Software volume", color = foreground, fontWeight = FontWeight.SemiBold)
                    Slider(value = volume, onValueChange = onVolume)
                    Text("${(volume * 100).toInt()}%", color = muted, modifier = Modifier.align(Alignment.End))
                    Text("31-band ISO equalizer · ±12 dB", color = foreground, fontWeight = FontWeight.SemiBold)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111214)),
                        shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                            ISO_BANDS.forEachIndexed { index, band ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                                    VerticalEqualizerSlider(equalizerGains[index], { onBandGain(index, it) }, Modifier.height(250.dp).width(44.dp))
                                    Text(band, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    Text(String.format(Locale.ROOT, "%.1f", equalizerGains[index]), color = Color.White.copy(alpha = .72f),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Destination.SETTINGS -> SettingsContent(settingsPage, foreground, muted, theme,
                    status = status, onBack = { settingsPage = when (settingsPage) {
                        SettingsPage.AUDIO, SettingsPage.LIBRARY, SettingsPage.APPEARANCE, SettingsPage.OTHER, SettingsPage.ABOUT -> SettingsPage.ROOT
                        SettingsPage.AUDIO_INFO -> SettingsPage.AUDIO
                        SettingsPage.OPEN_SOURCE, SettingsPage.TRANSLATOR, SettingsPage.CONTRIBUTORS -> SettingsPage.ABOUT
                        SettingsPage.ROOT -> SettingsPage.ROOT
                    } },
                    navigate = { settingsPage = it },
                    changeTheme = { theme = it; prefs.edit().putString("theme", it).apply() },
                    battery = onBatterySettings, update = onCheckUpdate,
                    openFolders = { destination = Destination.FOLDERS; selectedFolder = null },
                    addFolder = onAddFolder, removeFolder = onRemoveFolder,
                    folders = folders, tracks = tracks, scan = onScan,
                    playTrack = onPlay)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, tint: Color, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(52.dp)) {
        Icon(icon, label, tint = if (active) Color(0xFF1E90FF) else tint, modifier = Modifier.size(27.dp))
    }
}
@Composable private fun Heading(text: String, color: Color) { Text(text, color = color, fontSize = 25.sp, fontWeight = FontWeight.SemiBold) }

@Composable
private fun SettingsContent(page: SettingsPage, color: Color, muted: Color, theme: String,
    onBack: () -> Unit, navigate: (SettingsPage) -> Unit, changeTheme: (String) -> Unit,
    battery: () -> Unit, update: () -> Unit, openFolders: () -> Unit,
    addFolder: () -> Unit, removeFolder: (LibraryFolder) -> Unit, status: String,
    folders: List<LibraryFolder>, tracks: List<AudioTrackItem>, scan: () -> Unit,
    playTrack: (AudioTrackItem, List<AudioTrackItem>) -> Unit) {
    if (page != SettingsPage.ROOT) Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = color) }
        Heading(when(page) {
            SettingsPage.AUDIO -> "Audio"; SettingsPage.AUDIO_INFO -> "Information Audio"; SettingsPage.LIBRARY -> "Library"
            SettingsPage.APPEARANCE -> "Appearance"; SettingsPage.OTHER -> "Other"; SettingsPage.ABOUT -> "About"; SettingsPage.OPEN_SOURCE -> "Open Source"
            SettingsPage.TRANSLATOR -> "Translator"; SettingsPage.CONTRIBUTORS -> "Contributor"; SettingsPage.ROOT -> "Settings"
        }, color)
    } else Heading("Settings", color)
    when(page) {
        SettingsPage.ROOT -> {
            SettingCard("Audio", "Playback engine and processing", color, muted) { navigate(SettingsPage.AUDIO) }
            SettingCard("Appearance", "Theme and language", color, muted) { navigate(SettingsPage.APPEARANCE) }
            SettingCard("Library", "Folder management and scanning", color, muted) { navigate(SettingsPage.LIBRARY) }
            SettingCard("Other", "Battery optimization", color, muted) { navigate(SettingsPage.OTHER) }
            SettingCard("About", "Open-source licenses, credits and updates", color, muted, Icons.Default.Info) { navigate(SettingsPage.ABOUT) }
        }
        SettingsPage.AUDIO -> SettingCard("Information Audio", "Decoding, resampling, equalizer and output", color, muted) { navigate(SettingsPage.AUDIO_INFO) }
        SettingsPage.AUDIO_INFO -> {
            Detail("Decoder", "Symphonia · progressive packet decoding", color, muted)
            Detail("Resampler", "Rubato · persistent streaming state", color, muted)
            Detail("Equalizer", "31-band ISO biquad EQ · ±12 dB", color, muted)
            Detail("Output", "AAudio · bounded PCM ring buffer", color, muted)
            Detail("Pipeline", "SAF file → Symphonia decoder → Rubato resampler (when required) → 31-band EQ → bounded PCM ring → AAudio", color, muted)
            Detail("Playback status", status, color, muted)
            Detail("Runtime telemetry", "Per-stage timing, decoder throughput, and live DSP meters are not currently exposed by the engine.", color, muted)
        }
        SettingsPage.LIBRARY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scanned music", color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                    Text("${tracks.size} songs saved in this device", color = muted, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = scan, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6))) {
                    Icon(Icons.Default.Refresh, null)
                    Text(" Scan")
                }
            }
            Text("FOLDER SELECTION", color = color, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text("Remove only removes a folder from Flowery's library list. Original folders and files are never deleted.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = addFolder, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6)),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text(" ADD")
            }
            folders.forEach { folder ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(folder.name, color = color, fontWeight = FontWeight.Medium)
                        Text("Included in library scan", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { removeFolder(folder) }) {
                        Icon(Icons.Default.Close, "Remove folder from Flowery", tint = color, modifier = Modifier.size(25.dp))
                    }
                }
                HorizontalDivider(color = muted.copy(alpha = .16f))
            }
            if (folders.isEmpty()) Text("No folders added yet. Tap ADD to choose one or more folders.", color = muted)
            if (tracks.isEmpty()) {
                Text("Your scanned songs will appear here. The library index is saved, so opening this page will not start another scan.", color = muted)
            } else {
                tracks.forEach { track ->
                    Row(
                        Modifier.fillMaxWidth().clickable { playTrack(track, tracks) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(42.dp).background(color.copy(alpha = .09f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD66B9A))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(track.title, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(track.folder, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Icon(Icons.Default.PlayArrow, "Play", tint = color.copy(alpha = .7f))
                    }
                    HorizontalDivider(color = muted.copy(alpha = .16f))
                }
            }
            Text(status, color = muted, style = MaterialTheme.typography.labelSmall)
        }
        SettingsPage.APPEARANCE -> {
            Text("Theme", color = color, fontWeight = FontWeight.SemiBold)
            listOf("Light", "Black", "Auto").forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { changeTheme(option) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(theme == option, { changeTheme(option) }); Text(option, color = color)
                }
            }
            HorizontalDivider(color = muted.copy(alpha = .25f))
            Text("Language", color = color, fontWeight = FontWeight.SemiBold)
            Text("Language selection is a concept for now. Localization will be implemented later.", color = muted)
            Text("System default · English · Bahasa Indonesia", color = muted)
        }
        SettingsPage.OTHER -> {
            SettingCard("Battery optimization", "Open Android battery optimization settings", color, muted, Icons.Default.BatteryFull, battery)
        }
        SettingsPage.ABOUT -> {
            SettingCard("Open Source", "Libraries and licenses used by Flowery", color, muted, Icons.Default.Code) { navigate(SettingsPage.OPEN_SOURCE) }
            SettingCard("Translator", "Translation credits · planned", color, muted, Icons.Default.Translate) { navigate(SettingsPage.TRANSLATOR) }
            SettingCard("Contributor", "Project contributors", color, muted, Icons.Default.Groups) { navigate(SettingsPage.CONTRIBUTORS) }
            SettingCard("Update", "Open GitHub releases in browser", color, muted, Icons.Default.SystemUpdate, update)
        }
        SettingsPage.OPEN_SOURCE -> {
            Text("Third-party components", color = muted)
            Detail("Symphonia", "MPL-2.0", color, muted)
            Detail("Rubato", "MIT OR Apache-2.0", color, muted)
            Detail("Lofty", "MIT OR Apache-2.0", color, muted)
            Detail("Jetpack Compose", "Compose BOM 2024.12.01 · UI and Material 3 · Apache-2.0", color, muted)
            Detail("Activity Compose", "androidx.activity:activity-compose 1.9.3 · Apache-2.0", color, muted)
            Detail("Lifecycle", "lifecycle-runtime-ktx and lifecycle-viewmodel-compose 2.8.7 · Apache-2.0", color, muted)
            Detail("Room", "room-runtime and room-ktx 2.6.1 · Apache-2.0", color, muted)
            Detail("DataStore", "datastore-preferences 1.1.1 · Apache-2.0", color, muted)
            Detail("AndroidX Core", "core-ktx 1.15.0 · Apache-2.0", color, muted)
            Detail("Android media", "androidx.media:media 1.7.0 · Apache-2.0", color, muted)
            Detail("Document provider", "androidx.documentfile:documentfile 1.0.1 · Apache-2.0", color, muted)
            Detail("AAudio", "Android NDK platform API", color, muted)
            Detail("JNI", "jni 0.21 · Rust/Java native interface", color, muted)
            Detail("Audio adapter buffers", "audioadapter-buffers 5.1", color, muted)
            Detail("JSON", "serde_json 1", color, muted)
            Detail("Base64", "base64 0.22", color, muted)
            Detail("Native platform bindings", "libc 0.2", color, muted)
            Text("Consult the exact dependency versions and bundled license notices for the complete license inventory, including transitive dependencies.", color = muted,
                style = MaterialTheme.typography.bodySmall)
        }
        SettingsPage.TRANSLATOR -> Text("Translator credits will be listed when localization work begins.", color = muted)
        SettingsPage.CONTRIBUTORS -> Text("Contributor credits will be listed when contributions are recorded and confirmed.", color = muted)
    }
}
@Composable
private fun SettingCard(title: String, subtitle: String, color: Color, muted: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.ChevronRight, action: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = action), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .09f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(27.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, color = muted, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted)
        }
    }
}
@Composable
private fun Detail(title: String, value: String, color: Color, muted: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = color, fontWeight = FontWeight.SemiBold); Text(value, color = muted)
    }
}
