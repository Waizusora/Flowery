package com.waizusora.flowery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.net.Uri
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.util.concurrent.Executors

class PlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "com.waizusora.flowery.PLAY"
        const val ACTION_PAUSE = "com.waizusora.flowery.PAUSE"
        const val ACTION_RESUME = "com.waizusora.flowery.RESUME"
        const val ACTION_NEXT = "com.waizusora.flowery.NEXT"
        const val ACTION_PREVIOUS = "com.waizusora.flowery.PREVIOUS"
        const val ACTION_QUEUE = "com.waizusora.flowery.QUEUE"
        const val ACTION_VOLUME = "com.waizusora.flowery.VOLUME"
        const val ACTION_SHUFFLE = "com.waizusora.flowery.SHUFFLE"
        const val ACTION_REPEAT = "com.waizusora.flowery.REPEAT"
        const val ACTION_SEEK = "com.waizusora.flowery.SEEK"
        const val ACTION_UPDATE_METADATA = "com.waizusora.flowery.UPDATE_METADATA"
        const val ACTION_RESTORE = "com.waizusora.flowery.RESTORE"
        const val EXTRA_URI = "track_uri"
        const val EXTRA_QUEUE = "queue_uris"
        const val EXTRA_TITLES = "queue_titles"
        const val EXTRA_ARTISTS = "queue_artists"
        const val EXTRA_INDEX = "queue_index"
        const val EXTRA_CUE_STARTS = "queue_cue_starts_ms"
        const val EXTRA_CUE_ENDS = "queue_cue_ends_ms"
        const val EXTRA_CUE_PLAYLISTS = "queue_cue_playlists"
        const val EXTRA_PLAYLIST_NAMES = "queue_playlist_names"
        const val EXTRA_PLAYLIST_ORDERS = "queue_playlist_orders"
        const val EXTRA_CURRENT_CUE_PLAYLIST = "current_cue_playlist"
        const val EXTRA_CURRENT_PLAYLIST_NAME = "current_playlist_name"
        const val EXTRA_CURRENT_PLAYLIST_ORDER = "current_playlist_order"
        const val EXTRA_VALUE = "value"
        const val EXTRA_VOLUME_COMMAND_ID = "volume_command_id"
        const val EXTRA_VOLUME_COMMAND_COMPLETE = "volume_command_complete"
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_ARTIST = "media_artist"
        const val EXTRA_CURRENT_TITLE = "current_title"
        const val EXTRA_CURRENT_ARTIST = "current_artist"
        const val EXTRA_CURRENT_ARTWORK = "current_artwork"
        const val EXTRA_POSITION = "position_ms"
        const val EXTRA_DURATION = "duration_ms"
        const val EXTRA_CURRENT_URI = "current_uri"
        const val EXTRA_CURRENT_CUE_START = "current_cue_start_ms"
        const val BROADCAST_STATE = "com.waizusora.flowery.PLAYBACK_STATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_SHUFFLE_ENABLED = "shuffle_enabled"
        const val EXTRA_REPEAT_MODE = "repeat_mode"
        const val EXTRA_VOLUME = "extra_volume"
        const val EXTRA_OUTPUT_SAMPLE_RATE = "output_sample_rate_hz"
        const val EXTRA_OUTPUT_BIT_DEPTH = "output_bit_depth"
        const val EXTRA_OUTPUT_BACKEND = "output_backend"
        const val EXTRA_DECODED_SAMPLE_RATE = "decoded_sample_rate_hz"
        const val EXTRA_DECODED_PCM_FORMAT = "decoded_pcm_format"
        const val EXTRA_OUTPUT_PCM_FORMAT = "output_pcm_format"
        const val EXTRA_OUTPUT_RESAMPLING = "output_resampling_required"
        const val EXTRA_OUTPUT_BIT_DEPTH_CONVERSION = "output_bit_depth_conversion_required"
        const val EXTRA_OUTPUT_SAMPLE_FORMAT_CONVERSION = "output_sample_format_conversion_required"
        const val EXTRA_OUTPUT_BIT_PERFECT_CANDIDATE = "output_bit_perfect_candidate"
        const val EXTRA_USB_PLAYBACK_STATUS = "usb_playback_status"
        const val EXTRA_USB_VOLUME_DETECTED = "usb_volume_detected"
        const val EXTRA_USB_VOLUME_WRITABLE = "usb_volume_writable"
        const val EXTRA_USB_VOLUME_VERIFIED = "usb_volume_verified"
        const val EXTRA_USB_VOLUME_DB = "usb_volume_db"
        const val EXTRA_USB_VOLUME_MIN_DB = "usb_volume_min_db"
        const val EXTRA_USB_VOLUME_MAX_DB = "usb_volume_max_db"
        const val EXTRA_USB_VOLUME_RESOLUTION_DB = "usb_volume_resolution_db"
        private const val CHANNEL_ID = "flowery_playback"
        private const val NOTIFICATION_ID = 41
    }

    private lateinit var player: PlaybackCoordinator
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var hasAudioFocus = false
    private val callInterruptionPolicy = CallInterruptionPolicy()
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private val recoveryPreferences by lazy { getSharedPreferences("playback_recovery", MODE_PRIVATE) }
    @Volatile private var recoverySessionAvailable = false
    @Volatile private var recoveryNeedsPrepare = false
    @Volatile private var enginePositionAvailable = false
    @Volatile private var recoveryPositionMs = 0L
    private var lastRecoveryPersistMs = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val queue = mutableListOf<String>()
    private val queueTitles = mutableListOf<String>()
    private val queueArtists = mutableListOf<String>()
    private val queueCueStarts = mutableListOf<Long>()
    private val queueCueEnds = mutableListOf<Long>()
    private val queueCuePlaylists = mutableListOf<String>()
    private val queuePlaylistNames = mutableListOf<String>()
    private val queuePlaylistOrders = mutableListOf<Int>()
    private val queueState = PlaybackQueueState()
    private var queueIndex: Int
        get() = queueState.currentIndex
        set(value) = queueState.select(value, queue.size)
    private var repeatMode: Int
        get() = queueState.repeatMode
        set(value) = queueState.setRepeatMode(value)
    private var shuffle: Boolean
        get() = queueState.shuffleEnabled
        set(value) = queueState.setShuffle(value)
    private var currentTitle = "Flowery"
    private var currentArtist = "Local music"
    private var currentArtwork: Bitmap? = null
    private var currentArtworkKey = ""
    @Volatile private var playbackState = PlaybackStateCompat.STATE_STOPPED
    @Volatile private var playRequestGeneration = 0L
    private var lastPublishedMetadataDuration = -1L
    @Volatile private var sourceMetadataDurationMs = 0L
    private var lastPublishedMetadataTitle = ""
    private var lastPublishedMetadataArtist = ""
    private var lastPublishedArtworkKey = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefetchSourceResolver by lazy { AudioSourceResolver(this) }
    private val playbackPrefetch = PlaybackPrefetchCoordinator<PreparedPlaybackResources>(
        onPreparationFailure = { error ->
            Log.w("FloweryDiagnostics", "GAPLESS_PREFETCH_PREPARATION_FAILURE", error)
        }
    )
    private var lastOutputDeviceSnapshot: List<String> = emptyList()
    private var lastAudioDiagnosticMs = 0L
    @Volatile private var outputRouteRestartPending = false
    private var lastOutputRouteTarget = AndroidOutputTarget.SPEAKER
    private val outputRouteCheck: Runnable = Runnable {
        val current = outputDeviceSnapshot()
        if (outputRouteRestartPending) {
            if (current != lastOutputDeviceSnapshot) {
                mainHandler.postDelayed(outputRouteCheck, 350L)
            }
            return@Runnable
        }
        if (current != lastOutputDeviceSnapshot) {
            lastOutputDeviceSnapshot = current
            val routeResolver = AndroidOutputRouteResolver(this)
            val nextTarget = routeResolver.detectCurrent()
            val selectedRoute = routeResolver.resolve(nextTarget)
            val previousTarget = lastOutputRouteTarget
            lastOutputRouteTarget = nextTarget
            val wasPlaying = playbackState == PlaybackStateCompat.STATE_PLAYING ||
                playbackState == PlaybackStateCompat.STATE_CONNECTING
            val wasPaused = playbackState == PlaybackStateCompat.STATE_PAUSED
            val uriText = queue.getOrNull(queueIndex)
            if (!outputRouteRestartPending && !recoveryNeedsPrepare &&
                (wasPlaying || wasPaused) && !uriText.isNullOrBlank()) {
                playbackPrefetch.invalidate()
                outputRouteRestartPending = true
                val previousPositionMs = player.positionMs()
                val segmentStartMs = queueCueStarts.getOrNull(queueIndex) ?: 0L
                val segmentEndMs = queueCueEnds.getOrNull(queueIndex)?.takeIf { it > 0L }
                // USB attach always starts from the track's beginning. AAudio-to-AAudio
                // changes (and USB removal back to AAudio) preserve the elapsed position.
                val transition = OutputRouteTransitionPolicy.forTarget(nextTarget, previousPositionMs)
                val restartPositionMs = transition.positionMs
                recoveryPositionMs = restartPositionMs
                persistPlaybackSnapshot()
                Log.i(
                    "FloweryDiagnostics",
                    "OUTPUT_ROUTE_REOPEN from=$previousTarget to=$nextTarget " +
                        "deviceId=${selectedRoute.deviceId} deviceType=${selectedRoute.deviceType ?: -1} " +
                        "deviceName=${selectedRoute.deviceName ?: "unknown"} wasPlaying=$wasPlaying " +
                        "previousPositionMs=$previousPositionMs restartPositionMs=$restartPositionMs " +
                        "manualResumeRequired=true segmentStartMs=$segmentStartMs segmentEndMs=${segmentEndMs ?: -1L}"
                )
                playbackState = PlaybackStateCompat.STATE_PAUSED
                publishSessionState(restartPositionMs)
                val outputLabel = selectedRoute.deviceName?.takeIf { it.isNotBlank() }
                    ?: nextTarget.displayName
                publishState(if (nextTarget == AndroidOutputTarget.USB)
                    "USB DAC detected: $outputLabel · paused at track start · press Play"
                else "Output changed to $outputLabel · paused · press Play")
                io.execute {
                    runCatching {
                        player.reopenOutputRoute(
                            Uri.parse(uriText), segmentStartMs, segmentEndMs,
                            restartPositionMs, startPaused = transition.startPaused
                        )
                    }.onFailure { error ->
                        outputRouteRestartPending = false
                        Log.e("FloweryDiagnostics", "OUTPUT_ROUTE_REOPEN_FAILURE", error)
                        publishState("Playback error: unable to switch audio output: ${error.message}")
                    }
                }
            } else if (uriText.isNullOrBlank()) {
                publishState("Audio output changed")
            }
        }
    }
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            AndroidOutputDeviceOrder.onAdded(addedDevices.map {
                OutputDeviceCandidate(it.id, it.type, it.productName?.toString())
            })
            Log.i("FloweryDiagnostics", "OUTPUT_DEVICE_ADDED " +
                addedDevices.joinToString { "${it.id}:${it.type}:${it.productName}" })
            scheduleOutputRouteCheck()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            AndroidOutputDeviceOrder.onRemoved(removedDevices.map { it.id })
            Log.i("FloweryDiagnostics", "OUTPUT_DEVICE_REMOVED " +
                removedDevices.joinToString { "${it.id}:${it.type}:${it.productName}" })
            scheduleOutputRouteCheck()
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }
    private var lastStandaloneVolumeProbeMs = 0L

    private val progressUpdates = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                refreshCallInterruptionState()
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastRecoveryPersistMs >= 2_000L) {
                    lastRecoveryPersistMs = nowMs
                    persistPlaybackSnapshot()
                }
                // Refresh Feature Unit detection a few times per minute so Settings
                // reflects DAC attach/detach without requiring USB Direct playback.
                if (nowMs - lastStandaloneVolumeProbeMs >= 5_000L) {
                    lastStandaloneVolumeProbeMs = nowMs
                    runCatching { player.refreshStandaloneUsbVolumeDetection() }
                }
                if (playbackState == PlaybackStateCompat.STATE_PLAYING &&
                    nowMs - lastAudioDiagnosticMs >= 5_000L) {
                    lastAudioDiagnosticMs = nowMs
                    Log.i("FloweryDiagnostics", "AUDIO_PIPELINE ${player.audioPipelineDiagnostics()}")
                }
                val position = if (recoveryNeedsPrepare) recoveryPositionMs else player.positionMs()
                val duration = player.durationMs().takeIf { it > 0L } ?: sourceMetadataDurationMs
                if (::session.isInitialized) {
                    if (duration > 0L && duration != lastPublishedMetadataDuration) {
                        publishTrackMetadata(duration)
                    }
                    publishSessionState(position)
                }
                sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName)
                    .putExtra(EXTRA_POSITION, position)
                    .putExtra(EXTRA_DURATION, duration)
                    .putExtra(EXTRA_OUTPUT_SAMPLE_RATE, player.outputSampleRateHz())
                    .putExtra(EXTRA_OUTPUT_BIT_DEPTH, player.outputBitDepth())
                    .putExtra(EXTRA_OUTPUT_BACKEND, player.outputBackendLabel())
            .putExtra(EXTRA_DECODED_SAMPLE_RATE, player.decodedSampleRateHz())
            .putExtra(EXTRA_DECODED_PCM_FORMAT, player.decodedPcmFormatLabel())
            .putExtra(EXTRA_OUTPUT_PCM_FORMAT, player.outputPcmFormatLabel())
            .putExtra(EXTRA_OUTPUT_RESAMPLING, player.outputResamplingRequired())
            .putExtra(EXTRA_OUTPUT_BIT_DEPTH_CONVERSION, player.outputBitDepthConversionRequired())
            .putExtra(EXTRA_OUTPUT_SAMPLE_FORMAT_CONVERSION, player.outputSampleFormatConversionRequired())
            .putExtra(EXTRA_OUTPUT_BIT_PERFECT_CANDIDATE, player.outputBitPerfectCandidate())
                    .putExtra(EXTRA_USB_PLAYBACK_STATUS, player.usbPlaybackStatusJson())
                    .putExtra(EXTRA_USB_VOLUME_DETECTED, player.usbVolumeControlDetected())
                    .putExtra(EXTRA_USB_VOLUME_WRITABLE, player.usbVolumeControlWritable())
                    .putExtra(EXTRA_USB_VOLUME_VERIFIED, player.usbVolumeWriteVerified())
                    .putExtra(EXTRA_USB_VOLUME_DB, player.usbVolumeCurrentDb())
                    .putExtra(EXTRA_USB_VOLUME_MIN_DB, player.usbVolumeMinimumDb())
                    .putExtra(EXTRA_USB_VOLUME_MAX_DB, player.usbVolumeMaximumDb())
                    .putExtra(EXTRA_USB_VOLUME_RESOLUTION_DB, player.usbVolumeResolutionDb())
                    .putExtra(EXTRA_IS_PLAYING, playbackState == PlaybackStateCompat.STATE_PLAYING)
                    .putExtra(EXTRA_CURRENT_URI, queue.getOrNull(queueIndex).orEmpty())
                    .putExtra(EXTRA_CURRENT_CUE_START, queueCueStarts.getOrNull(queueIndex) ?: 0L)
                    .putExtra(EXTRA_CURRENT_CUE_PLAYLIST, queueCuePlaylists.getOrNull(queueIndex).orEmpty())
                    .putExtra(EXTRA_CURRENT_PLAYLIST_NAME, queuePlaylistNames.getOrNull(queueIndex).orEmpty())
                    .putExtra(EXTRA_CURRENT_PLAYLIST_ORDER, queuePlaylistOrders.getOrNull(queueIndex) ?: -1)
                    .putExtra(EXTRA_CURRENT_TITLE, currentTitle)
                    .putExtra(EXTRA_CURRENT_ARTIST, currentArtist)
                    .putExtra(EXTRA_SHUFFLE_ENABLED, shuffle)
                    .putExtra(EXTRA_REPEAT_MODE, repeatMode)
                    .putExtra(EXTRA_VOLUME, player.currentVolumeFraction()))
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("FloweryDiagnostics", "SERVICE_CREATE pid=${android.os.Process.myPid()}")
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        playbackWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:Playback")
            .apply { setReferenceCounted(false) }
        player = PlaybackCoordinator(this) { message ->
            if (message.startsWith("Playing") || message.startsWith("Paused")) {
                enginePositionAvailable = true
            }
            if (message.startsWith("Playing") || message.startsWith("Paused") ||
                message.startsWith("Playback error")) {
                val wasSwitchingOutput = outputRouteRestartPending
                outputRouteRestartPending = false
                if (wasSwitchingOutput) mainHandler.postDelayed(outputRouteCheck, 100L)
            }
            publishState(message)
            if (message == "Playback finished") {
                // Completion is reported from the playback worker. Defer queue transitions
                // so stopping the completed session never interrupts its own cleanup thread.
                mainHandler.post { handleCompletion() }
            }
        }
        createNotificationChannel()
        session = MediaSessionCompat(this, "FloweryPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(position: Long) {
                    recoveryPositionMs = position.coerceAtLeast(0L)
                    player.seekTo(recoveryPositionMs)
                    persistPlaybackSnapshot()
                }
            })
            isActive = true
        }
        restorePlaybackSnapshot()
        publishSessionState()
        mainHandler.post(progressUpdates)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        lastOutputDeviceSnapshot = outputDeviceSnapshot()
        lastOutputRouteTarget = AndroidOutputRouteResolver(this).detectCurrent()
        audioManager.registerAudioDeviceCallback(outputDeviceCallback, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && recoverySessionAvailable) {
            // START_STICKY process recreation is always a paused recovery, never auto-play.
            startForeground(NOTIFICATION_ID, buildNotification())
            publishState("Playback session restored · press Play to continue")
            Log.i("FloweryDiagnostics", "RECOVERY_SERVICE_RESTART queueSize=${queue.size} index=$queueIndex positionMs=$recoveryPositionMs autoPlay=false")
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_RESTORE -> {
                if (recoverySessionAvailable && queue.isNotEmpty()) {
                    Log.i(
                        "FloweryDiagnostics",
                        "RECOVERY_ACTIVITY_REQUEST_HANDLED queueSize=${queue.size} index=$queueIndex " +
                            "positionMs=$recoveryPositionMs requiresPrepare=$recoveryNeedsPrepare"
                    )
                    if (playbackState != PlaybackStateCompat.STATE_PLAYING &&
                        playbackState != PlaybackStateCompat.STATE_CONNECTING) {
                        startForeground(NOTIFICATION_ID, buildNotification())
                        publishState("Playback session restored · press Play to continue")
                    }
                } else {
                    Log.i("FloweryDiagnostics", "RECOVERY_ACTIVITY_REQUEST_NO_SESSION")
                    stopSelf(startId)
                }
            }
            ACTION_PLAY -> {
                val uris = intent.getStringArrayListExtra(EXTRA_QUEUE)
                if (!uris.isNullOrEmpty()) {
                    queue.clear()
                    queue.addAll(uris)
                    queueTitles.clear()
                    queueTitles.addAll(intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty())
                    queueArtists.clear()
                    queueArtists.addAll(intent.getStringArrayListExtra(EXTRA_ARTISTS).orEmpty())
                    queueCueStarts.clear()
                    queueCueStarts.addAll(intent.getLongArrayExtra(EXTRA_CUE_STARTS)?.toList().orEmpty())
                    queueCueEnds.clear()
                    queueCueEnds.addAll(intent.getLongArrayExtra(EXTRA_CUE_ENDS)?.toList().orEmpty())
                    queueCuePlaylists.clear()
                    queueCuePlaylists.addAll(intent.getStringArrayListExtra(EXTRA_CUE_PLAYLISTS).orEmpty())
                    queuePlaylistNames.clear()
                    queuePlaylistNames.addAll(intent.getStringArrayListExtra(EXTRA_PLAYLIST_NAMES).orEmpty())
                    queuePlaylistOrders.clear()
                    queuePlaylistOrders.addAll(intent.getIntegerArrayListExtra(EXTRA_PLAYLIST_ORDERS).orEmpty())
                    queueIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(queue.indices)
                    recoveryPositionMs = 0L
                    recoveryNeedsPrepare = false
                    recoverySessionAvailable = true
                } else {
                    val uri = intent.getStringExtra(EXTRA_URI)
                    if (uri != null && uri !in queue) queue.add(uri)
                    queueIndex = queue.indexOf(uri).coerceAtLeast(0)
                }
                recoverySessionAvailable = queue.isNotEmpty()
                recoveryNeedsPrepare = false
                recoveryPositionMs = 0L
                Log.i("FloweryDiagnostics", "QUEUE_LOAD source=ACTION_PLAY size=${queue.size} index=$queueIndex repeat=$repeatMode shuffle=$shuffle")
                startForeground(NOTIFICATION_ID, buildNotification())
                persistPlaybackSnapshot()
                playCurrent()
            }
            ACTION_QUEUE -> {
                val uris = intent.getStringArrayListExtra(EXTRA_QUEUE).orEmpty()
                queue.clear()
                queue.addAll(uris)
                queueCueStarts.clear()
                queueCueStarts.addAll(intent.getLongArrayExtra(EXTRA_CUE_STARTS)?.toList().orEmpty())
                queueCueEnds.clear()
                queueCueEnds.addAll(intent.getLongArrayExtra(EXTRA_CUE_ENDS)?.toList().orEmpty())
                queueCuePlaylists.clear()
                queueCuePlaylists.addAll(intent.getStringArrayListExtra(EXTRA_CUE_PLAYLISTS).orEmpty())
                queuePlaylistNames.clear()
                queuePlaylistNames.addAll(intent.getStringArrayListExtra(EXTRA_PLAYLIST_NAMES).orEmpty())
                queuePlaylistOrders.clear()
                queuePlaylistOrders.addAll(intent.getIntegerArrayListExtra(EXTRA_PLAYLIST_ORDERS).orEmpty())
                queueIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                recoverySessionAvailable = queue.isNotEmpty()
                recoveryNeedsPrepare = false
                recoveryPositionMs = 0L
                Log.i("FloweryDiagnostics", "QUEUE_LOAD source=ACTION_QUEUE size=${queue.size} index=$queueIndex repeat=$repeatMode shuffle=$shuffle")
                if (queue.isNotEmpty()) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    persistPlaybackSnapshot()
                    playCurrent()
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_VOLUME -> {
                val requestedVolume = intent.getFloatExtra(EXTRA_VALUE, 1f).coerceIn(0f, 1f)
                val commandId = intent.getLongExtra(EXTRA_VOLUME_COMMAND_ID, -1L)
                io.execute {
                    if (::player.isInitialized) {
                        Log.i("FloweryDiagnostics", "USB_VOLUME_COMMAND_START id=$commandId requestedFraction=$requestedVolume")
                        player.refreshStandaloneUsbVolumeDetection()
                        player.setVolume(requestedVolume)
                        val actualVolume = player.currentVolumeFraction().coerceIn(0f, 1f)
                        val verified = player.usbVolumeWriteVerified()
                        Log.i(
                            "FloweryDiagnostics",
                            "USB_VOLUME_COMMAND_COMPLETE id=$commandId requestedFraction=$requestedVolume actualFraction=$actualVolume verified=$verified"
                        )
                        sendBroadcast(
                            Intent(BROADCAST_STATE).setPackage(packageName)
                                .putExtra(EXTRA_VOLUME, actualVolume)
                                .putExtra(EXTRA_VOLUME_COMMAND_ID, commandId)
                                .putExtra(EXTRA_VOLUME_COMMAND_COMPLETE, true)
                                .putExtra(EXTRA_USB_VOLUME_DETECTED, player.usbVolumeControlDetected())
                                .putExtra(EXTRA_USB_VOLUME_WRITABLE, player.usbVolumeControlWritable())
                                .putExtra(EXTRA_USB_VOLUME_VERIFIED, verified)
                                .putExtra(EXTRA_USB_VOLUME_DB, player.usbVolumeCurrentDb())
                                .putExtra(EXTRA_USB_VOLUME_MIN_DB, player.usbVolumeMinimumDb())
                                .putExtra(EXTRA_USB_VOLUME_MAX_DB, player.usbVolumeMaximumDb())
                                .putExtra(EXTRA_USB_VOLUME_RESOLUTION_DB, player.usbVolumeResolutionDb())
                        )
                    }
                }
            }
            ACTION_SHUFFLE -> { shuffle = !shuffle; publishState(if (shuffle) "Shuffle enabled" else "Shuffle disabled") }
            ACTION_REPEAT -> { repeatMode = queueState.cycleRepeatMode(); publishState("Repeat mode: ${listOf("Off", "All", "One")[repeatMode]}") }
            ACTION_SEEK -> {
                recoveryPositionMs = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
                player.seekTo(recoveryPositionMs)
                persistPlaybackSnapshot()
            }

            ACTION_UPDATE_METADATA -> {
                currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: currentTitle
                currentArtist = intent.getStringExtra(EXTRA_ARTIST)?.takeIf { it.isNotBlank() } ?: currentArtist
                session.setMetadata(MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.durationMs())
                    .build())
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                publishState("Metadata updated")
            }
        }
        persistPlaybackSnapshot()
        return if (recoverySessionAvailable && queue.isNotEmpty()) START_STICKY else START_NOT_STICKY
    }

    private fun requestFocus(): Boolean {
        if (hasAudioFocus) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            abandonLegacyAudioFocus()
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Ordinary app audio must not interrupt Flowery. Call interruptions
                    // are detected independently through AudioManager's call modes.
                    hasAudioFocus = false
                    mainHandler.post { scheduleCallModeCheck() }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Focus recovery is never user intent. In particular, a completed
                    // phone/VoIP call must leave playback paused until an explicit Play.
                    hasAudioFocus = true
                    mainHandler.post { refreshCallInterruptionState() }
                }
            }
        }
        focusListener = listener
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // Continue playback even when another app owns audio focus; Flowery intentionally
        // mixes with ordinary app audio and only pauses for detected call audio modes.
        return true
    }

    private fun isCallAudioMode(): Boolean {
        return audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager.mode == AudioManager.MODE_RINGTONE
    }

    private fun scheduleCallModeCheck() {
        mainHandler.postDelayed({ refreshCallInterruptionState() }, 250L)
    }

    /** Polls call mode while the service is alive and never resumes on call end. */
    private fun refreshCallInterruptionState() {
        when (callInterruptionPolicy.update(isCallAudioMode())) {
            CallInterruptionPolicy.Transition.CALL_STARTED -> {
                Log.w("FloweryDiagnostics", "CALL_INTERRUPTION_STARTED state=$playbackState")
                persistPlaybackSnapshot()
                if (playbackState == PlaybackStateCompat.STATE_PLAYING ||
                    playbackState == PlaybackStateCompat.STATE_CONNECTING) {
                    // Serialize pause behind any in-flight engine start on the I/O executor.
                    io.execute {
                        val paused = runCatching { player.pause() }.getOrDefault(false)
                        mainHandler.post {
                            if (callInterruptionPolicy.isInterrupted) {
                                playbackState = PlaybackStateCompat.STATE_PAUSED
                                publishSessionState()
                                publishState(if (paused)
                                    "Paused · phone or app call · press Play after call"
                                else "Call active · playback start blocked")
                            }
                        }
                    }
                } else {
                    publishState("Call active · playback remains paused")
                }
            }
            CallInterruptionPolicy.Transition.CALL_ENDED -> {
                Log.i("FloweryDiagnostics", "CALL_INTERRUPTION_ENDED manual_play_required=true")
                persistPlaybackSnapshot()
                publishState("Call ended · press Play to resume")
            }
            CallInterruptionPolicy.Transition.NONE -> Unit
        }
    }

    private fun isPlaybackBlockedByCall(): Boolean {
        refreshCallInterruptionState()
        return callInterruptionPolicy.blocksPlayback(isCallAudioMode())
    }

    /**
     * Prepare the next decoder/session in the background without opening an
     * output stream. The resource remains owned by the prefetch slot until a
     * future persistent-output handoff consumes it.
     */
    private fun scheduleNextTrackPrefetch(generation: Long) {
        val nextIndex = queueState.peekNextIndex(queue.size)
        val nextUriText = queue.getOrNull(nextIndex)?.takeIf { it.isNotBlank() } ?: return
        val nextStartMs = queueCueStarts.getOrNull(nextIndex) ?: 0L
        val nextEndMs = queueCueEnds.getOrNull(nextIndex)?.takeIf { it > 0L }
        val key = "$nextUriText|$nextStartMs|${nextEndMs ?: -1L}"
        val targetUri = Uri.parse(nextUriText)

        val accepted = playbackPrefetch.prefetch(generation, key) {
            PreparedPlaybackResources.prepare(
                applicationContext,
                targetUri,
                prefetchSourceResolver::resolveExtension,
            )
        }
        if (accepted) {
            Log.i(
                "FloweryDiagnostics",
                "GAPLESS_PREFETCH_SCHEDULED generation=$generation nextIndex=$nextIndex " +
                    "segmentStartMs=$nextStartMs segmentEndMs=${nextEndMs ?: -1L}"
            )
        }
    }
    private fun playCurrent(initialPositionMs: Long = 0L) {
        if (isPlaybackBlockedByCall()) {
            publishState("Playback paused during call · press Play after call")
            return
        }
        val selectedIndex = queueIndex
        val uriText = queue.getOrNull(selectedIndex) ?: run {
            Log.e("FloweryDiagnostics", "PLAY_CURRENT_ABORT queueSize=${queue.size} index=$selectedIndex; selected item is missing")
            return
        }
        val requestGeneration = ++playRequestGeneration
        playbackPrefetch.beginGeneration(requestGeneration)
        scheduleNextTrackPrefetch(requestGeneration)
        val uri = Uri.parse(uriText)
        val segmentStartMs = queueCueStarts.getOrNull(selectedIndex) ?: 0L
        val segmentEndMs = queueCueEnds.getOrNull(selectedIndex)?.takeIf { it > 0L }
        recoverySessionAvailable = true
        recoveryNeedsPrepare = false
        enginePositionAvailable = false
        recoveryPositionMs = initialPositionMs.coerceAtLeast(0L)
        playbackState = PlaybackStateCompat.STATE_CONNECTING
        persistPlaybackSnapshot()
        val virtualEntry = !queueCuePlaylists.getOrNull(selectedIndex).isNullOrBlank() ||
            !queuePlaylistNames.getOrNull(selectedIndex).isNullOrBlank()
        val fallbackTitle = queueTitles.getOrNull(selectedIndex)
            ?.takeIf { it.isNotBlank() }
            ?: Uri.decode(uri.lastPathSegment.orEmpty()).substringBeforeLast('.').ifBlank { "Unknown track" }
        val fallbackArtist = queueArtists.getOrNull(selectedIndex)
            ?.takeIf { it.isNotBlank() && it != "Local music" }
            ?: "Unknown artist"

        Log.i("FloweryDiagnostics", "PLAY_CURRENT index=$selectedIndex queueSize=${queue.size} title=$fallbackTitle repeat=$repeatMode shuffle=$shuffle")
        Log.i("FloweryDiagnostics", "PLAY_PHASE phase=focus_request_start index=$selectedIndex")
        val focusAccepted = requestFocus()
        Log.i("FloweryDiagnostics", "PLAY_PHASE phase=focus_request_done accepted=$focusAccepted index=$selectedIndex")
        if (!focusAccepted) {
            publishState("Playback could not start because audio focus was denied")
            return
        }

        currentTitle = fallbackTitle
        currentArtist = fallbackArtist
        currentArtwork = null
        currentArtworkKey = ""
        sourceMetadataDurationMs = 0L
        lastPublishedMetadataDuration = -1L
        publishTrackMetadata(0L)
        startForeground(NOTIFICATION_ID, buildNotification())

        // SAF metadata extraction can block on document providers. Keep it off the
        // service callback thread so Android SystemUI media commands stay responsive.
        io.execute {
            var resolvedTitle = fallbackTitle
            var resolvedArtist = fallbackArtist
            var resolvedDurationMs = 0L
            var resolvedArtwork: AlbumArtworkDecoder.DecodedArtwork? = null
            Log.i("FloweryDiagnostics", "PLAY_PHASE phase=metadata_retrieval_start index=$selectedIndex")
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    val embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val embeddedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    resolvedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    resolvedArtwork = retriever.embeddedPicture
                        ?.let(AlbumArtworkDecoder::decode)
                    if (!virtualEntry) resolvedTitle = embeddedTitle?.takeIf { it.isNotBlank() } ?: resolvedTitle
                    if (resolvedArtist == "Unknown artist") {
                        resolvedArtist = embeddedArtist?.takeIf { it.isNotBlank() } ?: resolvedArtist
                    }
                } finally {
                    retriever.release()
                }
            }.onFailure { error ->
                Log.w("FloweryDiagnostics", "PLAY_METADATA_READ_FAILURE index=$selectedIndex", error)
            }

            mainHandler.post {
                if (requestGeneration != playRequestGeneration) return@post
                currentTitle = resolvedTitle
                currentArtist = resolvedArtist
                currentArtwork = resolvedArtwork?.bitmap
                currentArtworkKey = resolvedArtwork?.cacheKey.orEmpty()
                sourceMetadataDurationMs = resolvedDurationMs
                publishTrackMetadata(sourceMetadataDurationMs)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())

                io.execute {
                    if (requestGeneration != playRequestGeneration) return@execute
                    try {
                        if (callInterruptionPolicy.isInterrupted) {
                            Log.i("FloweryDiagnostics", "PLAY_PHASE phase=engine_play_blocked reason=call_interruption index=$selectedIndex")
                            return@execute
                        }
                        Log.i("FloweryDiagnostics", "PLAY_PHASE phase=engine_play_start index=$selectedIndex")
                        player.play(uri, segmentStartMs, segmentEndMs, initialPositionMs = initialPositionMs)
                        if (requestGeneration != playRequestGeneration) return@execute
                        Log.i("FloweryDiagnostics", "PLAY_PHASE phase=engine_play_return index=$selectedIndex; waiting_for_engine_started_callback=true")
                        playbackState = PlaybackStateCompat.STATE_CONNECTING
                        publishSessionState()
                    } catch (error: Throwable) {
                        if (requestGeneration == playRequestGeneration) {
                            Log.e("FloweryDiagnostics", "PLAYBACK_START_FAILURE index=$selectedIndex queueSize=${queue.size} title=$currentTitle", error)
                            publishState("Unable to load file: ${error.message}")
                        }
                    }
                }
            }
        }
    }

    private fun publishTrackMetadata(durationMs: Long) {
        if (!::session.isInitialized) return
        if (durationMs == lastPublishedMetadataDuration &&
            currentTitle == lastPublishedMetadataTitle &&
            currentArtist == lastPublishedMetadataArtist &&
            currentArtworkKey == lastPublishedArtworkKey
        ) return

        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
            .apply {
                currentArtwork?.let { bitmap ->
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                }
            }
            .build())
        lastPublishedMetadataDuration = durationMs
        lastPublishedMetadataTitle = currentTitle
        lastPublishedMetadataArtist = currentArtist
        lastPublishedArtworkKey = currentArtworkKey
    }

    private fun outputDeviceSnapshot(): List<String> {
        val monitoredTypes = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        ) + if (Build.VERSION.SDK_INT >= 31) setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        ) else emptySet()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in monitoredTypes }
            .map { "${it.type}:${it.id}:${it.productName}" }
            .sorted()
    }

    private fun scheduleOutputRouteCheck() {
        mainHandler.removeCallbacks(outputRouteCheck)
        mainHandler.postDelayed(outputRouteCheck, 350L)
    }

    private fun pausePlayback() {
        recoveryPositionMs = runCatching { player.positionMs() }.getOrDefault(recoveryPositionMs).coerceAtLeast(0L)
        if (!player.pause()) {
            publishState("Pause request was rejected")
            return
        }
        playbackState = PlaybackStateCompat.STATE_PAUSED
        publishSessionState()
        publishState("Paused")
        persistPlaybackSnapshot()
    }

    private fun resumePlayback() {
        if (queue.isEmpty()) return
        if (isPlaybackBlockedByCall()) {
            publishState("Call active · playback paused; press Play after call")
            return
        }
        if (recoveryNeedsPrepare) {
            val position = recoveryPositionMs
            Log.i("FloweryDiagnostics", "RECOVERY_MANUAL_PLAY index=$queueIndex positionMs=$position callInterrupted=${callInterruptionPolicy.isInterrupted}")
            playCurrent(initialPositionMs = position)
        } else if (playbackState == PlaybackStateCompat.STATE_STOPPED) playCurrent()
        else {
            if (requestFocus() && player.resume()) {
                playbackState = PlaybackStateCompat.STATE_PLAYING
                recoveryNeedsPrepare = false
                publishSessionState()
                persistPlaybackSnapshot()
            } else {
                publishState("Resume request was rejected")
            }
        }
    }

    private fun stopPlayback() {
        playRequestGeneration++
        playbackPrefetch.invalidate()
        Log.w("FloweryDiagnostics", "STOP_PLAYBACK index=$queueIndex queueSize=${queue.size} repeat=$repeatMode shuffle=$shuffle")
        player.stop()
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else abandonLegacyAudioFocus()
        focusRequest = null
        focusListener = null
        playbackState = PlaybackStateCompat.STATE_STOPPED
        enginePositionAvailable = false
        recoverySessionAvailable = false
        recoveryNeedsPrepare = false
        recoveryPositionMs = 0L
        PlaybackRecoverySnapshot.clear(recoveryPreferences)
        publishSessionState()
        publishState("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Completes the current queue without terminating the playback service.
     * The media session and player UI remain available for replay or selection
     * of another track; explicit user stop continues to use stopPlayback().
     */
    private fun finishQueuePlayback() {
        playRequestGeneration++
        Log.i("FloweryDiagnostics", "QUEUE_FINISHED index=$queueIndex queueSize=${queue.size}; service_retained=true")
        player.stop()
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else abandonLegacyAudioFocus()
        focusRequest = null
        focusListener = null
        playbackState = PlaybackStateCompat.STATE_STOPPED
        enginePositionAvailable = false
        recoverySessionAvailable = false
        recoveryNeedsPrepare = false
        PlaybackRecoverySnapshot.clear(recoveryPreferences)
        publishState("Playback finished · end of queue")
    }

    @Suppress("DEPRECATION")
    private fun abandonLegacyAudioFocus() {
        focusListener?.let { audioManager.abandonAudioFocus(it) }
    }

    private fun skipNext() {
        val nextIndex = queueState.transition(queue.size, PlaybackQueueState.Action.NEXT)
        if (nextIndex < 0) return
        queueIndex = nextIndex
        player.stop()
        playCurrent()
    }

    private fun skipPrevious() {
        val previousIndex = queueState.transition(queue.size, PlaybackQueueState.Action.PREVIOUS)
        if (previousIndex < 0) return
        queueIndex = previousIndex
        player.stop()
        playCurrent()
    }

    private fun handleCompletion() {
        val completedIndex = queueIndex
        val nextIndex = queueState.transition(queue.size, PlaybackQueueState.Action.COMPLETION)
        Log.i("FloweryDiagnostics", "TRACK_COMPLETION completedIndex=$completedIndex queueSize=${queue.size} nextIndex=$nextIndex repeat=$repeatMode shuffle=$shuffle")
        when (nextIndex) {
            -2 -> {
                player.stop()
                playCurrent()
            }
            -1 -> finishQueuePlayback()
            else -> {
                queueIndex = nextIndex
                player.stop()
                playCurrent()
            }
        }
    }

    private fun restorePlaybackSnapshot() {
        val snapshot = PlaybackRecoverySnapshot.load(recoveryPreferences)
        val plan = PlaybackRecoveryPolicy.from(snapshot)
        val restored = snapshot ?: return
        if (!plan.sessionAvailable) return
        queue.clear(); queue.addAll(restored.queue)
        queueTitles.clear(); queueTitles.addAll(restored.titles)
        queueArtists.clear(); queueArtists.addAll(restored.artists)
        queueCueStarts.clear(); queueCueStarts.addAll(restored.cueStartsMs)
        queueCueEnds.clear(); queueCueEnds.addAll(restored.cueEndsMs)
        queueCuePlaylists.clear(); queueCuePlaylists.addAll(restored.cuePlaylists)
        queuePlaylistNames.clear(); queuePlaylistNames.addAll(restored.playlistNames)
        queuePlaylistOrders.clear(); queuePlaylistOrders.addAll(restored.playlistOrders)
        queueIndex = restored.queueIndex
        repeatMode = restored.repeatMode
        shuffle = restored.shuffleEnabled
        currentTitle = restored.currentTitle
        currentArtist = restored.currentArtist
        recoveryPositionMs = plan.positionMs
        enginePositionAvailable = false
        recoverySessionAvailable = plan.sessionAvailable
        recoveryNeedsPrepare = plan.requiresPrepareOnPlay
        callInterruptionPolicy.restoreInterrupted(restored.callInterrupted)
        // A process restart never resumes audio automatically, even if it was playing before death.
        playbackState = if (plan.sessionAvailable && !plan.autoPlay) PlaybackStateCompat.STATE_PAUSED
            else PlaybackStateCompat.STATE_STOPPED
        Log.i("FloweryDiagnostics", "RECOVERY_SNAPSHOT_RESTORED queueSize=${queue.size} index=$queueIndex positionMs=$recoveryPositionMs wasPlaying=${restored.wasPlaying} callInterrupted=${restored.callInterrupted} autoPlay=${plan.autoPlay}")
        publishTrackMetadata(0L)
    }

    private fun persistPlaybackSnapshot() {
        if (!recoverySessionAvailable || queue.isEmpty()) return
        if (enginePositionAvailable && !recoveryNeedsPrepare && !outputRouteRestartPending &&
            (playbackState == PlaybackStateCompat.STATE_PLAYING || playbackState == PlaybackStateCompat.STATE_PAUSED)) {
            val observedPosition = runCatching { player.positionMs() }.getOrDefault(recoveryPositionMs)
            if (observedPosition >= 0L) recoveryPositionMs = observedPosition
        }
        val snapshot = PlaybackRecoverySnapshot(
            queue = queue.toList(),
            titles = queueTitles.toList(),
            artists = queueArtists.toList(),
            cueStartsMs = queueCueStarts.toList(),
            cueEndsMs = queueCueEnds.toList(),
            cuePlaylists = queueCuePlaylists.toList(),
            playlistNames = queuePlaylistNames.toList(),
            playlistOrders = queuePlaylistOrders.toList(),
            queueIndex = queueIndex,
            repeatMode = repeatMode,
            shuffleEnabled = shuffle,
            positionMs = recoveryPositionMs,
            wasPlaying = playbackState == PlaybackStateCompat.STATE_PLAYING || playbackState == PlaybackStateCompat.STATE_CONNECTING,
            callInterrupted = callInterruptionPolicy.isInterrupted,
            currentTitle = currentTitle,
            currentArtist = currentArtist,
        )
        PlaybackRecoverySnapshot.save(recoveryPreferences, snapshot)
    }

    private fun syncPlaybackWakeLock() {
        val lock = playbackWakeLock ?: return
        val shouldHold = playbackState == PlaybackStateCompat.STATE_PLAYING
        runCatching {
            if (shouldHold && !lock.isHeld) {
                lock.acquire()
                Log.d("FloweryDiagnostics", "PLAYBACK_WAKE_LOCK acquired")
            } else if (!shouldHold && lock.isHeld) {
                lock.release()
                Log.d("FloweryDiagnostics", "PLAYBACK_WAKE_LOCK released state=$playbackState")
            }
        }.onFailure { Log.w("FloweryDiagnostics", "PLAYBACK_WAKE_LOCK update failed", it) }
    }

    private fun publishState(message: String) {
        val effectiveMessage = if (message.startsWith("Playing") && callInterruptionPolicy.isInterrupted) {
            io.execute { runCatching { player.pause() } }
            "Paused · phone or app call · press Play after call"
        } else message
        playbackState = when {
            effectiveMessage.startsWith("Playing") -> PlaybackStateCompat.STATE_PLAYING
            effectiveMessage.startsWith("Paused") -> PlaybackStateCompat.STATE_PAUSED
            effectiveMessage == "Stopped" || effectiveMessage.startsWith("Playback finished") ||
                effectiveMessage.startsWith("Playback error:") -> PlaybackStateCompat.STATE_STOPPED
            else -> playbackState
        }
        syncPlaybackWakeLock()
        if (effectiveMessage.startsWith("Playing") || effectiveMessage.startsWith("Paused")) {
            persistPlaybackSnapshot()
        }
        val intent = Intent(BROADCAST_STATE).setPackage(packageName).putExtra(EXTRA_STATUS, effectiveMessage)
            .putExtra(EXTRA_POSITION, if (recoveryNeedsPrepare) recoveryPositionMs else player.positionMs())
            .putExtra(EXTRA_DURATION, player.durationMs().takeIf { it > 0L } ?: sourceMetadataDurationMs)
            .putExtra(EXTRA_OUTPUT_SAMPLE_RATE, player.outputSampleRateHz())
            .putExtra(EXTRA_OUTPUT_BIT_DEPTH, player.outputBitDepth())
            .putExtra(EXTRA_OUTPUT_BACKEND, player.outputBackendLabel())
            .putExtra(EXTRA_DECODED_SAMPLE_RATE, player.decodedSampleRateHz())
            .putExtra(EXTRA_DECODED_PCM_FORMAT, player.decodedPcmFormatLabel())
            .putExtra(EXTRA_OUTPUT_PCM_FORMAT, player.outputPcmFormatLabel())
            .putExtra(EXTRA_OUTPUT_RESAMPLING, player.outputResamplingRequired())
            .putExtra(EXTRA_OUTPUT_BIT_DEPTH_CONVERSION, player.outputBitDepthConversionRequired())
            .putExtra(EXTRA_OUTPUT_SAMPLE_FORMAT_CONVERSION, player.outputSampleFormatConversionRequired())
            .putExtra(EXTRA_OUTPUT_BIT_PERFECT_CANDIDATE, player.outputBitPerfectCandidate())
            .putExtra(EXTRA_USB_PLAYBACK_STATUS, player.usbPlaybackStatusJson())
            .putExtra(EXTRA_CURRENT_URI, queue.getOrNull(queueIndex).orEmpty())
            .putExtra(EXTRA_CURRENT_CUE_START, queueCueStarts.getOrNull(queueIndex) ?: 0L)
            .putExtra(EXTRA_CURRENT_CUE_PLAYLIST, queueCuePlaylists.getOrNull(queueIndex).orEmpty())
            .putExtra(EXTRA_CURRENT_PLAYLIST_NAME, queuePlaylistNames.getOrNull(queueIndex).orEmpty())
            .putExtra(EXTRA_CURRENT_PLAYLIST_ORDER, queuePlaylistOrders.getOrNull(queueIndex) ?: -1)
            .putExtra(EXTRA_CURRENT_TITLE, currentTitle)
            .putExtra(EXTRA_CURRENT_ARTIST, currentArtist)
            .putExtra(EXTRA_IS_PLAYING, playbackState == PlaybackStateCompat.STATE_PLAYING)
            .putExtra(EXTRA_SHUFFLE_ENABLED, shuffle)
            .putExtra(EXTRA_REPEAT_MODE, repeatMode)
        sendBroadcast(intent)
        if (::session.isInitialized) {
            publishSessionState()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun publishSessionState(positionMs: Long = player.positionMs()) {
        syncPlaybackWakeLock()
        if (!::session.isInitialized) return
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(playbackState, positionMs.coerceAtLeast(0L),
                if (playbackState == PlaybackStateCompat.STATE_PLAYING) 1f else 0f,
                SystemClock.elapsedRealtime())
            .build())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playbackState == PlaybackStateCompat.STATE_PLAYING)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", servicePending(ACTION_PREVIOUS, 1))
            .addAction(
                if (playbackState == PlaybackStateCompat.STATE_PLAYING) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playbackState == PlaybackStateCompat.STATE_PLAYING) "Pause" else "Play",
                servicePending(if (playbackState == PlaybackStateCompat.STATE_PLAYING) ACTION_PAUSE else ACTION_RESUME, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", servicePending(ACTION_NEXT, 3))
            .build()
    }

    private fun servicePending(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w("FloweryDiagnostics", "SERVICE_TASK_REMOVED index=$queueIndex queueSize=${queue.size} playbackState=$playbackState")
        persistPlaybackSnapshot()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w("FloweryDiagnostics", "SERVICE_DESTROY index=$queueIndex queueSize=${queue.size} playbackState=$playbackState")
        persistPlaybackSnapshot()
        playbackPrefetch.close()
        player.close { queueState.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else abandonLegacyAudioFocus()
        hasAudioFocus = false
        focusRequest = null
        focusListener = null
        runCatching { unregisterReceiver(noisyReceiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(outputDeviceCallback) }
        mainHandler.removeCallbacks(outputRouteCheck)
        session.isActive = false
        session.release()
        io.shutdownNow()
        playbackWakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        playbackWakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
