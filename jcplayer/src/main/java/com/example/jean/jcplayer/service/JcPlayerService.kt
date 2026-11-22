package com.example.jean.jcplayer.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.example.jean.jcplayer.JcPlayerManager
import com.example.jean.jcplayer.general.JcStatus
import com.example.jean.jcplayer.general.Origin
import com.example.jean.jcplayer.model.JcAudio
import com.example.jean.jcplayer.service.notification.JcNotificationPlayer
import com.example.jean.jcplayer.service.notification.JcMediaPresentation
import java.io.File

/** Owns playback, the Android media session, audio focus and the foreground lifetime. */
class JcPlayerService : Service(), MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
    MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener, AudioManager.OnAudioFocusChangeListener {

    private val binder = JcPlayerServiceBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var preparing = false
    private var playWhenReady = false
    private var foregroundRequested = false
    private var inForeground = false
    private var destroyed = false
    private var noisyRegistered = false
    private var focusHeld = false
    private var focusRequested = false
    private var resumeAfterFocusLoss = false
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationPlayer: JcNotificationPlayer
    private var metadataKey: Triple<String?, String?, Long>? = null

    @Volatile var isPlaying = false
        private set
    @Volatile var isPaused = true
        private set
    @Volatile var currentAudio: JcAudio? = null
        private set
    var serviceListener: JcPlayerServiceListener? = null

    inner class JcPlayerServiceBinder : Binder() {
        val service: JcPlayerService get() = this@JcPlayerService
    }

    companion object {
        private const val TAG = "JcPlayerService"
        private const val KEEP_ALIVE = "jcplayer.KEEP_ALIVE"
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                if (isPlaying) return
                val manager = serviceListener as? JcPlayerManager
                if (manager != null) manager.continueAudio() else currentAudio?.let { play(it) }
            }
            override fun onPause() { currentAudio?.let { pause(it) } }
            override fun onStop() { stop() }
            override fun onSkipToNext() { (serviceListener as? JcPlayerManager)?.nextAudio() }
            override fun onSkipToPrevious() { (serviceListener as? JcPlayerManager)?.previousAudio() }
            override fun onSeekTo(pos: Long) { seekTo(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
        }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "JcPlayer",
            ComponentName(this, MediaButtonReceiver::class.java), null)
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        mediaSession.setCallback(sessionCallback, handler)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            mediaSession.setSessionActivity(android.app.PendingIntent.getActivity(this, 100, it, flags))
        }
        notificationPlayer = JcNotificationPlayer.getInstance(this).get()!!
        notificationPlayer.attachSession(mediaSession.sessionToken) { publishNotification(it) }
        updateSession()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) MediaButtonReceiver.handleIntent(mediaSession, intent)
        if (currentAudio == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    fun getMediaSessionToken(): MediaSessionCompat.Token = mediaSession.sessionToken

    fun play(jcAudio: JcAudio): JcStatus {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { play(jcAudio) }
            return JcStatus(jcAudio, 0, 0, JcStatus.PlayState.PREPARING)
        }
        if (destroyed) return snapshot(JcStatus.PlayState.STOP)
        if (!validSource(jcAudio)) {
            serviceListener?.onError(IllegalArgumentException("Invalid audio source: ${jcAudio.origin}"))
            return snapshot(JcStatus.PlayState.STOP)
        }
        resumeAfterFocusLoss = false
        val sameSource = currentAudio?.path == jcAudio.path && currentAudio?.origin == jcAudio.origin
        val restartPlayingTrack = sameSource && prepared && isPlaying
        if (!sameSource) releasePlayer()
        currentAudio = jcAudio
        playWhenReady = true
        if (mediaPlayer == null) preparing = true
        if (!ensureForeground()) {
            playWhenReady = false
            preparing = false
            stopSelf()
            return snapshot(JcStatus.PlayState.PAUSE)
        }
        if (!requestFocus()) {
            playWhenReady = false
            preparing = false
            isPlaying = false
            isPaused = true
            publishState(force = true)
            stopSelf()
            val status = snapshot(JcStatus.PlayState.PAUSE)
            serviceListener?.onPausedListener(status)
            return status
        }
        if (prepared && mediaPlayer != null) {
            if (restartPlayingTrack) mediaPlayer?.seekTo(0)
            startPrepared(resumed = true)
            return snapshot(JcStatus.PlayState.CONTINUE)
        }
        if (mediaPlayer != null) return snapshot(JcStatus.PlayState.PREPARING)
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setOnPreparedListener(this)
            player.setOnBufferingUpdateListener(this)
            player.setOnCompletionListener(this)
            player.setOnErrorListener(this)
            player.setOnSeekCompleteListener(this)
            if (Build.VERSION.SDK_INT >= 21) player.setAudioAttributes(mediaAttributes())
            else player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            when (jcAudio.origin) {
                Origin.URL -> player.setDataSource(jcAudio.path)
                Origin.RAW -> applicationContext.resources.openRawResourceFd(jcAudio.path.toInt()).use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                Origin.ASSETS -> applicationContext.assets.openFd(jcAudio.path).use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                Origin.FILE_PATH -> player.setDataSource(applicationContext, Uri.fromFile(File(jcAudio.path)))
            }
            player.prepareAsync()
        } catch (error: Exception) {
            playbackError(error)
        }
        return snapshot(JcStatus.PlayState.PREPARING)
    }

    fun pause(jcAudio: JcAudio): JcStatus {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { pause(jcAudio) }
            return JcStatus(jcAudio, 0, 0, JcStatus.PlayState.PAUSE)
        }
        resumeAfterFocusLoss = false
        pauseInternal(abandon = true)
        return snapshot(JcStatus.PlayState.PAUSE)
    }

    private fun pauseInternal(abandon: Boolean) {
        playWhenReady = false
        if (prepared && isPlaying) mediaPlayer?.pause()
        isPlaying = false
        isPaused = true
        handler.removeCallbacks(progressTick)
        unregisterNoisy()
        if (abandon) abandonFocus()
        publishState(force = true)
        serviceListener?.onPausedListener(snapshot(JcStatus.PlayState.PAUSE))
    }

    fun stop(): JcStatus {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop() }
            return JcStatus(null, 0, 0, JcStatus.PlayState.STOP)
        }
        resumeAfterFocusLoss = false
        playWhenReady = false
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        currentAudio = null
        updateSession()
        notificationPlayer.destroyNotificationIfExists()
        leaveForeground(remove = true)
        stopSelf()
        val status = snapshot(JcStatus.PlayState.STOP)
        serviceListener?.onStoppedListener(status)
        return status
    }

    fun seekTo(time: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { seekTo(time) }; return }
        if (prepared) {
            mediaPlayer?.seekTo(time.coerceIn(0, duration().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            publishState(force = true)
        }
    }

    override fun onPrepared(player: MediaPlayer) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onPrepared(player) }; return }
        if (destroyed || mediaPlayer !== player) return
        prepared = true
        preparing = false
        if (playWhenReady && focusHeld) startPrepared(resumed = false)
        else {
            isPlaying = false
            isPaused = true
            publishState(force = true)
            serviceListener?.onPausedListener(snapshot(JcStatus.PlayState.PAUSE))
        }
    }

    private fun startPrepared(resumed: Boolean) {
        try {
            mediaPlayer?.start()
            isPlaying = true
            isPaused = false
            preparing = false
            registerNoisy()
            handler.removeCallbacks(progressTick)
            handler.postDelayed(progressTick, 1000)
            publishState(force = true)
            if (resumed) serviceListener?.onContinueListener(snapshot(JcStatus.PlayState.CONTINUE))
            else serviceListener?.onPreparedListener(snapshot(JcStatus.PlayState.PLAY))
        } catch (error: IllegalStateException) { playbackError(error) }
    }

    override fun onCompletion(player: MediaPlayer) {
        if (mediaPlayer !== player || destroyed) return
        playWhenReady = false
        isPlaying = false
        isPaused = true
        resumeAfterFocusLoss = false
        handler.removeCallbacks(progressTick)
        abandonFocus()
        unregisterNoisy()
        publishState(force = true)
        serviceListener?.onCompletedListener()
    }

    override fun onSeekComplete(player: MediaPlayer) {
        if (mediaPlayer === player && !destroyed) {
            publishState(force = true)
            serviceListener?.onTimeChangedListener(snapshot(if (isPlaying) JcStatus.PlayState.PLAYING else JcStatus.PlayState.PAUSE))
        }
    }

    override fun onBufferingUpdate(player: MediaPlayer, percent: Int) {}
    override fun onError(player: MediaPlayer, what: Int, extra: Int): Boolean {
        if (mediaPlayer === player) playbackError(IllegalStateException("MediaPlayer error $what/$extra"))
        return true
    }

    private fun playbackError(error: Exception) {
        stop()
        serviceListener?.onError(error)
    }

    private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttributes()).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this, handler).build().also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = focusHeld
        return focusHeld
    }

    private fun abandonFocus() {
        if (focusRequested) {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest!!)
            else audioManager.abandonAudioFocus(this)
        }
        focusRequested = false
        focusHeld = false
    }

    override fun onAudioFocusChange(change: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onAudioFocusChange(change) }; return }
        if (destroyed || !focusRequested) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                val resume = resumeAfterFocusLoss
                resumeAfterFocusLoss = false
                if (resume) currentAudio?.let { play(it) }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                resumeAfterFocusLoss = false
                pauseInternal(abandon = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterFocusLoss = resumeAfterFocusLoss || isPlaying || (preparing && playWhenReady)
                focusHeld = false
                pauseInternal(abandon = false)
            }
        }
    }

    private fun ensureForeground(): Boolean {
        foregroundRequested = true
        return try {
            if (!inForeground) ContextCompat.startForegroundService(this, Intent(this, JcPlayerService::class.java).setAction(KEEP_ALIVE))
            publishState(force = true)
            inForeground
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Foreground playback start not allowed", error)
            serviceListener?.onError(error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Foreground playback permission unavailable", error)
            serviceListener?.onError(error)
            false
        } finally { foregroundRequested = false }
    }

    private fun publishNotification(notification: Notification) {
        val needsForeground = foregroundRequested || isPlaying || (preparing && playWhenReady) || resumeAfterFocusLoss
        if (needsForeground && !inForeground) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(JcNotificationPlayer.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(JcNotificationPlayer.NOTIFICATION_ID, notification)
            inForeground = true
        } else {
            if (!needsForeground) leaveForeground(remove = false)
            NotificationManagerCompat.from(this).notify(JcNotificationPlayer.NOTIFICATION_ID, notification)
        }
    }

    private fun leaveForeground(remove: Boolean) {
        if (inForeground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            else stopForeground(remove)
            inForeground = false
        }
    }

    private fun updateSession() {
        val audio = currentAudio
        val length = duration()
        val key = Triple(audio?.path, audio?.title, length)
        if (metadataKey != key) {
            val label = applicationInfo.loadLabel(packageManager).toString()
            mediaSession.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, audio?.path)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, audio?.title ?: label)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audio?.title ?: label)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, JcMediaPresentation.durationText(length))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, JcMediaPresentation.durationText(length))
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, JcMediaPresentation.artwork(this))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, length).build())
            metadataKey = key
        }
        val state = when {
            audio == null -> PlaybackStateCompat.STATE_STOPPED
            preparing && playWhenReady -> PlaybackStateCompat.STATE_BUFFERING
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (audio != null) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (isPlaying) actions = actions or PlaybackStateCompat.ACTION_PAUSE
        if (prepared) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions)
            .setState(state, position(), if (isPlaying) 1f else 0f, SystemClock.elapsedRealtime()).build())
        mediaSession.isActive = audio != null
    }

    private fun publishState(force: Boolean = false) {
        updateSession()
        if (currentAudio != null) notificationPlayer.updateMediaState(currentAudio?.title, duration(), position(), isPlaying, force)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (destroyed || !isPlaying || !prepared) return
            publishState()
            serviceListener?.onTimeChangedListener(snapshot(JcStatus.PlayState.PLAYING))
            if (isPlaying) handler.postDelayed(this, 1000)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) currentAudio?.let { pause(it) }
        }
    }
    private fun registerNoisy() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisyReceiver, filter)
        noisyRegistered = true
    }
    private fun unregisterNoisy() {
        if (noisyRegistered) { unregisterReceiver(noisyReceiver); noisyRegistered = false }
    }
    private fun position(): Long = if (!prepared) 0 else try { mediaPlayer?.currentPosition?.toLong() ?: 0 } catch (_: IllegalStateException) { 0 }
    private fun duration(): Long = if (!prepared) 0 else try { mediaPlayer?.duration?.toLong() ?: 0 } catch (_: IllegalStateException) { 0 }
    private fun snapshot(state: JcStatus.PlayState) = JcStatus(currentAudio, duration(), position(), state)
    private fun validSource(audio: JcAudio): Boolean = when (audio.origin) {
        Origin.URL -> audio.path.startsWith("http://") || audio.path.startsWith("https://")
        Origin.RAW -> audio.path.toIntOrNull()?.let { it != 0 } == true
        Origin.ASSETS -> audio.path.isNotBlank()
        Origin.FILE_PATH -> File(audio.path).exists()
    }
    private fun releasePlayer() {
        handler.removeCallbacks(progressTick)
        mediaPlayer?.release()
        mediaPlayer = null
        prepared = false
        preparing = false
        isPlaying = false
        isPaused = true
    }
    fun getMediaPlayer(): MediaPlayer? = mediaPlayer
    fun finalize() { stop(); stopSelf() }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        resumeAfterFocusLoss = false
        playWhenReady = false
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        currentAudio = null
        if (::mediaSession.isInitialized) {
            updateSession()
            notificationPlayer.detachSession(mediaSession.sessionToken)
            notificationPlayer.destroyNotificationIfExists()
            mediaSession.isActive = false
            mediaSession.release()
        }
        leaveForeground(remove = true)
        serviceListener = null
        super.onDestroy()
    }
}