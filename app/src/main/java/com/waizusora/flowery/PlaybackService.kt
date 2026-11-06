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
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
        const val ACTION_EQUALIZER = "com.waizusora.flowery.EQUALIZER"
        const val ACTION_SHUFFLE = "com.waizusora.flowery.SHUFFLE"
        const val ACTION_REPEAT = "com.waizusora.flowery.REPEAT"
        const val ACTION_SEEK = "com.waizusora.flowery.SEEK"
        const val ACTION_UPDATE_METADATA = "com.waizusora.flowery.UPDATE_METADATA"
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
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_ARTIST = "media_artist"
        const val EXTRA_CURRENT_TITLE = "current_title"
        const val EXTRA_CURRENT_ARTIST = "current_artist"
        const val EXTRA_GAINS = "gains"
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
        private const val CHANNEL_ID = "flowery_playback"
        private const val NOTIFICATION_ID = 41
    }

    private lateinit var player: PlaybackCoordinator
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var hasAudioFocus = false
    private var resumeAfterFocus = false
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
    @Volatile private var playbackState = PlaybackStateCompat.STATE_STOPPED
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastOutputDeviceSnapshot: List<String> = emptyList()
    private val outputRouteCheck = Runnable {
        val current = outputDeviceSnapshot()
        if (current != lastOutputDeviceSnapshot) {
            lastOutputDeviceSnapshot = current
            if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
                pauseForOutputChange()
            } else {
                publishState("Audio output changed · press Play when ready")
            }
        }
    }
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            scheduleOutputRouteCheck()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            scheduleOutputRouteCheck()
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }
    private val progressUpdates = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val position = player.positionMs()
                val duration = player.durationMs()
                if (::session.isInitialized && duration > 0L) {
                    session.setMetadata(MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                        .build())
                    publishSessionState(position)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                }
                sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName)
                    .putExtra(EXTRA_POSITION, position)
                    .putExtra(EXTRA_DURATION, duration)
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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        player = PlaybackCoordinator(this) { message ->
            publishState(message)
            if (message == "Playback finished") handleCompletion()
        }
        createNotificationChannel()
        session = MediaSessionCompat(this, "FloweryPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(position: Long) = player.seekTo(position)
            })
            isActive = true
        }
        publishSessionState()
        mainHandler.post(progressUpdates)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        lastOutputDeviceSnapshot = outputDeviceSnapshot()
        audioManager.registerAudioDeviceCallback(outputDeviceCallback, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
                } else {
                    val uri = intent.getStringExtra(EXTRA_URI)
                    if (uri != null && uri !in queue) queue.add(uri)
                    queueIndex = queue.indexOf(uri).coerceAtLeast(0)
                }
                startForeground(NOTIFICATION_ID, buildNotification())
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
                if (queue.isNotEmpty()) playCurrent()
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_VOLUME -> {
                val requestedVolume = intent.getFloatExtra(EXTRA_VALUE, 1f)
                io.execute { if (::player.isInitialized) player.setVolume(requestedVolume) }
            }
            ACTION_EQUALIZER -> intent.getFloatArrayExtra(EXTRA_GAINS)?.let(player::setEqualizerGains)
            ACTION_SHUFFLE -> { shuffle = !shuffle; publishState(if (shuffle) "Shuffle enabled" else "Shuffle disabled") }
            ACTION_REPEAT -> { repeatMode = queueState.cycleRepeatMode(); publishState("Repeat mode: ${listOf("Off", "All", "One")[repeatMode]}") }
            ACTION_SEEK -> player.seekTo(intent.getLongExtra(EXTRA_POSITION, 0L))
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
        return START_NOT_STICKY
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
                    // Ordinary app audio must not interrupt Flowery. A call is identified
                    // separately through the communication/ringtone audio mode.
                    hasAudioFocus = false
                    if (isCallAudioMode()) {
                        scheduleCallModeCheck()
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasAudioFocus = true
                    mainHandler.postDelayed({
                        if (resumeAfterFocus && !isCallAudioMode()) {
                            resumeAfterFocus = false
                            player.setDucked(false)
                            player.resume()
                            playbackState = PlaybackStateCompat.STATE_PLAYING
                            publishSessionState()
                            publishState("Playing · call ended")
                        }
                    }, 400L)
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
        mainHandler.postDelayed({
            if (isCallAudioMode() && playbackState == PlaybackStateCompat.STATE_PLAYING) {
                resumeAfterFocus = true
                player.pause()
                playbackState = PlaybackStateCompat.STATE_PAUSED
                publishSessionState()
                publishState("Paused · phone or app call")
            }
        }, 500L)
    }

    private fun playCurrent() {
        val uriText = queue.getOrNull(queueIndex) ?: return
        if (!requestFocus()) {
            publishState("Playback could not start because audio focus was denied")
            return
        }
        val uri = Uri.parse(uriText)
        currentTitle = queueTitles.getOrNull(queueIndex)
            ?.takeIf { it.isNotBlank() }
            ?: Uri.decode(uri.lastPathSegment.orEmpty()).substringBeforeLast('.').ifBlank { "Unknown track" }
        currentArtist = queueArtists.getOrNull(queueIndex)?.takeIf { it.isNotBlank() && it != "Local music" } ?: "Unknown artist"
        val embeddedArtwork = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val embeddedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val virtualEntry = !queueCuePlaylists.getOrNull(queueIndex).isNullOrBlank() ||
                    !queuePlaylistNames.getOrNull(queueIndex).isNullOrBlank()
                if (!virtualEntry) currentTitle = embeddedTitle?.takeIf { it.isNotBlank() } ?: currentTitle
                if (currentArtist == "Unknown artist") currentArtist = embeddedArtist?.takeIf { it.isNotBlank() } ?: currentArtist
                retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            } finally { retriever.release() }
        }.getOrNull()
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.durationMs())
        if (embeddedArtwork != null) metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, embeddedArtwork)
        session.setMetadata(metadataBuilder.build())
        startForeground(NOTIFICATION_ID, buildNotification())
        io.execute {
            try {
                player.play(uri, queueCueStarts.getOrNull(queueIndex) ?: 0L, queueCueEnds.getOrNull(queueIndex)?.takeIf { it > 0L })
                playbackState = PlaybackStateCompat.STATE_PLAYING
                publishSessionState()
            } catch (error: Throwable) {
                publishState("Unable to load file: ${error.message}")
            }
        }
    }

    private fun outputDeviceSnapshot(): List<String> {
        val monitoredTypes = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in monitoredTypes }
            .map { "${it.type}:${it.id}:${it.productName}" }
            .sorted()
    }

    private fun scheduleOutputRouteCheck() {
        mainHandler.removeCallbacks(outputRouteCheck)
        mainHandler.removeCallbacks(progressUpdates)
        mainHandler.postDelayed(outputRouteCheck, 350L)
    }

    private fun pauseForOutputChange() {
        resumeAfterFocus = false
        player.pause()
        playbackState = PlaybackStateCompat.STATE_PAUSED
        publishSessionState()
        publishState("Paused · audio output changed. Press Play to resume.")
    }

    private fun pausePlayback() {
        resumeAfterFocus = false
        player.pause()
        playbackState = PlaybackStateCompat.STATE_PAUSED
        publishSessionState()
        publishState("Paused")
    }

    private fun resumePlayback() {
        if (queue.isEmpty()) return
        resumeAfterFocus = false
        if (playbackState == PlaybackStateCompat.STATE_STOPPED) playCurrent()
        else {
            if (requestFocus()) player.resume()
            playbackState = PlaybackStateCompat.STATE_PLAYING
            publishSessionState()
        }
    }

    private fun stopPlayback() {
        player.stop()
        resumeAfterFocus = false
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else abandonLegacyAudioFocus()
        focusRequest = null
        focusListener = null
        playbackState = PlaybackStateCompat.STATE_STOPPED
        publishSessionState()
        publishState("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        when (val nextIndex = queueState.transition(queue.size, PlaybackQueueState.Action.COMPLETION)) {
            -2 -> {
                player.stop()
                playCurrent()
            }
            -1 -> stopPlayback()
            else -> {
                queueIndex = nextIndex
                player.stop()
                playCurrent()
            }
        }
    }

    private fun publishState(message: String) {
        playbackState = when {
            message.startsWith("Playing") -> PlaybackStateCompat.STATE_PLAYING
            message == "Paused" -> PlaybackStateCompat.STATE_PAUSED
            message == "Stopped" || message == "Playback finished" -> PlaybackStateCompat.STATE_STOPPED
            else -> playbackState
        }
        val intent = Intent(BROADCAST_STATE).setPackage(packageName).putExtra(EXTRA_STATUS, message)
            .putExtra(EXTRA_POSITION, player.positionMs()).putExtra(EXTRA_DURATION, player.durationMs())
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
        if (!::session.isInitialized) return
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(playbackState, positionMs.coerceAtLeast(0L),
                if (playbackState == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
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

    override fun onDestroy() {
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
