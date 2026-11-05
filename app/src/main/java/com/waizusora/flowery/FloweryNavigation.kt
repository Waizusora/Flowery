package com.waizusora.flowery

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private enum class Destination { WELCOME, PLAYER, FOLDERS, DSP, SETTINGS }
private enum class SettingsPage { ROOT, AUDIO, AUDIO_INFO, AUDIO_DIAGNOSTICS, LIBRARY, APPEARANCE, OTHER, ABOUT, OPEN_SOURCE, TRANSLATOR, CONTRIBUTORS }

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
    onBatterySettings: () -> Unit, onCheckUpdate: () -> Unit, onUsbDacClick: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    var destination by remember(context) {
        mutableStateOf(if (context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
            .getBoolean("welcome_complete", false) || folders.isNotEmpty()) Destination.PLAYER else Destination.WELCOME)
    }
    var settingsPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var selectedFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var virtualCueFolder by remember { mutableStateOf(false) }
    var lyricsVisible by remember { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()
    val activeLyricIndex = remember(lyrics, positionMs) { lyrics.indexOfLast { it.timeMs <= positionMs } }
    LaunchedEffect(lyricsVisible, activeLyricIndex) {
        if (lyricsVisible && activeLyricIndex >= 0) {
            lyricListState.animateScrollToItem(activeLyricIndex + 1)
        }
    }
    val libraryScanActive = status.startsWith("Scanning selected folders") || status.startsWith("Refreshing ")
    val refreshTransition = rememberInfiniteTransition(label = "folder-refresh")
    val refreshRotation by refreshTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "folder-refresh-rotation"
    )
    val prefs = remember(context) { context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE) }
    var aaudioLogRecording by remember { mutableStateOf(false) }
    var aaudioPreviousSample by remember { mutableStateOf<LongArray?>(null) }
    var aaudioPendingLog by remember { mutableStateOf("") }
    val aaudioLogLines = remember { mutableStateListOf<String>() }
    val aaudioSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(aaudioPendingLog) }
        }
    }
    LaunchedEffect(aaudioLogRecording) {
        while (aaudioLogRecording) {
            val sample = runCatching { NativeAudio.nativeGetOutputDiagnostics() }.getOrNull()
            if (sample != null && sample.size >= 36) {
                val previous = aaudioPreviousSample
                aaudioLogLines.add(createAaudioLogLine(sample, previous))
                if (aaudioLogLines.size > 7200) aaudioLogLines.removeAt(0)
                aaudioPreviousSample = sample
            } else {
                val wallTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                    .format(java.util.Date())
                aaudioLogLines.add("$wallTime diagnostics=unavailable")
                if (aaudioLogLines.size > 7200) aaudioLogLines.removeAt(0)
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    var theme by remember { mutableStateOf(prefs.getString("theme", "Auto") ?: "Auto") }
    val availableEncodings = remember { LibraryTextEncoding.available().map { it.name() } }
    var libraryEncoding by remember { mutableStateOf(prefs.getString("library_text_encoding", "UTF-8") ?: "UTF-8") }
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
        if (destination != Destination.WELCOME) Row(Modifier.fillMaxWidth().background(Color.Transparent).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            NavIcon(Icons.Default.Folder, "Folders", destination == Destination.FOLDERS, foreground) {
                destination = Destination.FOLDERS; selectedFolder = null; virtualCueFolder = false
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
                Destination.WELCOME -> WelcomeContent(
                    foreground = foreground,
                    muted = muted,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onContinue = {
                        prefs.edit().putBoolean("welcome_complete", true).apply()
                        destination = Destination.PLAYER
                    }
                )
                Destination.PLAYER -> {
                    Card(Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .08f))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (lyricsVisible) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "LYRICS LRC",
                                        color = foreground,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (lyrics.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text("No synchronized LRC lyrics found.", color = muted, textAlign = TextAlign.Center)
                                        }
                                    } else {
                                        LazyColumn(
                                            state = lyricListState,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            contentPadding = PaddingValues(vertical = 18.dp)
                                        ) {
                                            item(key = "lyrics-spacer-top") { Spacer(Modifier.height(24.dp)) }
                                            itemsIndexed(lyrics, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                                                val active = index == activeLyricIndex
                                                Text(
                                                    line.text.ifBlank { "♪" },
                                                    color = if (active) Color(0xFFD66B9A) else muted,
                                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = if (active) 21.sp else 16.sp,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                        .padding(horizontal = 8.dp, vertical = 9.dp)
                                                        .then(if (active) Modifier.background(
                                                            Color(0xFFD66B9A).copy(alpha = .13f), RoundedCornerShape(12.dp)
                                                        ) else Modifier)
                                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                                )
                                            }
                                            item(key = "lyrics-spacer-bottom") { Spacer(Modifier.height(32.dp)) }
                                        }
                                    }
                                    Text(
                                        "Synced Lyric",
                                        color = muted,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.fillMaxWidth()
                                    )
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
                        val virtualTrackTitle = selectedTrack?.takeIf { it.cuePlaylist != null || it.playlistName != null }?.title
                        Text(virtualTrackTitle ?: currentTag?.title?.ifBlank { selectedTrack?.title ?: "Nothing playing" } ?: selectedTrack?.title ?: "Nothing playing",
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
                    if (selectedTrack != null) {
                        val seekDurationMs = durationMs.coerceAtLeast(1L)
                        Slider(
                            value = positionMs.coerceIn(0L, seekDurationMs).toFloat(),
                            onValueChange = { if (durationMs > 0L) onSeek(it.toLong()) },
                            valueRange = 0f..seekDurationMs.toFloat(),
                            enabled = durationMs > 0L
                        )
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
                    if (selectedFolder == null && !virtualCueFolder) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { destination = Destination.PLAYER; lyricsVisible = false }) {
                                Icon(Icons.Default.ArrowBack, "Now playing", tint = foreground)
                            }
                            Heading("Folders", foreground)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onScan, enabled = !libraryScanActive) {
                                Icon(Icons.Default.Refresh, "Refresh library", tint = Color(0xFFD66B9A),
                                    modifier = Modifier.graphicsLayer { rotationZ = if (libraryScanActive) refreshRotation else 0f })
                            }
                        }
                        Text("SCANNED FOLDERS", color = muted, style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold)
                        val cueVirtualTracks = tracks.filter { it.cuePlaylist != null }
                        val playlistVirtualTracks = tracks.filter { it.playlistName != null }
                        if (cueVirtualTracks.isNotEmpty() || playlistVirtualTracks.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().clickable { virtualCueFolder = true; selectedFolder = null }
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
                                Modifier.fillMaxWidth().clickable { selectedFolder = folder; virtualCueFolder = false }
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
                            IconButton(onClick = { virtualCueFolder = false }) { Icon(Icons.Default.ArrowBack, "Back to folders", tint = foreground) }
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
                                    if (selectedTrack?.uri == item.uri && selectedTrack?.cueStartMs == item.cueStartMs) Icon(Icons.Default.GraphicEq, "Playing", tint = Color(0xFF1E90FF))
                                }
                                HorizontalDivider(color = muted.copy(alpha = .16f))
                            }
                        }
                        m3uTracks.groupBy { it.playlistName }.forEach { (name, entries) ->
                            Text(name ?: "Playlist", color = Color(0xFFD66B9A), fontWeight = FontWeight.SemiBold)
                            entries.sortedBy { it.playlistOrder ?: Int.MAX_VALUE }.forEach { item ->
                                Row(Modifier.fillMaxWidth().clickable { onPlay(item, entries) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.QueueMusic, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(25.dp))
                                    Text(item.title, color = foreground, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                }
                                HorizontalDivider(color = muted.copy(alpha = .16f))
                            }
                        }
                        if (cueTracks.isEmpty() && m3uTracks.isEmpty()) Text("No CUE or playlist files were indexed.", color = muted)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedFolder = null }) {
                                Icon(Icons.Default.ArrowBack, "Back to folders", tint = foreground)
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
                    aaudioLogRecording = aaudioLogRecording,
                    aaudioLogCount = aaudioLogLines.size,
                    onToggleAaudioLog = {
                        if (aaudioLogRecording) aaudioLogRecording = false
                        else { aaudioLogLines.clear(); aaudioPreviousSample = null; aaudioLogRecording = true }
                    },
                    onSaveAaudioLog = {
                        aaudioPendingLog = buildString {
                            appendLine("# Flowery AAudio diagnostic log")
                            appendLine("# Sampling interval: approximately 1 second")
                            appendLine("# Samples: ${aaudioLogLines.size}")
                            aaudioLogLines.forEach { appendLine(it) }
                        }
                        aaudioSaveLauncher.launch("flowery-audio-log-${System.currentTimeMillis()}.txt")
                    },
                    status = status, onBack = { settingsPage = when (settingsPage) {
                        SettingsPage.AUDIO, SettingsPage.LIBRARY, SettingsPage.APPEARANCE, SettingsPage.OTHER, SettingsPage.ABOUT -> SettingsPage.ROOT
                        SettingsPage.AUDIO_INFO, SettingsPage.AUDIO_DIAGNOSTICS -> SettingsPage.AUDIO
                        SettingsPage.OPEN_SOURCE, SettingsPage.TRANSLATOR, SettingsPage.CONTRIBUTORS -> SettingsPage.ABOUT
                        SettingsPage.ROOT -> SettingsPage.ROOT
                    } },
                    navigate = { settingsPage = it },
                    changeTheme = { theme = it; prefs.edit().putString("theme", it).apply() },
                    libraryEncoding = libraryEncoding,
                    availableEncodings = availableEncodings,
                    changeEncoding = { libraryEncoding = it; prefs.edit().putString("library_text_encoding", it).apply() },
                    battery = onBatterySettings, update = onCheckUpdate,
                    openFolders = { destination = Destination.FOLDERS; selectedFolder = null; virtualCueFolder = false },
                    addFolder = onAddFolder, removeFolder = onRemoveFolder,
                    folders = folders, tracks = tracks, scan = onScan,
                    playTrack = onPlay)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WelcomeContent(
    foreground: Color,
    muted: Color,
    onRequestNotificationPermission: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Icon(Icons.Default.LocalFlorist, contentDescription = "Flowery", tint = Color(0xFFD66B9A), modifier = Modifier.size(76.dp))
        Text("Welcome to Flowery", color = foreground, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Your local Hi-Fi music player. Follow these steps to add your music folder.",
            color = muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .07f)),
            shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("HOW TO ADD A MUSIC FOLDER", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFFD66B9A), modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("1. Open Settings → Library", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("In Flowery, open Settings, select Library, then tap ADD to choose a folder in Android's folder picker. Confirm with Use this folder / Allow.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color(0xFFD66B9A), modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("2. Tap Scan", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Flowery indexes supported audio files and playlists in the folders you selected.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFFD66B9A), modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("3. Open Folders and play", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Choose a song to start playback. Your playback controls will appear in Now Playing.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .07f)),
            shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("APP PERMISSIONS", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                PermissionInfoRow(Icons.Default.FolderOpen, "Music folder access",
                    "Granted only to folders you choose. You can add or remove folders later.", foreground, muted)
                PermissionInfoRow(Icons.Default.Notifications, "Notifications",
                    "Optional on Android 13 and newer. Allows playback controls to appear in the notification shade and lockscreen.", foreground, muted)
                PermissionInfoRow(Icons.Default.Usb, "USB DAC access",
                    "Android asks separately when you connect and enable a compatible USB DAC.", foreground, muted)
                PermissionInfoRow(Icons.Default.BatteryFull, "Battery optimization",
                    "Optional. You can review it in Settings if background playback is interrupted.", foreground, muted)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null)
                        Text("  ALLOW NOTIFICATIONS")
                    }
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6)),
            shape = RoundedCornerShape(28.dp)) { Text("NEXT") }
    }
}

