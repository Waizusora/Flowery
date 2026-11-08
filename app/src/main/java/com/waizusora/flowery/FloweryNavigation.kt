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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
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
    equalizerGains: List<Float>, equalizerEnabled: Boolean, status: String,
    onBandGain: (Int, Float) -> Unit, onEqualizerEnabled: (Boolean) -> Unit,
    onAddFolder: () -> Unit,
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
    val lyricDensity = LocalDensity.current
    LaunchedEffect(lyricsVisible, activeLyricIndex, lyrics.size) {
        if (lyricsVisible && lyrics.isNotEmpty()) {
            val targetIndex = activeLyricIndex.coerceAtLeast(0) + 1
            val viewportHeight = lyricListState.layoutInfo.viewportSize.height
            val fallbackItemHeight = with(lyricDensity) { 56.dp.roundToPx() }
            val itemHeight = lyricListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }?.size ?: fallbackItemHeight
            val centerOffset = (viewportHeight / 2 - itemHeight / 2).coerceAtLeast(0)
            lyricListState.animateScrollToItem(targetIndex, scrollOffset = -centerOffset)
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
    var usbDiagnosticRecording by remember { mutableStateOf(prefs.getBoolean("usb_diagnostic_recording", false)) }
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
                val wallTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                    .format(java.util.Date())
                fun logField(value: String): String =
                    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
                val playbackSnapshot = buildString {
                    append("$wallTime playbackSnapshot ")
                    append("isPlaying=$isPlaying positionMs=$positionMs durationMs=$durationMs ")
                    append("source=\"${logField(audioSourceLabel)}\" ")
                    append("track=\"${logField(selectedTrack?.title.orEmpty())}\" ")
                    append("artist=\"${logField(currentTag?.artist.orEmpty())}\" ")
                    append("routeStatus=\"${logField(status)}\" ")
                    append("usbDacConnected=$usbDacConnected usbPermissionGranted=$usbDacPermissionGranted ")
                    append("usbDacName=\"${logField(usbDacDeviceName)}\" ")
                    append("bitPerfectStatus=automatic route detection")
                }
                if (sample != null && sample.size >= 36) {
                    usbDiagnosticLines.add(
                        "$playbackSnapshot ${createAaudioLogLine(sample, usbDiagnosticPreviousSample, openAttempt)}"
                    )
                    usbDiagnosticPreviousSample = sample
                } else {
                    usbDiagnosticLines.add("$playbackSnapshot aaudioDiagnostics=unavailable")
                }
                if (usbDiagnosticLines.size > 7200) usbDiagnosticLines.removeAt(0)
                kotlinx.coroutines.delay(1000)
            }
        }
    }
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
    val foreground = if (lightTheme) Color(0xFF292629) else Color(0xFFF4F0F0)
    val muted = if (lightTheme) Color(0xFF746A6E) else Color(0xFFC8B9BF)
    val background = if (lightTheme) Color(0xFFF7F6F4) else Color(0xFF171719)

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
                    tint = if (usbTransportActive) MaterialTheme.colorScheme.primary else foreground
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
                Destination.WELCOME -> WelcomeDestination(
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
                    onBackToPlayer = { destination = Destination.PLAYER; lyricsVisible = false },
                    onScan = onScan,
                    onRefreshFolder = onRefreshFolder,
                    onSelectedFolder = { selectedFolder = it },
                    onVirtualCueFolder = { virtualCueFolder = it },
                    onPlay = onPlay
                )
                Destination.DSP -> DspDestination(
                    volume = volume,
                    equalizerGains = equalizerGains,
                    equalizerEnabled = equalizerEnabled,
                    foreground = foreground,
                    muted = muted,
                    onVolume = onVolume,
                    onBandGain = onBandGain,
                    onEqualizerEnabled = onEqualizerEnabled
                )
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
                            appendLine("# AAudio and playback-state samples: ${usbDiagnosticLines.size}")
                            appendLine("# Direct USB events: ${usbDirectLog.lineSequence().count { it.isNotBlank() }}")
                            appendLine("# Software architecture: Kotlin Android integration; Rust decode/DSP/playback sessions; C++ libusb transport")
                            appendLine("# Hardware acceptance: pending physical Android/USB DAC verification; CI does not establish bit-perfect output")
                            appendLine("# Current playback: playing=$isPlaying positionMs=$positionMs durationMs=$durationMs")
                            appendLine("# Current source: $audioSourceLabel")
                            appendLine("# Current track: ${currentTag?.artist.orEmpty()} - ${selectedTrack?.title.orEmpty()}")
                            appendLine("# Current route status: $status")
                            appendLine("# USB DAC: connected=$usbDacConnected permissionGranted=$usbDacPermissionGranted name=$usbDacDeviceName")
                            appendLine("# Bit-perfect policy: automatic DAC capability and playback-path detection")
                            appendLine()
                            appendLine("===== USB descriptor and DAC profile database snapshot =====")
                            appendLine(usbDiagnosticDescriptorReport.ifBlank { "No descriptor snapshot was captured." })
                            appendLine()
                            appendLine("===== Playback/session state and AAudio runtime telemetry (1-second sampling) =====")
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
                    status = status, onBack = { settingsPage = when (settingsPage) {
                        SettingsPage.AUDIO, SettingsPage.LIBRARY, SettingsPage.APPEARANCE, SettingsPage.OTHER, SettingsPage.ABOUT -> SettingsPage.ROOT
                        SettingsPage.AUDIO_INFO, SettingsPage.USB_DIAGNOSTICS -> SettingsPage.AUDIO
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
