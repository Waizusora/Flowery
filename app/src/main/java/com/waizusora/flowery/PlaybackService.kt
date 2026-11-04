package com.waizusora.flowery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
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
        const val ACTION_STOP = "com.waizusora.flowery.STOP"
        const val ACTION_NEXT = "com.waizusora.flowery.NEXT"
        const val ACTION_PREVIOUS = "com.waizusora.flowery.PREVIOUS"
        const val ACTION_QUEUE = "com.waizusora.flowery.QUEUE"
        const val ACTION_VOLUME = "com.waizusora.flowery.VOLUME"
        const val ACTION_EQUALIZER = "com.waizusora.flowery.EQUALIZER"
        const val ACTION_SHUFFLE = "com.waizusora.flowery.SHUFFLE"
        const val ACTION_REPEAT = "com.waizusora.flowery.REPEAT"
        const val ACTION_SEEK = "com.waizusora.flowery.SEEK"
        const val EXTRA_URI = "track_uri"
        const val EXTRA_QUEUE = "queue_uris"
        const val EXTRA_INDEX = "queue_index"
        const val EXTRA_VALUE = "value"
        const val EXTRA_GAINS = "gains"
        const val EXTRA_POSITION = "position_ms"
        const val EXTRA_DURATION = "duration_ms"
        const val BROADCAST_STATE = "com.waizusora.flowery.PLAYBACK_STATE"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "flowery_playback"
        private const val NOTIFICATION_ID = 41
    }

    private lateinit var player: FloweryPlayer
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val io = Executors.newSingleThreadExecutor()
    private val queue = mutableListOf<String>()
    private var queueIndex = -1
    private var repeatMode = 0 // 0 off, 1 all, 2 one
    private var shuffle = false
    private var currentTitle = "Flowery"
    private var currentArtist = "Local music"
    @Volatile private var playbackState = PlaybackStateCompat.STATE_STOPPED
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressUpdates = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName)
                    .putExtra(EXTRA_POSITION, player.positionMs())
                    .putExtra(EXTRA_DURATION, player.durationMs()))
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        player = FloweryPlayer(this) { message ->
            publishState(message)
            if (message == "Playback finished") handleCompletion()
        }
        createNotificationChannel()
        session = MediaSessionCompat(this, "FloweryPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(position: Long) = player.seekTo(position)
            })
            isActive = true
        }
        publishSessionState()
        mainHandler.post(progressUpdates)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val uris = intent.getStringArrayListExtra(EXTRA_QUEUE)
                if (!uris.isNullOrEmpty()) {
                    queue.clear()
                    queue.addAll(uris)
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
                queueIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                if (queue.isNotEmpty()) playCurrent()
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_VOLUME -> player.setVolume(intent.getFloatExtra(EXTRA_VALUE, 1f))
            ACTION_EQUALIZER -> intent.getFloatArrayExtra(EXTRA_GAINS)?.let(player::setEqualizerGains)
            ACTION_SHUFFLE -> { shuffle = !shuffle; publishState(if (shuffle) "Shuffle enabled" else "Shuffle disabled") }
            ACTION_REPEAT -> { repeatMode = (repeatMode + 1) % 3; publishState("Repeat mode: ${listOf("Off", "All", "One")[repeatMode]}") }
            ACTION_SEEK -> player.seekTo(intent.getLongExtra(EXTRA_POSITION, 0L))
        }
        return START_NOT_STICKY
    }

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.setVolume(0.25f)
                AudioManager.AUDIOFOCUS_GAIN -> player.setVolume(1f)
            }
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun playCurrent() {
        val uriText = queue.getOrNull(queueIndex) ?: return
        if (!requestFocus()) {
            publishState("Playback could not start because audio focus was denied")
            return
        }
        val uri = Uri.parse(uriText)
        currentTitle = Uri.decode(uri.lastPathSegment.orEmpty()).substringBeforeLast('.').ifBlank { "Unknown track" }
        currentArtist = "Local music"
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .build())
        startForeground(NOTIFICATION_ID, buildNotification())
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to open selected audio file")
                player.play(bytes)
                playbackState = PlaybackStateCompat.STATE_PLAYING
                publishSessionState()
            } catch (error: Throwable) {
                publishState("Unable to load file: ${error.message}")
            }
        }
    }

    private fun pausePlayback() {
        player.pause()
        playbackState = PlaybackStateCompat.STATE_PAUSED
        publishSessionState()
        publishState("Paused")
    }

    private fun resumePlayback() {
        if (queue.isEmpty()) return
        if (playbackState == PlaybackStateCompat.STATE_STOPPED) playCurrent()
        else {
            if (requestFocus()) player.resume()
            playbackState = PlaybackStateCompat.STATE_PLAYING
            publishSessionState()
        }
    }

    private fun stopPlayback() {
        player.stop()
        playbackState = PlaybackStateCompat.STATE_STOPPED
        publishSessionState()
        publishState("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun skipNext() {
        if (queue.isEmpty()) return
        queueIndex = if (shuffle && queue.size > 1) {
            (queue.indices.filter { it != queueIndex }.random())
        } else if (queueIndex < queue.lastIndex) queueIndex + 1
        else if (repeatMode == 1) 0 else return
        player.stop()
        playCurrent()
    }

    private fun skipPrevious() {
        if (queue.isEmpty()) return
        queueIndex = if (queueIndex > 0) queueIndex - 1 else if (repeatMode == 1) queue.lastIndex else 0
        player.stop()
        playCurrent()
    }

    private fun handleCompletion() {
        if (repeatMode == 2) {
            player.stop()
            playCurrent()
        } else skipNext()
    }

    private fun publishState(message: String) {
        val intent = Intent(BROADCAST_STATE).setPackage(packageName).putExtra(EXTRA_STATUS, message)
            .putExtra(EXTRA_POSITION, player.positionMs()).putExtra(EXTRA_DURATION, player.durationMs())
        sendBroadcast(intent)
        if (::session.isInitialized) {
            playbackState = when {
                message.startsWith("Playing") -> PlaybackStateCompat.STATE_PLAYING
                message == "Paused" -> PlaybackStateCompat.STATE_PAUSED
                message == "Stopped" || message == "Playback finished" -> PlaybackStateCompat.STATE_STOPPED
                else -> playbackState
            }
            publishSessionState()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun publishSessionState() {
        if (!::session.isInitialized) return
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
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
        player.stop()
        session.isActive = false
        session.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
