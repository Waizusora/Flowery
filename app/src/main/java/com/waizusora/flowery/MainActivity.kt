package com.waizusora.flowery

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import android.hardware.usb.UsbDevice
import android.util.Base64
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
    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
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
    private var infoTag by mutableStateOf<AudioTag?>(null)
    private var editingTag by mutableStateOf<AudioTag?>(null)
    private var volume by mutableStateOf(1f)
    private var usbDacMessage by mutableStateOf<String?>(null)
    private val usbDacController by lazy { UsbDacController(this) }
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbDacController.ACTION_USB_PERMISSION) return
            val device = intent.usbDeviceExtra()
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            usbDacMessage = when {
                device == null -> "Android did not return the USB audio device."
                granted && usbDacController.hasCompatibleUac2PlaybackProfile(device) -> {
                    run {
                        val volumeController = OutputVolumeController(this@MainActivity)
                        val key = usbDacController.deviceIdentity(device)
                        val hardwareVolume = usbDacController.readHardwareVolumeFraction(device)
                        volumeController.usbProfile(key, hardwareVolume != null)
                        volume = hardwareVolume ?: volumeController.usbVolume(key)
                    }
                    getSharedPreferences("flowery_preferences", MODE_PRIVATE).edit()
                        .putBoolean("usb_bit_perfect_enabled", true)
                        .apply()
                    "USB permission granted for ${usbDacController.describeDevice(device)}. Flowery identified a compatible stereo UAC1/UAC2 playback profile from its descriptors. All supported audio is decoded by Symphonia into the shared PCM path. Bit Perfect is not currently certified."
                }
                granted -> {
                    getSharedPreferences("flowery_preferences", MODE_PRIVATE).edit()
                        .putBoolean("usb_bit_perfect_enabled", false)
                        .apply()
                    "Flowery recognized ${usbDacController.describeDevice(device)}, but its descriptors do not expose a usable stereo UAC1/UAC2 playback profile. The DAC remains available through Android's normal audio route."
                }
                else -> "USB access was not granted. The DAC remains on the regular Android audio route."
            }
        }
    }
    private var playbackPositionMs by mutableStateOf(0L)
    private var playbackDurationMs by mutableStateOf(0L)
    private val equalizerGains = mutableStateListOf<Float>().apply { repeat(31) { add(0f) } }
    private val io = Executors.newSingleThreadExecutor()
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            intent.getStringExtra(PlaybackService.EXTRA_STATUS)?.let { status = it }
            if (intent.hasExtra(PlaybackService.EXTRA_IS_PLAYING)) {
                isPlaying = intent.getBooleanExtra(PlaybackService.EXTRA_IS_PLAYING, false)
            }
            val reportedStatus = intent.getStringExtra(PlaybackService.EXTRA_STATUS)
            if (reportedStatus == "Stopped" || reportedStatus == "Playback finished") hasPlaybackSession = false
            if (intent.hasExtra(PlaybackService.EXTRA_SHUFFLE_ENABLED)) {
                shuffleEnabled = intent.getBooleanExtra(PlaybackService.EXTRA_SHUFFLE_ENABLED, false)
            }
            if (intent.hasExtra(PlaybackService.EXTRA_REPEAT_MODE)) {
                repeatMode = intent.getIntExtra(PlaybackService.EXTRA_REPEAT_MODE, 0).coerceIn(0, 2)
            }
            playbackPositionMs = intent.getLongExtra(PlaybackService.EXTRA_POSITION, playbackPositionMs)
            val reportedDuration = intent.getLongExtra(PlaybackService.EXTRA_DURATION, 0L)
            if (reportedDuration > 0L) playbackDurationMs = reportedDuration
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
        getSharedPreferences("flowery_preferences", MODE_PRIVATE).edit()
            .putBoolean("usb_diagnostic_recording", false).apply()
        restoreFolders()
        restoreScannedTracks()
        restoreLastPlayedTrack()
        refreshOutputVolume()
        setContent {
            FloweryNavigation(
                context = this,
                folders = folders,
                tracks = tracks,
                status = status,
                onUsbDacClick = ::requestUsbDacAccess,
                isPlaying = isPlaying,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                selectedTrack = selectedTrack,
                currentTag = currentTag,
                lyrics = lyrics,
                volume = volume,
                positionMs = playbackPositionMs,
                durationMs = playbackDurationMs,
                equalizerGains = equalizerGains,
                onBandGain = { index, gain ->
                    equalizerGains[index] = gain
                    sendPlaybackCommand(PlaybackService.ACTION_EQUALIZER) {
                        putExtra(PlaybackService.EXTRA_GAINS, equalizerGains.toFloatArray())
                    }
                },
                onAddFolder = { folderPicker.launch(null) },
                onRequestNotificationPermission = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
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
                onVolume = { volume = it; sendPlaybackCommand(PlaybackService.ACTION_VOLUME) { putExtra(PlaybackService.EXTRA_VALUE, it) } },
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
            usbDacMessage?.let { message ->
                AlertDialog(onDismissRequest = { usbDacMessage = null },
                    title = { Text("USB DAC") },
                    text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = { TextButton(onClick = { usbDacMessage = null }) { Text("Close") } })
            }
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

    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(this, playbackReceiver, IntentFilter(PlaybackService.BROADCAST_STATE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(this, usbPermissionReceiver, IntentFilter(UsbDacController.ACTION_USB_PERMISSION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(playbackReceiver)
        unregisterReceiver(usbPermissionReceiver)
        super.onStop()
    }

    private fun refreshOutputVolume() {
        val volumeController = OutputVolumeController(this)
        val connectedDac = usbDacController.audioDevices().singleOrNull()
        volume = connectedDac?.let { device ->
            val hardwareVolume = usbDacController.readHardwareVolumeFraction(device.usbDevice)
            volumeController.usbProfile(device.identity, hardwareVolume != null)
            hardwareVolume ?: volumeController.usbVolume(device.identity)
        } ?: volumeController.systemVolumeFraction()
    }

    private fun requestUsbDacAccess() {
        val devices = usbDacController.audioDevices()
        refreshOutputVolume()
        if (devices.isEmpty()) {
            usbDacMessage = "No USB Audio Class playback device was detected. Connect a USB DAC and try again."
            return
        }

        // Prefer a device for which Android permission is already granted. If none is
        // authorized yet, request access to the first enumerated device.
        val detected = devices.firstOrNull {
            usbDacController.hasPermission(it.usbDevice) &&
                usbDacController.hasCompatibleUac2PlaybackProfile(it.usbDevice)
        } ?: devices.firstOrNull { !usbDacController.hasPermission(it.usbDevice) }
            ?: devices.first()
        val device = detected.usbDevice
        if (usbDacController.hasPermission(device)) {
            if (!usbDacController.hasCompatibleUac2PlaybackProfile(device)) {
                usbDacMessage = "Flowery recognized ${detected.displayName}, but direct USB playback requires a compatible stereo UAC1 Type-I PCM or UAC2 profile. Android's regular audio route remains available."
                return
            }
            val preferences = getSharedPreferences("flowery_preferences", MODE_PRIVATE)
            val enabled = preferences.getBoolean("usb_bit_perfect_enabled", false)
            preferences.edit().putBoolean("usb_bit_perfect_enabled", !enabled).apply()
            usbDacMessage = if (enabled) {
                "Direct USB exclusive mode disabled. Playback will use the normal Flowery DSP and Android AAudio route."
            } else {
                "Direct USB exclusive mode enabled for compatible stereo UAC1/UAC2 DACs. All formats use Symphonia and the shared PCM path; Bit Perfect is not currently certified. Flowery tries permitted devices using descriptor-derived capabilities."
            }
        } else {
            usbDacController.requestPermission(device)
            usbDacMessage = "Android will ask whether Flowery may access ${detected.displayName}. Permission grants device access only. Direct playback requires a compatible stereo UAC1 Type-I PCM or UAC2 PCM profile and successful sample-rate negotiation; decoded formats are not bit-perfect."
        }
    }

    private fun sendPlaybackCommand(action: String, configure: Intent.() -> Unit = {}) {
        val command = Intent(this, PlaybackService::class.java).setAction(action).apply(configure)
        startService(command)
    }

    private fun addFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
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
            contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        persistFolders()
        tracks.removeAll { it.folder == folder.name }
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
                    tracks.removeAll { it.folder == folder.name }
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
            runOnUiThread {
                if (selectedTrack?.uri == track.uri &&
                    selectedTrack?.cueStartMs == track.cueStartMs &&
                    selectedTrack?.cuePlaylist == track.cuePlaylist &&
                    selectedTrack?.playlistName == track.playlistName &&
                    selectedTrack?.playlistOrder == track.playlistOrder) {
                    currentTag = tag
                    lyrics = parsedLyrics
                    if (!hasPlaybackSession) status = "Ready to resume · ${track.title}"
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

    override fun onDestroy() {
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

internal val ISO_BANDS = listOf("20", "25", "31.5", "40", "50", "63", "80", "100", "125", "160", "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k")
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
    equalizerGains: List<Float>,
    onBandGain: (Int, Float) -> Unit,
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
                            tint = if (shuffleEnabled) Color(0xFF1E90FF) else Color.White,
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
                Text("31-BAND ISO EQUALIZER · ±12 dB", color = Color.White, fontWeight = FontWeight.Bold)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111214)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        ISO_BANDS.forEachIndexed { index, band ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(48.dp)
                            ) {
                                VerticalEqualizerSlider(
                                    value = equalizerGains[index],
                                    onValueChange = { onBandGain(index, it) },
                                    modifier = Modifier.height(250.dp).width(44.dp)
                                )
                                Text(band, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                Text(
                                    String.format(java.util.Locale.ROOT, "%.1f", equalizerGains[index]),
                                    color = Color.White.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

internal fun formatPlaybackTime(valueMs: Long): String {
    val seconds = valueMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(min = 360.dp, max = 600.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                    Text(
                        "Artwork is preserved when the selected tag format supports it.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onSave(tag.copy(
                                title = title, artist = artist, album = album, albumArtist = albumArtist,
                                genre = genre, year = year, track = track, disc = disc,
                                composer = composer, comment = comment, lyrics = lyrics
                            ))
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun VerticalEqualizerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            fun valueAt(y: Float): Float =
                (12f - (y / size.height.coerceAtLeast(1)) * 24f).coerceIn(-12f, 12f)
            detectTapGestures { offset -> onValueChange(valueAt(offset.y)) }
        }.pointerInput(Unit) {
            fun valueAt(y: Float): Float =
                (12f - (y / size.height.coerceAtLeast(1)) * 24f).coerceIn(-12f, 12f)
            detectDragGestures { change, _ ->
                change.consume()
                onValueChange(valueAt(change.position.y))
            }
        }
    ) {
        val centerX = size.width / 2f
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val centerY = (top + bottom) / 2f
        val thumbY = bottom - ((value + 12f) / 24f) * (bottom - top)

        drawLine(
            color = Color(0xFF777777),
            start = Offset(centerX, top),
            end = Offset(centerX, bottom),
            strokeWidth = 3.dp.toPx()
        )
        listOf(top + 24.dp.toPx(), bottom - 24.dp.toPx()).forEach { y ->
            drawLine(
                color = Color(0xFFBDBDBD),
                start = Offset(centerX - 21.dp.toPx(), y),
                end = Offset(centerX - 16.dp.toPx(), y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color(0xFFBDBDBD),
                start = Offset(centerX + 16.dp.toPx(), y),
                end = Offset(centerX + 21.dp.toPx(), y),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(centerX - 15.dp.toPx(), thumbY - 22.dp.toPx()),
            size = Size(30.dp.toPx(), 44.dp.toPx()),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = 2.5.dp.toPx())
        )
        drawLine(
            color = Color.White,
            start = Offset(centerX - 5.dp.toPx(), thumbY),
            end = Offset(centerX + 5.dp.toPx(), thumbY),
            strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
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
