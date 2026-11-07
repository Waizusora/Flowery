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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
internal enum class SettingsPage { ROOT, AUDIO, AUDIO_INFO, USB_DIAGNOSTICS, LIBRARY, APPEARANCE, OTHER, ABOUT, OPEN_SOURCE, TRANSLATOR, CONTRIBUTORS }

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
    usbDacUiStatus: String, usbDacDeviceName: String, usbDacConnected: Boolean,
    usbDacPermissionGranted: Boolean, onConnectUsbDac: () -> Unit, onReleaseUsbDac: () -> Unit,
    audioSourceLabel: String,
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
    var usbDiagnosticRecording by remember { mutableStateOf(false) }
    var usbDiagnosticPreviousSample by remember { mutableStateOf<LongArray?>(null) }
    var usbDiagnosticPendingLog by remember { mutableStateOf("") }
    var usbDiagnosticDescriptorReport by remember { mutableStateOf("") }
    val usbDiagnosticLines = remember { mutableStateListOf<String>() }
    val usbDiagnosticSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(usbDiagnosticPendingLog) }
        }
    }
    LaunchedEffect(usbDiagnosticRecording) {
        if (usbDiagnosticRecording) {
            usbDiagnosticDescriptorReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val controller = UsbDacController(context)
                val devices = controller.audioDevices()
                if (devices.isEmpty()) "No USB Audio Class device was detected when Record Log started."
                else devices.joinToString(separator = "\n\n") { device ->
                    "===== ${device.displayName} · ${device.identity} =====\n" +
                        if (controller.hasPermission(device.usbDevice)) controller.inspectDescriptors(device.usbDevice)
                        else "USB permission not granted; raw descriptors and clock ranges are unavailable."
                }
            }
            while (usbDiagnosticRecording) {
                val sample = runCatching { NativeAudio.nativeGetOutputDiagnostics() }.getOrNull()
                val openAttempt = runCatching { NativeAudio.nativeGetLastOutputOpenDiagnostics() }.getOrNull()
                if (sample != null && sample.size >= 36) {
                    usbDiagnosticLines.add(createAaudioLogLine(sample, usbDiagnosticPreviousSample, openAttempt))
                    usbDiagnosticPreviousSample = sample
                } else {
                    val wallTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(java.util.Date())
                    usbDiagnosticLines.add("$wallTime aaudioDiagnostics=unavailable")
                }
                if (usbDiagnosticLines.size > 7200) usbDiagnosticLines.removeAt(0)
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    var theme by remember { mutableStateOf(prefs.getString("theme", "Auto") ?: "Auto") }
    var usbBitPerfectEnabled by remember { mutableStateOf(prefs.getBoolean("usb_bit_perfect_enabled", false)) }
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
            val usbTransportActive = status.contains("USB exclusive")
            val unityGainDirectPcm = status.contains("unity gain")
            IconButton(onClick = onUsbDacClick) {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = when {
                        unityGainDirectPcm -> "USB DAC direct PCM active at unity gain; EQ bypassed"
                        usbTransportActive -> "USB DAC direct transport active; decoded PCM is not bit-perfect"
                        else -> "USB DAC"
                    },
                    tint = if (usbTransportActive) Color(0xFF1E90FF) else foreground
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
                Destination.PLAYER -> NowPlayingDestination(
                    foreground = foreground,
                    muted = muted,
                    lyricsVisible = lyricsVisible,
                    lyricListState = lyricListState,
                    lyrics = lyrics,
                    activeLyricIndex = activeLyricIndex,
                    currentTag = currentTag,
                    selectedTrack = selectedTrack,
                    shuffleEnabled = shuffleEnabled,
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    status = status,
                    onShuffle = onShuffle,
                    onPrevious = onPrevious,
                    onTogglePlayback = onTogglePlayback,
                    onNext = onNext,
                    onRepeat = onRepeat,
                    onSeek = onSeek,
                    onInspect = onInspect,
                    onEdit = onEdit
                )
                Destination.FOLDERS -> {
                    if (selectedFolder == null && !virtualCueFolder) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { destination = Destination.PLAYER; lyricsVisible = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Now playing", tint = foreground)
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
                            IconButton(onClick = { virtualCueFolder = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to folders", tint = foreground) }
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
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(25.dp))
                                    Text(item.title, color = foreground, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                }
                                HorizontalDivider(color = muted.copy(alpha = .16f))
                            }
                        }
                        if (cueTracks.isEmpty() && m3uTracks.isEmpty()) Text("No CUE or playlist files were indexed.", color = muted)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedFolder = null }) {
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
                Destination.DSP -> {
                    Heading("DSP", foreground)
                    Text("Output volume", color = foreground, fontWeight = FontWeight.SemiBold)
                    Slider(value = volume, onValueChange = onVolume)
                    Text("${(volume * 100).toInt()}%", color = muted, modifier = Modifier.align(Alignment.End))
                    Text(
                        "USB DAC: software gain is saved separately for each DAC and remains available without a physical volume control. Speaker, AUX and Bluetooth follow Android media volume.",
                        color = muted,
                        style = MaterialTheme.typography.bodySmall
                    )
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
                    usbDiagnosticRecording = usbDiagnosticRecording,
                    usbDiagnosticLogCount = usbDiagnosticLines.size,
                    usbDiagnosticDescriptorReport = usbDiagnosticDescriptorReport,
                    onToggleUsbDiagnosticLog = {
                        if (usbDiagnosticRecording) {
                            usbDiagnosticRecording = false
                            prefs.edit().putBoolean("usb_diagnostic_recording", false).apply()
                        } else {
                            usbDiagnosticLines.clear()
                            usbDiagnosticPreviousSample = null
                            usbDiagnosticDescriptorReport = "Reading connected USB DAC descriptors…"
                            prefs.edit().putString("usb_direct_diagnostics", "")
                                .putBoolean("usb_diagnostic_recording", true).apply()
                            usbDiagnosticRecording = true
                        }
                    },
                    onSaveUsbDiagnosticLog = {
                        val usbDirectLog = prefs.getString("usb_direct_diagnostics", "").orEmpty()
                        usbDiagnosticPendingLog = buildString {
                            appendLine("# Flowery USB diagnostic record")
                            appendLine("# Recorded at: ${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(java.util.Date())}")
                            appendLine("# AAudio telemetry samples: ${usbDiagnosticLines.size}")
                            appendLine("# Direct USB events: ${usbDirectLog.lineSequence().count { it.isNotBlank() }}")
                            appendLine()
                            appendLine("===== USB descriptor and DAC profile database snapshot =====")
                            appendLine(usbDiagnosticDescriptorReport.ifBlank { "No descriptor snapshot was captured." })
                            appendLine()
                            appendLine("===== AAudio runtime telemetry =====")
                            usbDiagnosticLines.forEach { appendLine(it) }
                            appendLine()
                            appendLine("===== Direct USB / libusb playback events =====")
                            if (usbDirectLog.isBlank()) appendLine("No direct USB playback events were recorded.")
                            else appendLine(usbDirectLog)
                        }
                        usbDiagnosticSaveLauncher.launch("flowery-usb-diagnostic-${System.currentTimeMillis()}.txt")
                    },
                    usbDacUiStatus = usbDacUiStatus,
                    usbDacDeviceName = usbDacDeviceName,
                    usbDacConnected = usbDacConnected,
                    usbDacPermissionGranted = usbDacPermissionGranted,
                    onConnectUsbDac = onConnectUsbDac,
                    onReleaseUsbDac = onReleaseUsbDac,
                    audioSourceLabel = audioSourceLabel,
                    usbBitPerfectEnabled = usbBitPerfectEnabled,
                    onUsbBitPerfectChanged = { enabled ->
                        usbBitPerfectEnabled = enabled
                        prefs.edit().putBoolean("usb_bit_perfect_enabled", enabled).apply()
                    },
                    status = status, onBack = { settingsPage = when (settingsPage) {
                        SettingsPage.AUDIO, SettingsPage.LIBRARY, SettingsPage.APPEARANCE, SettingsPage.OTHER, SettingsPage.ABOUT -> SettingsPage.ROOT
                        SettingsPage.AUDIO_INFO, SettingsPage.USB_DIAGNOSTICS -> SettingsPage.AUDIO
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


private fun createAaudioLogLine(sample: LongArray, previous: LongArray?, openAttempt: LongArray?): String {
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
    val openAttemptText = if (openAttempt != null && openAttempt.size >= 11) {
        "lastOpenRequestedDeviceId=${openAttempt[0]} " +
            "lastOpenRequestedMode=${modeName(openAttempt[1])} " +
            "lastOpenRequestedRate=${openAttempt[2]} lastOpenRequestedChannels=${openAttempt[3]} " +
            "lastOpenResult=${openAttempt[4]} lastOpenActualDeviceId=${openAttempt[5]} " +
            "lastOpenActualMode=${modeName(openAttempt[6])} lastOpenActualRate=${openAttempt[7]} " +
            "lastOpenActualChannels=${openAttempt[8]} lastOpenActualFormat=${openAttempt[9]} " +
            "lastOpenFailureStage=${openAttempt[10]}"
    } else "lastOpenAttempt=unavailable"
    return "$wallTime sampleRate=${sample[7]} requestedDeviceId=${sample[33]} actualDeviceId=${sample[8]} " +
        "preferredSharingMode=${modeName(sample[31])} successfulOpenRequestMode=${modeName(sample[32])} " +
        "actualSharingMode=${modeName(sample[9])} " +
        "hardwareFormat=${sample.getOrElse(36) { -1L }} " +
        "hardwareSampleRate=${sample.getOrElse(37) { -1L }} " +
        "hardwareChannels=${sample.getOrElse(38) { -1L }} " +
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
        "sharedOpenResult=${sample[20]} $callbackDeltaText $timestampDeltaText $openAttemptText"
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
