package com.waizusora.flowery

import android.app.ActivityManager
import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val usbPermissionAction = "com.waizusora.flowery.USB_PERMISSION"
    private val usbPermissionRequests = mutableSetOf<String>()
    private val usbPermissionDeniedDevices = mutableSetOf<String>()
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i("FloweryUsbPermission", "USB_DEVICE_ATTACHED")
                    requestUsbPermissionForConnectedAudioDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    detached?.deviceName?.let {
                        usbPermissionRequests.remove(it)
                        usbPermissionDeniedDevices.remove(it)
                    }
                    Log.i("FloweryUsbPermission", "USB_DEVICE_DETACHED device=${detached?.deviceName}")
                    status = "USB DAC disconnected"
                }
                usbPermissionAction -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                    val actualGrant = device != null && manager.hasPermission(device)
                    device?.deviceName?.let { deviceName ->
                        usbPermissionRequests.remove(deviceName)
                        if (!actualGrant) usbPermissionDeniedDevices.add(deviceName)
                    }
                    Log.i(
                        "FloweryUsbPermission",
                        "USB_PERMISSION_RESULT device=${device?.deviceName} grantedExtra=$granted hasPermission=$actualGrant"
                    )
                    status = when {
                        actualGrant -> "USB DAC permission granted · ready for USB playback"
                        device != null -> "USB DAC permission denied · approve the Android permission dialog to use USB audio"
                        else -> "USB permission response did not include a device"
                    }
                }
            }
        }
    }
    private fun requestUsbPermissionForConnectedAudioDevice() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { candidate ->
            (0 until candidate.interfaceCount).any { index ->
                val audioInterface = candidate.getInterface(index)
                audioInterface.interfaceClass == 1 && audioInterface.interfaceSubclass == 2 &&
                    (0 until audioInterface.endpointCount).any { endpointIndex ->
                        val endpoint = audioInterface.getEndpoint(endpointIndex)
                        endpoint.type == 1 && endpoint.direction == 0
                    }
            }
        } ?: run {
            Log.d("FloweryUsbPermission", "USB_AUDIO_DEVICE_NOT_FOUND")
            return
        }
        if (device.deviceName in usbPermissionDeniedDevices) {
            Log.i("FloweryUsbPermission", "USB_PERMISSION_DENIAL_SUPPRESSED_UNTIL_DETACH device=${device.deviceName}")
            return
        }
        if (manager.hasPermission(device)) {
            Log.i("FloweryUsbPermission", "USB_PERMISSION_ALREADY_GRANTED device=${device.deviceName}")
            status = "USB DAC permission granted · ready for USB playback"
            return
        }
        if (!usbPermissionRequests.add(device.deviceName)) return

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(usbPermissionAction).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        Log.i(
            "FloweryUsbPermission",
            "USB_PERMISSION_REQUEST device=${device.deviceName} vendorId=${device.vendorId} productId=${device.productId}"
        )
        runCatching { manager.requestPermission(device, permissionIntent) }
            .onFailure { error ->
                usbPermissionRequests.remove(device.deviceName)
                Log.e("FloweryUsbPermission", "USB_PERMISSION_REQUEST_FAILED device=${device.deviceName}", error)
                status = "Unable to request USB DAC permission: ${error.message}"
            }
    }
    private fun logPreviousProcessExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i("FloweryDiagnostics", "PROCESS_START api=${Build.VERSION.SDK_INT}; historical exit reasons require API 30+")
            return
        }
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        Log.i("FloweryDiagnostics", "PROCESS_START pid=${android.os.Process.myPid()} availableMemoryBytes=${memory.availMem} lowMemory=${memory.lowMemory} memoryThresholdBytes=${memory.threshold}")
        runCatching { activityManager.getHistoricalProcessExitReasons(packageName, 0, 5) }
            .onSuccess { exits ->
                exits.forEach { exit ->
                    Log.w("FloweryDiagnostics", "PREVIOUS_PROCESS_EXIT timestamp=${exit.timestamp} reason=${exit.reason} status=${exit.status} importance=${exit.importance} pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description.orEmpty()}")
                }
                if (exits.isEmpty()) Log.i("FloweryDiagnostics", "PREVIOUS_PROCESS_EXIT none reported by Android")
            }
            .onFailure { error -> Log.e("FloweryDiagnostics", "Unable to read historical process exit reasons", error) }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        getSharedPreferences("flowery_preferences", MODE_PRIVATE).edit()
            .putBoolean("notification_permission_requested", true).apply()
        status = if (granted) "Notifications enabled" else "Notifications are disabled; playback still works while Flowery is open"
    }
    private val folders = mutableStateListOf<LibraryFolder>()
    private val tracks = mutableStateListOf<AudioTrackItem>()
    private var status by mutableStateOf("Choose a music folder to begin")
    private var isPlaying by mutableStateOf(false)
    private var hasPlaybackSession by mutableStateOf(false)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableStateOf(0)
    private var selectedTrack by mutableStateOf<AudioTrackItem?>(null)
    private var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    private var currentTag by mutableStateOf<AudioTag?>(null)
    private var albumArtBytes by mutableStateOf<ByteArray?>(null)
    private var nowPlayingTitle by mutableStateOf("Nothing playing")
    private var nowPlayingArtist by mutableStateOf("Unknown artist")
    private var infoTag by mutableStateOf<AudioTag?>(null)
    private var editingTag by mutableStateOf<AudioTag?>(null)
    private var volume by mutableStateOf(1f)
    private var nextVolumeCommandId = 0L
    private var pendingVolumeCommandId: Long? = null
    private var playbackPositionMs by mutableStateOf(0L)
    private var playbackDurationMs by mutableStateOf(0L)
    private var playbackOutputSampleRateHz by mutableStateOf(0)
    private var playbackOutputBitDepth by mutableStateOf(0)
    private var playbackOutputBackend by mutableStateOf("—")
    private var playbackDecodedSampleRateHz by mutableStateOf(0)
    private var playbackDecodedPcmFormat by mutableStateOf("—")
    private var playbackOutputPcmFormat by mutableStateOf("—")
    private var playbackOutputResampling by mutableStateOf(false)
    private var playbackOutputBitDepthConversion by mutableStateOf(false)
    private var playbackOutputSampleFormatConversion by mutableStateOf(false)
    private var playbackOutputBitPerfectCandidate by mutableStateOf(false)
    private var playbackUsbStatusJson by mutableStateOf("")
    private var usbVolumeDetected by mutableStateOf(false)
    private var usbVolumeWritable by mutableStateOf(false)
    private var usbVolumeVerified by mutableStateOf(false)
    private var usbVolumeDb by mutableStateOf(Float.NaN)
    private var usbVolumeMinDb by mutableStateOf(Float.NaN)
    private var usbVolumeMaxDb by mutableStateOf(Float.NaN)
    private var usbVolumeResolutionDb by mutableStateOf(Float.NaN)
    private val io = Executors.newSingleThreadExecutor()
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            intent.getStringExtra(PlaybackService.EXTRA_STATUS)?.let { status = it }
            if (intent.hasExtra(PlaybackService.EXTRA_IS_PLAYING)) {
                isPlaying = intent.getBooleanExtra(PlaybackService.EXTRA_IS_PLAYING, false)
            }
            val reportedStatus = intent.getStringExtra(PlaybackService.EXTRA_STATUS)
            intent.getStringExtra(PlaybackService.EXTRA_CURRENT_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { nowPlayingTitle = it }
            intent.getStringExtra(PlaybackService.EXTRA_CURRENT_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { nowPlayingArtist = it }
            if (intent.hasExtra(PlaybackService.EXTRA_CURRENT_ARTWORK)) {
                albumArtBytes = intent.getByteArrayExtra(PlaybackService.EXTRA_CURRENT_ARTWORK)
            }
            if (reportedStatus == "Stopped" || reportedStatus == "Playback finished") hasPlaybackSession = false
            if (intent.hasExtra(PlaybackService.EXTRA_SHUFFLE_ENABLED)) {
                shuffleEnabled = intent.getBooleanExtra(PlaybackService.EXTRA_SHUFFLE_ENABLED, false)
            }
            if (intent.hasExtra(PlaybackService.EXTRA_REPEAT_MODE)) {
                repeatMode = intent.getIntExtra(PlaybackService.EXTRA_REPEAT_MODE, 0).coerceIn(0, 2)
            }
            if (intent.hasExtra(PlaybackService.EXTRA_VOLUME)) {
                val reportedVolume = intent.getFloatExtra(PlaybackService.EXTRA_VOLUME, volume).coerceIn(0f, 1f)
                val isVolumeAck = intent.getBooleanExtra(PlaybackService.EXTRA_VOLUME_COMMAND_COMPLETE, false)
                val acknowledgedId = intent.getLongExtra(PlaybackService.EXTRA_VOLUME_COMMAND_ID, -1L)
                if (isVolumeAck) {
                    if (pendingVolumeCommandId == acknowledgedId) {
                        pendingVolumeCommandId = null
                        volume = reportedVolume
                        Log.i("FloweryDiagnostics", "VOLUME_UI_SOURCE source=command_ack id=$acknowledgedId actual=$reportedVolume")
                    } else {
                        Log.i("FloweryDiagnostics", "VOLUME_UI_SOURCE source=stale_command_ack id=$acknowledgedId pending=$pendingVolumeCommandId")
                    }
                } else if (pendingVolumeCommandId == null) {
                    volume = reportedVolume
                } else {
                    Log.d("FloweryDiagnostics", "VOLUME_UI_SOURCE source=periodic_broadcast_ignored pending=$pendingVolumeCommandId reported=$reportedVolume")
                }
            }
            playbackPositionMs = intent.getLongExtra(PlaybackService.EXTRA_POSITION, playbackPositionMs)
            val reportedDuration = intent.getLongExtra(PlaybackService.EXTRA_DURATION, 0L)
            if (reportedDuration > 0L) playbackDurationMs = reportedDuration
            playbackOutputSampleRateHz = intent.getIntExtra(PlaybackService.EXTRA_OUTPUT_SAMPLE_RATE, playbackOutputSampleRateHz)
            playbackOutputBitDepth = intent.getIntExtra(PlaybackService.EXTRA_OUTPUT_BIT_DEPTH, playbackOutputBitDepth)
            playbackOutputBackend = intent.getStringExtra(PlaybackService.EXTRA_OUTPUT_BACKEND) ?: playbackOutputBackend
            playbackDecodedSampleRateHz = intent.getIntExtra(PlaybackService.EXTRA_DECODED_SAMPLE_RATE, playbackDecodedSampleRateHz)
            playbackDecodedPcmFormat = intent.getStringExtra(PlaybackService.EXTRA_DECODED_PCM_FORMAT) ?: playbackDecodedPcmFormat
            playbackOutputPcmFormat = intent.getStringExtra(PlaybackService.EXTRA_OUTPUT_PCM_FORMAT) ?: playbackOutputPcmFormat
            playbackOutputResampling = intent.getBooleanExtra(PlaybackService.EXTRA_OUTPUT_RESAMPLING, playbackOutputResampling)
            playbackOutputBitDepthConversion = intent.getBooleanExtra(PlaybackService.EXTRA_OUTPUT_BIT_DEPTH_CONVERSION, playbackOutputBitDepthConversion)
            playbackOutputSampleFormatConversion = intent.getBooleanExtra(PlaybackService.EXTRA_OUTPUT_SAMPLE_FORMAT_CONVERSION, playbackOutputSampleFormatConversion)
            playbackOutputBitPerfectCandidate = intent.getBooleanExtra(PlaybackService.EXTRA_OUTPUT_BIT_PERFECT_CANDIDATE, playbackOutputBitPerfectCandidate)
            playbackUsbStatusJson = intent.getStringExtra(PlaybackService.EXTRA_USB_PLAYBACK_STATUS) ?: playbackUsbStatusJson
            if (intent.hasExtra(PlaybackService.EXTRA_USB_VOLUME_DETECTED)) {
                usbVolumeDetected = intent.getBooleanExtra(PlaybackService.EXTRA_USB_VOLUME_DETECTED, false)
                usbVolumeWritable = intent.getBooleanExtra(PlaybackService.EXTRA_USB_VOLUME_WRITABLE, false)
                usbVolumeVerified = intent.getBooleanExtra(PlaybackService.EXTRA_USB_VOLUME_VERIFIED, false)
                usbVolumeDb = intent.getFloatExtra(PlaybackService.EXTRA_USB_VOLUME_DB, Float.NaN)
                usbVolumeMinDb = intent.getFloatExtra(PlaybackService.EXTRA_USB_VOLUME_MIN_DB, Float.NaN)
                usbVolumeMaxDb = intent.getFloatExtra(PlaybackService.EXTRA_USB_VOLUME_MAX_DB, Float.NaN)
                usbVolumeResolutionDb = intent.getFloatExtra(PlaybackService.EXTRA_USB_VOLUME_RESOLUTION_DB, Float.NaN)
            }
            val currentUri = intent.getStringExtra(PlaybackService.EXTRA_CURRENT_URI)
            val currentCueStart = intent.getLongExtra(PlaybackService.EXTRA_CURRENT_CUE_START, 0L)
            val currentCuePlaylist = intent.getStringExtra(PlaybackService.EXTRA_CURRENT_CUE_PLAYLIST).orEmpty().ifBlank { null }
            val currentPlaylistName = intent.getStringExtra(PlaybackService.EXTRA_CURRENT_PLAYLIST_NAME).orEmpty().ifBlank { null }
            val currentPlaylistOrder = intent.getIntExtra(PlaybackService.EXTRA_CURRENT_PLAYLIST_ORDER, -1).takeIf { it >= 0 }
            val nextTrack = tracks.firstOrNull {
                it.uri.toString() == currentUri && (it.cueStartMs ?: 0L) == currentCueStart &&
                    it.cuePlaylist == currentCuePlaylist && it.playlistName == currentPlaylistName &&
                    it.playlistOrder == currentPlaylistOrder
            } ?: tracks.firstOrNull {
                it.uri.toString() == currentUri && (it.cueStartMs ?: 0L) == currentCueStart
            }
            if (nextTrack != null) {
                hasPlaybackSession = true
                persistLastPlayedTrack(nextTrack)
            }
            if (reportedStatus == "Stopped" || reportedStatus == "Playback finished") hasPlaybackSession = false
            if (nextTrack != null && (selectedTrack?.uri != nextTrack.uri || selectedTrack?.cueStartMs != nextTrack.cueStartMs ||
                    selectedTrack?.cuePlaylist != nextTrack.cuePlaylist || selectedTrack?.playlistName != nextTrack.playlistName ||
                    selectedTrack?.playlistOrder != nextTrack.playlistOrder)) {
                selectedTrack = nextTrack
                playbackDurationMs = 0L
                currentTag = null
                lyrics = emptyList()
                io.execute {
                    val tag = runCatching { readTag(nextTrack) }.getOrNull()
                    val parsed = nextTrack.sidecarLyricsUri?.let { lyricUri ->
                        runCatching { contentResolver.openInputStream(lyricUri)?.bufferedReader()?.use { LrcParser.parse(it.readText()) } }.getOrNull()
                    }.orEmpty()
                    runOnUiThread {
                        currentTag = tag
                        lyrics = parsed
                        if (playbackDurationMs <= 0L) playbackDurationMs = resolveTrackDurationMs(nextTrack, tag)
                        sendPlaybackCommand(PlaybackService.ACTION_UPDATE_METADATA) {
                            val virtualTitle = nextTrack.cuePlaylist != null || nextTrack.playlistName != null
                            putExtra(PlaybackService.EXTRA_TITLE,
                                if (virtualTitle) nextTrack.title else tag?.title?.ifBlank { nextTrack.title } ?: nextTrack.title)
                            putExtra(PlaybackService.EXTRA_ARTIST, tag?.artist?.ifBlank { "Unknown artist" } ?: "Unknown artist")
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logPreviousProcessExitReasons()
        val audioPreferences = getSharedPreferences("flowery_preferences", MODE_PRIVATE)
        restoreFolders()
        restoreScannedTracks()
        restoreLastPlayedTrack()
        refreshOutputVolume()
        setContent {
            val savedTheme = getSharedPreferences("flowery_preferences", MODE_PRIVATE)
                .getString("theme", "Auto")
            val darkTheme = when (savedTheme) {
                "Light" -> false
                "Black", "Dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            FloweryTheme(darkTheme = darkTheme) {
            FloweryNavigation(
                context = this,
                folders = folders,
                tracks = tracks,
                status = status,
                audioSourceLabel = currentTag?.fileName ?: selectedTrack?.title ?: "No file selected",
                isPlaying = isPlaying,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                selectedTrack = selectedTrack,
                currentTag = currentTag,
                albumArtBytes = albumArtBytes,
                displayTitle = nowPlayingTitle,
                displayArtist = nowPlayingArtist,
                lyrics = lyrics,
                positionMs = playbackPositionMs,
                durationMs = playbackDurationMs,
                outputSampleRateHz = playbackOutputSampleRateHz,
                outputBitDepth = playbackOutputBitDepth,
                outputBackend = playbackOutputBackend,
                decodedSampleRateHz = playbackDecodedSampleRateHz,
                decodedPcmFormat = playbackDecodedPcmFormat,
                outputPcmFormat = playbackOutputPcmFormat,
                outputResamplingRequired = playbackOutputResampling,
                outputBitDepthConversionRequired = playbackOutputBitDepthConversion,
                outputSampleFormatConversionRequired = playbackOutputSampleFormatConversion,
                outputBitPerfectCandidate = playbackOutputBitPerfectCandidate,
                usbPlaybackStatusJson = playbackUsbStatusJson,
                volume = volume,
                usbVolumeDetected = usbVolumeDetected,
                usbVolumeWritable = usbVolumeWritable,
                usbVolumeVerified = usbVolumeVerified,
                usbVolumeDb = usbVolumeDb,
                usbVolumeMinDb = usbVolumeMinDb,
                usbVolumeMaxDb = usbVolumeMaxDb,
                usbVolumeResolutionDb = usbVolumeResolutionDb,
                onAddFolder = { folderPicker.launch(null) },
                onRequestNotificationPermission = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
                onRequestBatteryOptimization = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")))
                    }
                },
                onRequestUsbPermission = ::requestUsbPermissionForConnectedAudioDevice,
                onRemoveFolder = ::removeFolder,
                onToggleFolder = ::toggleFolder,
                onScan = ::scanLibrary,
                onRefreshFolder = ::refreshFolder,
                onPlay = { track, folderTracks -> playTrack(track, folderTracks) },
                onInspect = ::inspectTrack,
                onEdit = { currentTag?.let { if (it.writable) editingTag = it else infoTag = it } },
                onTogglePlayback = {
                    if (isPlaying) {
                        sendPlaybackCommand(PlaybackService.ACTION_PAUSE)
                    } else if (!hasPlaybackSession) {
                        val lastTrack = selectedTrack ?: findLastPlayedTrack()
                        if (lastTrack != null) playTrack(lastTrack, tracks.toList())
                        else sendPlaybackCommand(PlaybackService.ACTION_RESUME)
                    } else {
                        sendPlaybackCommand(PlaybackService.ACTION_RESUME)
                    }
                },
                onVolume = {
                    val requested = it.coerceIn(0f, 1f)
                    val commandId = ++nextVolumeCommandId
                    pendingVolumeCommandId = commandId
                    Log.i(
                        "FloweryDiagnostics",
                        "VOLUME_UI_SOURCE source=slider_commit id=$commandId requested=$requested"
                    )
                    volume = requested
                    sendPlaybackCommand(PlaybackService.ACTION_VOLUME) {
                        putExtra(PlaybackService.EXTRA_VALUE, requested)
                        putExtra(PlaybackService.EXTRA_VOLUME_COMMAND_ID, commandId)
                    }
                },
                onPrevious = { sendPlaybackCommand(PlaybackService.ACTION_PREVIOUS) },
                onNext = { sendPlaybackCommand(PlaybackService.ACTION_NEXT) },
                onShuffle = { sendPlaybackCommand(PlaybackService.ACTION_SHUFFLE) },
                onRepeat = { sendPlaybackCommand(PlaybackService.ACTION_REPEAT) },
                onSeek = { position -> sendPlaybackCommand(PlaybackService.ACTION_SEEK) { putExtra(PlaybackService.EXTRA_POSITION, position) } },
                onBatterySettings = {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                },
                onCheckUpdate = {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Waizusora/Flowery/releases")))
                }
            )

            infoTag?.let { tag -> TagInfoDialog(tag, onDismiss = { infoTag = null }) }
            editingTag?.let { tag ->
                TagEditorDialog(
                    tag = tag,
                    onDismiss = { editingTag = null },
                    onSave = { edited -> editingTag = null; saveTags(edited) }
                )
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(this, playbackReceiver, IntentFilter(PlaybackService.BROADCAST_STATE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        val usbFilter = IntentFilter(usbPermissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, usbPermissionReceiver, usbFilter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        if (PlaybackRecoverySnapshot.hasStoredSnapshot(
                getSharedPreferences("playback_recovery", MODE_PRIVATE)
            )) {
            Log.i("FloweryDiagnostics", "RECOVERY_ACTIVITY_REQUEST")
            sendPlaybackCommand(PlaybackService.ACTION_RESTORE)
        }
    }

    override fun onResume() {
        super.onResume()
        requestUsbPermissionForConnectedAudioDevice()
    }

    override fun onStop() {
        unregisterReceiver(playbackReceiver)
        unregisterReceiver(usbPermissionReceiver)
        usbPermissionRequests.clear()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refreshOutputVolume() {
        val restoredVolume = OutputVolumeController(this).systemVolumeFraction()
        Log.i("FloweryDiagnostics", "VOLUME_UI_SOURCE source=initial_system_restore value=$restoredVolume")
        volume = restoredVolume
    }









    private fun sendPlaybackCommand(action: String, configure: Intent.() -> Unit = {}) {
        val command = Intent(this, PlaybackService::class.java).setAction(action).apply(configure)
        startService(command)
    }

    private fun addFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { error ->
            Log.w("FloweryDiagnostics", "SAF_PERSIST_READ_GRANT_FAILURE uri=$uri", error)
        }
        val persistedGrants = contentResolver.persistedUriPermissions.map {
            SafReadGrant(it.uri.toString(), it.isReadPermission)
        }
        if (!SafPermissionPolicy.hasReadGrant(uri.toString(), persistedGrants)) {
            Log.e("FloweryDiagnostics", "SAF_FOLDER_REJECTED_NO_PERSISTED_READ_GRANT uri=$uri")
            status = "Folder access could not be retained · select the folder again"
            return
        }
        if (folders.none { it.uri == uri.toString() }) {
            getSharedPreferences("flowery_preferences", MODE_PRIVATE).edit()
                .putBoolean("welcome_complete", true).apply()
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Folder"
            folders += LibraryFolder(uri.toString(), name, true)
            persistFolders()
            status = "Folder added: $name"
        }
    }

    private fun removeFolder(folder: LibraryFolder) {
        folders.remove(folder)
        runCatching {
            val uri = Uri.parse(folder.uri)
            val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            var flags = 0
            if (grant?.isReadPermission == true) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (grant?.isWritePermission == true) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) contentResolver.releasePersistableUriPermission(uri, flags)
        }
        persistFolders()
        tracks.removeAll { it.folder == folder.name || it.folder.startsWith("${folder.name}/") }
        persistScannedTracks()
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
                    persistScannedTracks()
                    status = "Scan complete · ${found.size} audio files"
                }
            } catch (error: Throwable) {
                runOnUiThread { status = "Scan failed: ${error.message}" }
            }
        }
    }

    private fun refreshFolder(folder: LibraryFolder) {
        status = "Refreshing ${folder.name}…"
        io.execute {
            try {
                val refreshed = LibraryScanner(this).scan(listOf(folder))
                runOnUiThread {
                    tracks.removeAll { it.folder == folder.name || it.folder.startsWith("${folder.name}/") }
                    tracks.addAll(refreshed)
                    persistScannedTracks()
                    status = "Folder refreshed · ${refreshed.size} audio files"
                }
            } catch (error: Throwable) {
                runOnUiThread { status = "Refresh failed: ${error.message}" }
            }
        }
    }

    private fun persistScannedTracks() {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("uri", track.uri.toString())
                put("title", track.title)
                put("folder", track.folder)
                put("mimeType", track.mimeType ?: JSONObject.NULL)
                put("lyricsUri", track.sidecarLyricsUri?.toString() ?: JSONObject.NULL)
                put("cueStartMs", track.cueStartMs ?: JSONObject.NULL)
                put("cueEndMs", track.cueEndMs ?: JSONObject.NULL)
                put("cuePlaylist", track.cuePlaylist ?: JSONObject.NULL)
                put("playlistName", track.playlistName ?: JSONObject.NULL)
                put("playlistOrder", track.playlistOrder ?: JSONObject.NULL)
            })
        }
        getSharedPreferences("library", MODE_PRIVATE).edit()
            .putString("scanned_tracks", array.toString())
            .putLong("last_scan_time", System.currentTimeMillis())
            .apply()
    }

    private fun restoreScannedTracks() {
        val stored = getSharedPreferences("library", MODE_PRIVATE).getString("scanned_tracks", null) ?: return
        runCatching {
            val array = JSONArray(stored)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))
                val lyrics = item.optString("lyricsUri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse)
                tracks += AudioTrackItem(
                    uri = uri,
                    title = item.optString("title"),
                    folder = item.optString("folder"),
                    mimeType = item.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                    sidecarLyricsUri = lyrics,
                    cueStartMs = if (item.has("cueStartMs") && !item.isNull("cueStartMs")) item.optLong("cueStartMs") else null,
                    cueEndMs = if (item.has("cueEndMs") && !item.isNull("cueEndMs")) item.optLong("cueEndMs") else null,
                    cuePlaylist = item.optString("cuePlaylist").takeIf { it.isNotBlank() && it != "null" },
                    playlistName = item.optString("playlistName").takeIf { it.isNotBlank() && it != "null" },
                    playlistOrder = if (item.has("playlistOrder") && !item.isNull("playlistOrder")) item.optInt("playlistOrder") else null
                )
            }
            status = if (tracks.isEmpty()) "Choose a music folder to begin"
                else "Library restored · ${tracks.size} indexed audio files"
        }.onFailure {
            tracks.clear()
            status = "Saved library index could not be restored"
        }
    }

    private fun persistLastPlayedTrack(track: AudioTrackItem) {
        getSharedPreferences("library", MODE_PRIVATE).edit()
            .putString("last_played_uri", track.uri.toString())
            .putLong("last_played_cue_start_ms", track.cueStartMs ?: 0L)
            .putString("last_played_cue_playlist", track.cuePlaylist.orEmpty())
            .putString("last_played_playlist_name", track.playlistName.orEmpty())
            .putInt("last_played_playlist_order", track.playlistOrder ?: -1)
            .apply()
    }

    private fun findLastPlayedTrack(): AudioTrackItem? {
        val preferences = getSharedPreferences("library", MODE_PRIVATE)
        val uri = preferences.getString("last_played_uri", null) ?: return null
        val cueStart = preferences.getLong("last_played_cue_start_ms", 0L)
        val cuePlaylist = preferences.getString("last_played_cue_playlist", "").orEmpty().ifBlank { null }
        val playlistName = preferences.getString("last_played_playlist_name", "").orEmpty().ifBlank { null }
        val playlistOrder = preferences.getInt("last_played_playlist_order", -1).takeIf { it >= 0 }
        return tracks.firstOrNull { track ->
            track.uri.toString() == uri &&
                (track.cueStartMs ?: 0L) == cueStart &&
                track.cuePlaylist == cuePlaylist &&
                track.playlistName == playlistName &&
                track.playlistOrder == playlistOrder
        } ?: tracks.firstOrNull { it.uri.toString() == uri && (it.cueStartMs ?: 0L) == cueStart }
    }

    private fun restoreLastPlayedTrack() {
        val track = findLastPlayedTrack() ?: return
        selectedTrack = track
        io.execute {
            val tag = runCatching { readTag(track) }.getOrNull()
            val parsedLyrics = track.sidecarLyricsUri?.let { lyricUri ->
                runCatching {
                    contentResolver.openInputStream(lyricUri)?.bufferedReader()?.use { LrcParser.parse(it.readText()) }
                }.getOrNull()
            }.orEmpty()
            val durationMs = resolveTrackDurationMs(track, tag)
            runOnUiThread {
                if (selectedTrack?.uri == track.uri &&
                    selectedTrack?.cueStartMs == track.cueStartMs &&
                    selectedTrack?.cuePlaylist == track.cuePlaylist &&
                    selectedTrack?.playlistName == track.playlistName &&
                    selectedTrack?.playlistOrder == track.playlistOrder) {
                    currentTag = tag
                    lyrics = parsedLyrics
                    playbackDurationMs = durationMs
                    nowPlayingTitle = tag?.title?.takeIf { it.isNotBlank() }
                        ?: track.title.ifBlank { "Unknown track" }
                    nowPlayingArtist = tag?.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                    if (!hasPlaybackSession) status = "Ready to resume · $nowPlayingTitle"
                }
            }
        }
    }
    private fun extensionOf(track: AudioTrackItem): String =
        Uri.decode(track.uri.lastPathSegment.orEmpty()).substringAfterLast('.', "")

    private fun readTag(track: AudioTrackItem): AudioTag {
        val jsonText = contentResolver.openFileDescriptor(track.uri, "r")?.use { descriptor ->
            NativeAudio.nativeReadTagsFromFd(descriptor.fd, extensionOf(track), cacheDir.absolutePath)
        } ?: throw IllegalStateException("Unable to open selected audio file")
        val json = JSONObject(jsonText)
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


    private fun resolveTrackDurationMs(track: AudioTrackItem, tag: AudioTag?): Long {
        val sourceDuration = tag?.durationMs?.takeIf { it > 0L } ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, track.uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        val start = (track.cueStartMs ?: 0L).coerceAtLeast(0L)
        val end = (track.cueEndMs ?: sourceDuration).takeIf { it > 0L } ?: sourceDuration
        return (end - start).coerceAtLeast(0L)
    }

    private fun playTrack(track: AudioTrackItem, folderTracks: List<AudioTrackItem> = tracks.toList()) {
        val playbackTracks = when {
            track.cuePlaylist != null -> folderTracks.filter { it.cuePlaylist == track.cuePlaylist }.sortedBy { it.cueStartMs ?: 0L }
            track.playlistName != null -> folderTracks.filter { it.playlistName == track.playlistName }.sortedBy { it.playlistOrder ?: Int.MAX_VALUE }
            else -> folderTracks.filter { it.cuePlaylist == null && it.playlistName == null }
        }
        selectedTrack = track
        persistLastPlayedTrack(track)
        hasPlaybackSession = false
        currentTag = null
        lyrics = emptyList()
        status = "Reading tags and preparing ${track.title}…"
        io.execute {
            try {
                val tag = runCatching { readTag(track) }.getOrNull()
                val parsedLyrics = track.sidecarLyricsUri?.let { lyricUri ->
                    contentResolver.openInputStream(lyricUri)?.bufferedReader()?.use { LrcParser.parse(it.readText()) }
                }.orEmpty()
                runOnUiThread {
                    currentTag = tag
                    lyrics = parsedLyrics
                    playbackPositionMs = 0L
                    playbackDurationMs = resolveTrackDurationMs(track, tag)
                    if (tag == null) status = "Tags unavailable for this format · playback continues"
                    val queueUris = ArrayList(playbackTracks.map { it.uri.toString() })
                    val queueTitles = ArrayList(playbackTracks.map { item ->
                        if (item.cuePlaylist == null && item.playlistName == null && item.uri == track.uri) {
                            tag?.title?.ifBlank { item.title } ?: item.title
                        } else item.title
                    })
                    val queueArtists = ArrayList(playbackTracks.map { item ->
                        if (item.cuePlaylist == null && item.playlistName == null && item.uri == track.uri) {
                            tag?.artist?.ifBlank { "Unknown artist" } ?: "Unknown artist"
                        } else "Local music"
                    })
                    val queueIndex = playbackTracks.indexOfFirst { it.uri == track.uri && it.cueStartMs == track.cueStartMs && it.playlistOrder == track.playlistOrder }.coerceAtLeast(0)
                    val queueCueStarts = playbackTracks.map { it.cueStartMs ?: 0L }.toLongArray()
                    val queueCueEnds = playbackTracks.map { it.cueEndMs ?: 0L }.toLongArray()
                    val command = Intent(this, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_PLAY)
                        .putStringArrayListExtra(PlaybackService.EXTRA_QUEUE, queueUris)
                        .putStringArrayListExtra(PlaybackService.EXTRA_TITLES, queueTitles)
                        .putStringArrayListExtra(PlaybackService.EXTRA_ARTISTS, queueArtists)
                        .putExtra(PlaybackService.EXTRA_CUE_STARTS, queueCueStarts)
                        .putExtra(PlaybackService.EXTRA_CUE_ENDS, queueCueEnds)
                        .putStringArrayListExtra(PlaybackService.EXTRA_CUE_PLAYLISTS, ArrayList(playbackTracks.map { it.cuePlaylist.orEmpty() }))
                        .putStringArrayListExtra(PlaybackService.EXTRA_PLAYLIST_NAMES, ArrayList(playbackTracks.map { it.playlistName.orEmpty() }))
                        .putIntegerArrayListExtra(PlaybackService.EXTRA_PLAYLIST_ORDERS, ArrayList(playbackTracks.map { it.playlistOrder ?: -1 }))
                        .putExtra(PlaybackService.EXTRA_INDEX, queueIndex)
                    androidx.core.content.ContextCompat.startForegroundService(this, command)
                }
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
                val extension = extensionOf(tracks.firstOrNull { it.uri == tag.uri } ?: AudioTrackItem(tag.uri, tag.fileName, "", null))
                val stagedPath = contentResolver.openFileDescriptor(tag.uri, "r")?.use { descriptor ->
                    NativeAudio.nativeWriteTagsFromFd(
                        descriptor.fd, extension, cacheDir.absolutePath, tag.title, tag.artist, tag.album,
                        tag.albumArtist, tag.genre, tag.year, tag.track, tag.disc, tag.comment, tag.composer, tag.lyrics
                    )
                } ?: throw IllegalStateException("Unable to open original audio file")
                val stagedFile = java.io.File(stagedPath)
                try {
                    contentResolver.openOutputStream(tag.uri, "wt")?.use { output ->
                        stagedFile.inputStream().use { input -> input.copyTo(output) }
                        output.flush()
                    } ?: throw IllegalStateException("The selected document provider does not allow writing this file")
                } finally {
                    stagedFile.delete()
                }
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


}

private val Gold = Color(0xFFE0E0E0)
private val DeepGold = Color(0xFF777B82)
private val Ink = Color(0xFF29251B)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun FloweryApp(
    folders: List<LibraryFolder>,
    tracks: List<AudioTrackItem>,
    status: String,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    selectedTrack: AudioTrackItem?,
    currentTag: AudioTag?,
    lyrics: List<LyricLine>,
    volume: Float,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit,
    onToggleFolder: (LibraryFolder, Boolean) -> Unit,
    onScan: () -> Unit,
    onPlay: (AudioTrackItem) -> Unit,
    onInspect: (AudioTrackItem) -> Unit,
    onEdit: () -> Unit,
    onTogglePlayback: () -> Unit,
    onVolume: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    MaterialTheme {
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF42454B), Color(0xFF777B82), Color(0xFFB0B2B6))))
                    .verticalScroll(rememberScrollState())
                    .padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
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
                                androidx.compose.foundation.Image(bitmap = art, contentDescription = "Embedded album artwork", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                            } else {
                                Box(Modifier.fillMaxSize().background(Color(0xFF686C73)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, contentDescription = "No artwork", tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.size(72.dp))
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onEdit).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        currentTag?.title?.ifBlank { selectedTrack?.title ?: "Nothing playing" } ?: selectedTrack?.title ?: "Nothing playing",
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    Text(
                        currentTag?.artist?.ifBlank { "Unknown artist" } ?: "Unknown artist",
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.92f),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onShuffle) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(
                        onClick = onTogglePlayback,
                        modifier = Modifier.size(76.dp).background(DeepGold, RoundedCornerShape(40.dp))
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onRepeat) {
                        Icon(
                            if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = when (repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" },
                            tint = if (repeatMode == 0) Color.White else Color(0xFF1E90FF),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (playbackDurationMs > 0L) {
                        val safeDuration = playbackDurationMs.coerceAtLeast(1L)
                        Slider(
                            value = playbackPositionMs.coerceIn(0L, safeDuration).toFloat(),
                            onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..safeDuration.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${formatPlaybackTime(playbackPositionMs)} / ${formatPlaybackTime(playbackDurationMs)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                    currentTag?.let { tag ->
                        val playbackDetails = listOfNotNull(
                            tag.bitDepth.takeIf { it > 0 }?.let { "Bit depth: $it-bit" },
                            tag.sampleRate.takeIf { it > 0 }?.let { "Sample rate: ${"%.1f".format(java.util.Locale.ROOT, it / 1000.0)} kHz" },
                            tag.bitrateKbps.takeIf { it > 0 }?.let { "Bit rate: $it kbps" },
                            tag.channels.takeIf { it > 0 }?.let { "Channel: $it ch" }
                        )
                        if (playbackDetails.isNotEmpty()) {
                            Text(
                                playbackDetails.joinToString("  ·  "),
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (lyrics.isNotEmpty()) {
                    val currentLyric = LrcParser.currentLine(lyrics, playbackPositionMs)
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0x44FFFFFF))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("LYRICS · LRC", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                currentLyric?.text ?: "Waiting for the first lyric timestamp…",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
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
                val cueTracks = tracks.filter { it.cuePlaylist != null }
                if (cueTracks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color.White)
                        Text("CUE PLAYLISTS · ${cueTracks.mapNotNull { it.cuePlaylist }.distinct().size}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                    cueTracks.groupBy { it.cuePlaylist }.forEach { (playlistName, entries) ->
                        Text(playlistName ?: "CUE playlist", color = Gold, fontWeight = FontWeight.SemiBold)
                        entries.sortedBy { it.cueStartMs ?: 0L }.forEach { track ->
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { onPlay(track) }, onLongClick = { onInspect(track) }).padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.MusicNote, null, tint = Color.White)
                                Text(track.title, color = Color.White, modifier = Modifier.weight(1f).padding(start = 10.dp))
                                if (selectedTrack?.uri == track.uri && selectedTrack?.cueStartMs == track.cueStartMs) Icon(Icons.Default.PlayArrow, "Selected", tint = Gold)
                            }
                        }
                    }
                }
                val regularTracks = tracks.filter { it.cuePlaylist == null }
                Text("LIBRARY · ${regularTracks.size} TRACKS", color = Color.White, fontWeight = FontWeight.Bold)
                regularTracks.forEach { track ->
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
                Text("OUTPUT VOLUME", color = Color.White)
                Slider(value = volume, onValueChange = onVolume)
            }
        }
    }
}

internal fun formatPlaybackTime(valueMs: Long): String {
    val seconds = valueMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