@Composable
private fun PermissionInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    foreground: Color,
    muted: Color
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = Color(0xFFD66B9A), modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = foreground, fontWeight = FontWeight.SemiBold)
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}


private fun createAaudioLogLine(sample: LongArray, previous: LongArray?): String {
    val timestampValid = sample[27] == 0L && sample[29] > 0L
    val callbackDeltaText = if (previous != null && previous.size >= 36) {
        "callbackCountDelta=${sample[13] - previous[13]} " +
            "lateCallbackIntervalsDelta=${sample[25] - previous[25]}"
    } else "callbackDelta=unavailable"
    val timestampDeltaText = if (timestampValid && previous != null &&
        previous[27] == 0L && previous[29] > 0L) {
        val elapsedNs = sample[29] - previous[29]
        val presentedFrames = sample[28] - previous[28]
        val writtenFrames = sample[30] - previous[30]
        val expectedFrames = if (elapsedNs > 0L && sample[23] > 0L)
            elapsedNs.toDouble() * sample[23].toDouble() / 1_000_000_000.0 else Double.NaN
        val driftFrames = if (expectedFrames.isFinite()) presentedFrames - expectedFrames else Double.NaN
        "timestampDeltaNs=$elapsedNs presentedDeltaFrames=$presentedFrames " +
            "expectedDeltaFrames=${"%.2f".format(Locale.ROOT, expectedFrames)} " +
            "clockDriftFrames=${"%.2f".format(Locale.ROOT, driftFrames)} " +
            "writtenDeltaFrames=$writtenFrames writtenMinusPresentedDelta=${writtenFrames - presentedFrames}"
    } else "timestampDelta=unavailable"
    val modeName: (Long) -> String = { mode ->
        when (mode) { 0L -> "EXCLUSIVE"; 1L -> "SHARED"; else -> "UNKNOWN($mode)" }
    }
    val wallTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        .format(java.util.Date())
    return "$wallTime sampleRate=${sample[7]} requestedDeviceId=${sample[33]} actualDeviceId=${sample[8]} " +
        "preferredSharingMode=${modeName(sample[31])} successfulOpenRequestMode=${modeName(sample[32])} " +
        "actualSharingMode=${modeName(sample[9])} " +
        "streamState=${sample[6]} timestampStatus=${sample[27]} " +
        "hardwareFramePosition=${sample[28]} hardwareTimestampNs=${sample[29]} " +
        "framesWritten=${sample[30]} ringUnderruns=${sample[0]} hardwareXruns=${sample[1]} " +
        "queuedFrames=${sample[2]} bufferSizeFrames=${sample[4]} bufferCapacityFrames=${sample[26]} " +
        "callbackCount=${sample[13]} callbackFramesRequested=${sample[14]} " +
        "callbackFramesDelivered=${sample[15]} silenceFrames=${sample[16]} " +
        "unexpectedSilenceFrames=${sample[18]} maxCallbackDurationNs=${sample[21]} " +
        "lastCallbackIntervalNs=${sample[34]} lastCallbackLatenessNs=${sample[35]} " +
        "maxCallbackIntervalNs=${sample[22]} maxCallbackLatenessNs=${sample[24]} " +
        "lateCallbackIntervals=${sample[25]} exclusiveOpenResult=${sample[19]} " +
        "sharedOpenResult=${sample[20]} $callbackDeltaText $timestampDeltaText"
}

