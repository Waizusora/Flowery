package com.waizusora.flowery

import android.app.Activity
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

private enum class Destination { WELCOME, PLAYER, FOLDERS, SETTINGS }
internal enum class SettingsPage { ROOT, AUDIO, INFO_AUDIO, LIBRARY, APPEARANCE, OTHER, ABOUT, OPEN_SOURCE, TRANSLATOR, CONTRIBUTORS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FloweryNavigation(
    context: Context, folders: List<LibraryFolder>, tracks: List<AudioTrackItem>,
    isPlaying: Boolean, shuffleEnabled: Boolean, repeatMode: Int, selectedTrack: AudioTrackItem?,
    currentTag: AudioTag?, albumArtBytes: ByteArray?, displayTitle: String, displayArtist: String,
    lyrics: List<LyricLine>, positionMs: Long, durationMs: Long,
    outputSampleRateHz: Int, outputBitDepth: Int, outputBackend: String,
    decodedSampleRateHz: Int, decodedPcmFormat: String, outputPcmFormat: String,
    outputResamplingRequired: Boolean, outputBitDepthConversionRequired: Boolean,
    outputSampleFormatConversionRequired: Boolean, outputBitPerfectCandidate: Boolean,
    volume: Float, usbVolumeDetected: Boolean, usbVolumeWritable: Boolean,
    usbVolumeVerified: Boolean, usbVolumeDb: Float,
    usbVolumeMinDb: Float, usbVolumeMaxDb: Float, usbVolumeResolutionDb: Float,
    status: String,
    onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit, onToggleFolder: (LibraryFolder, Boolean) -> Unit,
    onScan: () -> Unit, onRefreshFolder: (LibraryFolder) -> Unit,
    onPlay: (AudioTrackItem, List<AudioTrackItem>) -> Unit,
    onInspect: (AudioTrackItem) -> Unit, onEdit: () -> Unit, onTogglePlayback: () -> Unit,
    onVolume: (Float) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onSeek: (Long) -> Unit,
    onBatterySettings: () -> Unit, onCheckUpdate: () -> Unit,
    audioSourceLabel: String,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    var destination by remember(context) {
        mutableStateOf(if (context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
            .getBoolean("welcome_complete", false) || folders.isNotEmpty()) Destination.PLAYER else Destination.WELCOME)
    }
    var settingsPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val permissionRevision = permissionRefresh
    val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val batteryOptimizationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    val prefs = remember(context) { context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE) }
    var selectedFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var virtualCueFolder by remember { mutableStateOf(false) }
    val navigateBack = {
        when (destination) {
            Destination.PLAYER -> Unit
            Destination.FOLDERS -> {
                if (virtualCueFolder) virtualCueFolder = false
                else if (selectedFolder != null) selectedFolder = null
                else destination = Destination.PLAYER
            }
            Destination.SETTINGS -> {
                if (settingsPage != SettingsPage.ROOT) settingsPage = SettingsPage.ROOT
                else destination = Destination.PLAYER
            }
            Destination.WELCOME -> Unit
        }
    }
    BackHandler(enabled = destination != Destination.WELCOME) {
        if (destination == Destination.PLAYER) {
            // Back from Now Playing backgrounds the task like Home; playback remains owned by PlaybackService.
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            navigateBack()
        }
    }
    var lyricsVisible by remember { mutableStateOf(false) }
    var showLyricsSearch by remember { mutableStateOf(false) }
    var downloadedLyrics by remember(selectedTrack?.uri) {
        mutableStateOf(LyricsRepository.load(context, selectedTrack))
    }
    val displayLyrics = downloadedLyrics ?: lyrics
    val lyricListState = rememberLazyListState()
    val hasSynchronizedLyrics = displayLyrics.any { it.timeMs > 0L }
    val activeLyricIndex = remember(displayLyrics, positionMs) {
        if (hasSynchronizedLyrics) LrcParser.activeLineIndex(displayLyrics, positionMs) else -1
    }
    LaunchedEffect(lyricsVisible, activeLyricIndex, displayLyrics.size, hasSynchronizedLyrics) {
        if (lyricsVisible && hasSynchronizedLyrics && displayLyrics.isNotEmpty()) {
            // Wait for a real, non-zero LazyColumn viewport. A single frame is
            // not sufficient on first entry or after the panel is resized.
            withFrameNanos { }
            snapshotFlow { lyricListState.layoutInfo.viewportSize.height }
                .first { it > 0 }
            val targetIndex = activeLyricIndex.coerceIn(0, displayLyrics.lastIndex)
            // First make the active line visible, then center its measured bounds
            // against the actual list viewport. This avoids relying on a fixed
            // text-row height when lyrics wrap or the device font scale changes.
            lyricListState.animateScrollToItem(targetIndex)
            withFrameNanos { }
            val layout = lyricListState.layoutInfo
            val activeItem = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (activeItem != null) {
                val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                val itemCenter = activeItem.offset + activeItem.size / 2f
                lyricListState.animateScrollBy(itemCenter - viewportCenter)
            }
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
    var theme by remember {
        mutableStateOf(when (prefs.getString("theme", "System")) {
            "Light" -> "Light"
            "Black", "Dark" -> "Dark"
            else -> "System"
        })
    }
    val availableEncodings = remember { LibraryTextEncoding.available().map { it.name() } }
    var libraryEncoding by remember { mutableStateOf(prefs.getString("library_text_encoding", "UTF-8") ?: "UTF-8") }
    val systemDark = isSystemInDarkTheme()
    val lightTheme = theme == "Light" || (theme == "System" && !systemDark)
    val foreground = if (lightTheme) Color(0xFF152536) else Color(0xFFE6F4F7)
    val muted = if (lightTheme) Color(0xFF52697C) else Color(0xFFB0C8D0)
    val background = if (lightTheme) Color(0xFFF4F9FE) else Color(0xFF101B24)

    FloweryTheme(darkTheme = !lightTheme) {
    Scaffold(containerColor = background, bottomBar = {
        if (destination != Destination.WELCOME) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            NavIcon(Icons.Default.Folder, "Folders", destination == Destination.FOLDERS, foreground) {
                destination = Destination.FOLDERS; selectedFolder = null; virtualCueFolder = false
            }
            NavIcon(Icons.Default.Lyrics, "Lyrics", lyricsVisible, foreground) {
                if (destination != Destination.PLAYER) destination = Destination.PLAYER
                lyricsVisible = !lyricsVisible
            }
            NavIcon(Icons.Default.Settings, "Settings", destination == Destination.SETTINGS, foreground) {
                destination = Destination.SETTINGS; settingsPage = SettingsPage.ROOT
            }
        }
    }) { inset ->
        Column(
            Modifier.fillMaxSize().background(background).padding(inset)
                .pointerInput(destination, selectedFolder, virtualCueFolder, settingsPage) {
                    var horizontalDistance = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            horizontalDistance += dragAmount
                            change.consume()
                        },
                        onDragEnd = {
                            val threshold = 72.dp.toPx()
                            when {
                                horizontalDistance >= threshold -> {
                                    when (destination) {
                                        Destination.PLAYER -> {
                                            destination = Destination.FOLDERS
                                            selectedFolder = null
                                            virtualCueFolder = false
                                        }
                                        Destination.SETTINGS -> {
                                            if (settingsPage != SettingsPage.ROOT) settingsPage = SettingsPage.ROOT
                                            else destination = Destination.PLAYER
                                        }
                                        Destination.FOLDERS -> {
                                            if (virtualCueFolder) virtualCueFolder = false
                                            else if (selectedFolder != null) selectedFolder = null
                                            else destination = Destination.PLAYER
                                        }
                                        Destination.WELCOME -> Unit
                                    }
                                }
                                horizontalDistance <= -threshold -> {
                                    when (destination) {
                                        Destination.PLAYER -> {
                                            destination = Destination.SETTINGS
                                            settingsPage = SettingsPage.ROOT
                                        }
                                        Destination.FOLDERS -> {
                                            if (selectedFolder == null && !virtualCueFolder) destination = Destination.PLAYER
                                        }
                                        Destination.SETTINGS, Destination.WELCOME -> Unit
                                    }
                                }
                            }
                            horizontalDistance = 0f
                        },
                        onDragCancel = { horizontalDistance = 0f }
                    )
                }
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (destination) {
                Destination.WELCOME -> WelcomeDestination(
                    foreground = foreground,
                    muted = muted,
                    musicFolderGranted = folders.isNotEmpty(),
                    notificationsGranted = notificationsGranted,
                    batteryOptimizationGranted = batteryOptimizationGranted,
                    onAddMusicFolder = onAddFolder,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onRequestBatteryOptimization = onRequestBatteryOptimization,
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
                    lyrics = displayLyrics,
                    activeLyricIndex = activeLyricIndex,
                    currentTag = currentTag,
                    albumArtBytes = albumArtBytes,
                    displayTitle = displayTitle,
                    displayArtist = displayArtist,
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
                    onEdit = onEdit,
                    onSearchLyrics = { showLyricsSearch = true }
                )
                Destination.FOLDERS -> FoldersDestination(
                    folders = folders,
                    tracks = tracks,
                    selectedFolder = selectedFolder,
                    virtualCueFolder = virtualCueFolder,
                    selectedTrack = selectedTrack,
                    foreground = foreground,
                    muted = muted,
                    libraryScanActive = libraryScanActive,
                    refreshRotation = refreshRotation,
                    onBackToPlayer = { navigateBack(); lyricsVisible = false },
                    onScan = onScan,
                    onRefreshFolder = onRefreshFolder,
                    onSelectedFolder = { selectedFolder = it },
                    onVirtualCueFolder = { virtualCueFolder = it },
                    onPlay = onPlay
                )
                Destination.SETTINGS -> SettingsContent(settingsPage, foreground, muted, theme,
                    audioSourceLabel = audioSourceLabel,
                    currentTag = currentTag,
                    isPlaying = isPlaying,
                    outputSampleRateHz = outputSampleRateHz,
                    outputBitDepth = outputBitDepth,
                    outputBackend = outputBackend,
                    decodedSampleRateHz = decodedSampleRateHz,
                    decodedPcmFormat = decodedPcmFormat,
                    outputPcmFormat = outputPcmFormat,
                    outputResamplingRequired = outputResamplingRequired,
                    outputBitDepthConversionRequired = outputBitDepthConversionRequired,
                    outputSampleFormatConversionRequired = outputSampleFormatConversionRequired,
                    outputBitPerfectCandidate = outputBitPerfectCandidate,
                    volume = volume,
                    onVolume = onVolume,
                    usbVolumeDetected = usbVolumeDetected,
                    usbVolumeWritable = usbVolumeWritable,
                    usbVolumeVerified = usbVolumeVerified,
                    usbVolumeDb = usbVolumeDb,
                    usbVolumeMinDb = usbVolumeMinDb,
                    usbVolumeMaxDb = usbVolumeMaxDb,
                    usbVolumeResolutionDb = usbVolumeResolutionDb,
                    status = status, onBack = { settingsPage = when (settingsPage) {
                        SettingsPage.AUDIO, SettingsPage.LIBRARY, SettingsPage.APPEARANCE, SettingsPage.OTHER, SettingsPage.ABOUT -> SettingsPage.ROOT
                        SettingsPage.INFO_AUDIO -> SettingsPage.AUDIO
                        SettingsPage.OPEN_SOURCE, SettingsPage.TRANSLATOR, SettingsPage.CONTRIBUTORS -> SettingsPage.ABOUT
                        SettingsPage.ROOT -> SettingsPage.ROOT
                    } },
                    navigate = { settingsPage = it },
                    changeTheme = { selected ->
                        theme = selected
                        prefs.edit().putString("theme", when (selected) { "Dark" -> "Black"; "Light" -> "Light"; else -> "Auto" }).apply()
                    },
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
    if (showLyricsSearch && selectedTrack != null) {
        LyricsSearchDialog(
            initialQuery = listOf(displayTitle, displayArtist).filter { it.isNotBlank() }.joinToString(" "),
            onDismiss = { showLyricsSearch = false },
            onDownload = { content, extension ->
                runCatching { LyricsRepository.save(context, selectedTrack, extension, content) }
                    .onSuccess { parsed ->
                        downloadedLyrics = parsed
                        showLyricsSearch = false
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: "Unable to save downloaded lyrics.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        )
    }
}

@Composable
private fun NavIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    foreground: Color,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
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