@Composable
private fun SettingsContent(page: SettingsPage, color: Color, muted: Color, theme: String,
    aaudioLogRecording: Boolean, aaudioLogCount: Int,
    onToggleAaudioLog: () -> Unit, onSaveAaudioLog: () -> Unit,
    onBack: () -> Unit, navigate: (SettingsPage) -> Unit, changeTheme: (String) -> Unit,
    libraryEncoding: String, availableEncodings: List<String>, changeEncoding: (String) -> Unit,
    battery: () -> Unit, update: () -> Unit, openFolders: () -> Unit,
    addFolder: () -> Unit, removeFolder: (LibraryFolder) -> Unit, status: String,
    folders: List<LibraryFolder>, tracks: List<AudioTrackItem>, scan: () -> Unit,
    playTrack: (AudioTrackItem, List<AudioTrackItem>) -> Unit) {
    var encodingExpanded by remember(page) { mutableStateOf(false) }
    if (page != SettingsPage.ROOT) Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = color) }
        Heading(when(page) {
            SettingsPage.AUDIO -> "Audio"; SettingsPage.AUDIO_INFO -> "Information Audio"; SettingsPage.AUDIO_DIAGNOSTICS -> "AAudio Diagnostics"; SettingsPage.LIBRARY -> "Library"
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
        SettingsPage.AUDIO -> {
            SettingCard("Information Audio", "Decoding, resampling, equalizer and output", color, muted) { navigate(SettingsPage.AUDIO_INFO) }
            SettingCard("AAudio Diagnostics", "Ring-buffer underruns, hardware XRuns and output buffer metrics", color, muted) { navigate(SettingsPage.AUDIO_DIAGNOSTICS) }
        }
        SettingsPage.AUDIO_INFO -> {
            Detail("Decoder", "Symphonia · progressive packet decoding", color, muted)
            Detail("Resampler", "Rubato · persistent streaming state", color, muted)
            Detail("Equalizer", "31-band ISO biquad EQ · ±12 dB", color, muted)
            Detail("Output", "AAudio · bounded PCM ring buffer", color, muted)
            Detail("Pipeline", "SAF file → Symphonia decoder → Rubato resampler (when required) → 31-band EQ → bounded PCM ring → AAudio", color, muted)
            Detail("Playback status", status, color, muted)
            Detail("Runtime telemetry", "Per-stage timing, decoder throughput, and live DSP meters are not currently exposed by the engine.", color, muted)
        }
        SettingsPage.AUDIO_DIAGNOSTICS -> {
            var metrics by remember(page) { mutableStateOf<LongArray?>(null) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleAaudioLog, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (aaudioLogRecording) Color(0xFFB44758) else Color(0xFF7452A6))) {
                    Icon(if (aaudioLogRecording) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Text(if (aaudioLogRecording) "  Stop Log" else "  Start Log")
                }
                Button(onClick = onSaveAaudioLog, enabled = !aaudioLogRecording && aaudioLogCount > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6))) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("  Save Log")
                }
            }
            Text(if (aaudioLogRecording) "Recording every second in the background. You can leave Settings, play the problem track, then return here to stop and save."
                else "Start Log before playback. Sampling continues while you navigate to Now Playing; stop logging before saving.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("Captured samples: $aaudioLogCount", color = muted, style = MaterialTheme.typography.labelSmall)
            Button(onClick = { metrics = runCatching { NativeAudio.nativeGetOutputDiagnostics() }.getOrNull() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6))) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("  Refresh diagnostics")
            }
            Text("Values are also available as a live snapshot below. The log records timestamp progression and frame deltas so playback interruptions can be correlated with the hardware clock.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            val data = metrics
            if (data == null || data.size < 21) {
                Detail("Output", "No diagnostics sampled yet. Start playback, then refresh.", color, muted)
            } else {
                Detail("Ring-buffer underruns", data[0].toString(), color, muted)
                Detail("AAudio hardware XRuns", if (data[1] < 0) "Unavailable" else data[1].toString(), color, muted)
                Detail("Queued PCM frames", data[2].toString(), color, muted)
                Detail("Ring capacity", "${data[3]} frames", color, muted)
                Detail("AAudio buffer size", if (data[4] > 0) "${data[4]} frames" else "Unavailable", color, muted)
                Detail("Frames per burst", if (data[5] > 0) data[5].toString() else "Unavailable", color, muted)
                Detail("Stream state", data[6].toString(), color, muted)
                Detail("Output sample rate", if (data[7] > 0) "${data[7]} Hz" else "Unavailable", color, muted)
                Detail("Actual AAudio device ID", data[8].toString(), color, muted)
                Detail("Sharing mode", when (data[9].toInt()) { 0 -> "EXCLUSIVE"; 1 -> "SHARED"; else -> "Unavailable" }, color, muted)
                Detail("PCM format", when (data[10].toInt()) { 2 -> "PCM_FLOAT"; 1 -> "PCM_I16"; 3 -> "PCM_I24_PACKED"; 4 -> "PCM_I32"; else -> "Unknown (${data[10]})" }, color, muted)
                Detail("Output channels", data[11].toString(), color, muted)
                Detail("Performance mode", when (data[12].toInt()) { 12 -> "LOW_LATENCY"; 11 -> "POWER_SAVING"; 10 -> "NONE"; else -> "Unknown (${data[12]})" }, color, muted)
                Detail("Data callbacks", data[13].toString(), color, muted)
                Detail("Callback frames requested", data[14].toString(), color, muted)
                Detail("PCM frames delivered", data[15].toString(), color, muted)
                Detail("Silence frames inserted", data[16].toString(), color, muted)
                Detail("Largest callback", "${data[17]} frames", color, muted)
                Detail("Unexpected silence frames", data[18].toString(), color, muted)
                Detail("Exclusive open result", data[19].toString(), color, muted)
                Detail("Shared open result", data[20].toString(), color, muted)
                if (data.size >= 27) {
                    Detail("Maximum callback processing time", "${data[21] / 1_000_000.0} ms", color, muted)
                    Detail("Maximum callback interval", "${data[22] / 1_000_000.0} ms", color, muted)
                    Detail("Negotiated stream sample rate", "${data[23]} Hz", color, muted)
                    Detail("Maximum callback scheduling lateness", "${data[24] / 1_000_000.0} ms", color, muted)
                    Detail("Callback intervals over 10 ms late", data[25].toString(), color, muted)
                    Detail("AAudio buffer capacity", "${data[26]} frames", color, muted)
                }
                if (data.size >= 31) {
                    Detail("AAudio timestamp result", if (data[27] == 0L) "Available (AAUDIO_OK)" else "Unavailable (error ${data[27]})", color, muted)
                    Detail("Hardware timestamp frame position", data[28].toString(), color, muted)
                    Detail("Hardware timestamp time", if (data[29] > 0L) "${data[29]} ns · CLOCK_MONOTONIC" else "Unavailable", color, muted)
                    Detail("Frames written to AAudio", data[30].toString(), color, muted)
                }
                if (data.size >= 36) {
                    val sharingName: (Long) -> String = { mode ->
                        when (mode) { 0L -> "EXCLUSIVE"; 1L -> "SHARED"; else -> "Unavailable ($mode)" }
                    }
                    Detail("Preferred sharing mode", sharingName(data[31]), color, muted)
                    Detail("Successful open request mode", sharingName(data[32]), color, muted)
                    Detail("Requested device ID", data[33].toString(), color, muted)
                    Detail("Last callback interval", "${data[34] / 1_000_000.0} ms", color, muted)
                    Detail("Last callback lateness", "${data[35] / 1_000_000.0} ms", color, muted)
                }
            }
            Text("Callback counters include requested frames, PCM frames copied from the ring, and silence frames written by the callback. A non-zero silence-frame count can reveal gaps even when the hardware XRun counter remains zero. Hardware timestamps are queried on demand using CLOCK_MONOTONIC; the timestamp result must be AAUDIO_OK before its frame position and time are interpreted. Frames written is the stream's cumulative submitted-frame count. AAudio device ID and sharing mode describe the stream actually opened. Counters reset when the output stream is recreated.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsPage.LIBRARY -> {
            Text("FOLDER SELECTION", color = color, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text("Remove only removes a folder from Flowery's list. Original folders and audio files are never deleted.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = addFolder,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(" ADD")
                }
                val scanInProgress = status.startsWith("Scanning selected folders") ||
                    status.startsWith("Refreshing ")
                val scanTransition = rememberInfiniteTransition(label = "library-scan")
                val scanRotation by scanTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "scan-rotation"
                )
                Button(
                    onClick = scan,
                    enabled = !scanInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Default.Refresh, null,
                        modifier = Modifier.graphicsLayer { rotationZ = if (scanInProgress) scanRotation else 0f })
                    Text(if (scanInProgress) " Scanning…" else " Scan")
                }
            }
            Text("${folders.size} folders in library", color = muted,
                style = MaterialTheme.typography.labelSmall)
            folders.forEach { folder ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(27.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(folder.name, color = color, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Included in library", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { removeFolder(folder) }) {
                        Icon(Icons.Default.Close, "Remove folder from Flowery", tint = Color(0xFFD66B9A),
                            modifier = Modifier.size(25.dp))
                    }
                }
                HorizontalDivider(color = muted.copy(alpha = .16f))
            }
            if (folders.isEmpty()) Text("No folders added yet. Tap ADD to choose a folder. You can add more folders at any time.",
                color = muted)
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
            Text("TEXT ENCODING", color = color, fontWeight = FontWeight.SemiBold)
            Text("Choose the character encoding used to read CUE, M3U and PLS files. M3U8 is always decoded as UTF-8.", color = muted)
            Box {
                OutlinedButton(onClick = { encodingExpanded = true }) {
                    Text(libraryEncoding, color = color)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose text encoding", tint = color)
                }
                DropdownMenu(
                    expanded = encodingExpanded,
                    onDismissRequest = { encodingExpanded = false },
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    availableEncodings.forEach { encoding ->
                        DropdownMenuItem(
                            text = { Text(encoding) },
                            onClick = {
                                changeEncoding(encoding)
                                encodingExpanded = false
                            }
                        )
                    }
                }
            }
            Text("Run Scan again after changing this setting to re-read playlist names and labels.", color = muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = muted.copy(alpha = .25f))
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


@Composable
private fun Heading(title: String, color: Color) {
    Text(
        text = title,
        color = color,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun NavIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    foreground: Color,
    onClick: () -> Unit
) {
    val accent = Color(0xFFD66B9A)
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) accent else foreground,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = if (selected) accent else foreground.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}
