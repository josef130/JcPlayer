package com.example.jean.jcplayer.service.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.jean.jcplayer.JcPlayerManager
import com.example.jean.jcplayer.JcPlayerManagerListener
import com.example.jean.jcplayer.R
import com.example.jean.jcplayer.general.JcStatus
import com.example.jean.jcplayer.general.PlayerUtil
import java.lang.ref.WeakReference

class JcNotificationPlayer private constructor(context: Context) : JcPlayerManagerListener {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var title: String? = null
    private var time = "00:00"
    private var duration = 0L
    private var iconResource = R.drawable.default_icon
    private var paused = true
    private var stopped = false
    private var channelCreated = false
    private var frameworkFailureLogged = false
    private var lastPublishedState: DisplayState? = null
    private var sessionToken: MediaSessionCompat.Token? = null
    private var publisher: ((Notification) -> Unit)? = null
    private val notificationManager by lazy { NotificationManagerCompat.from(this.context) }
    private val actionIntents = mutableMapOf<String, PendingIntent>()
    private val contentIntent: PendingIntent? by lazy {
        this.context.packageManager.getLaunchIntentForPackage(this.context.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            PendingIntent.getActivity(this.context, NOTIFICATION_ID, it, buildIntentFlags())
        }
    }
    private val applicationName by lazy {
        this.context.applicationInfo.loadLabel(this.context.packageManager).toString()
    }
    private data class DisplayState(val title: String?, val time: String, val duration: Long, val icon: Int, val paused: Boolean)
    private data class Progress(val title: String, val time: String, val duration: Long)
    private val progressLock = Any()
    private var pendingProgress: Progress? = null
    private val publishProgress = Runnable {
        val progress = synchronized(progressLock) { pendingProgress.also { pendingProgress = null } }
        if (progress != null && !stopped) {
            title = progress.title
            time = progress.time
            duration = progress.duration
            renderNotification()
        }
    }

    companion object {
        private const val TAG = "JcNotificationPlayer"
        const val NEXT = "jcplayer.NEXT"
        const val PREVIOUS = "jcplayer.PREVIOUS"
        const val PAUSE = "jcplayer.PAUSE"
        const val PLAY = "jcplayer.PLAY"
        const val STOP = "jcplayer.STOP"
        const val ACTION = "jcplayer.ACTION"
        const val PLAYLIST = "jcplayer.PLAYLIST"
        const val CURRENT_AUDIO = "jcplayer.CURRENT_AUDIO"
        internal const val NOTIFICATION_ID = 100
        private const val NOTIFICATION_CHANNEL = "jcplayer.NOTIFICATION_CHANNEL"
        @Volatile private var INSTANCE: WeakReference<JcNotificationPlayer>? = null

        @JvmStatic @Synchronized
        fun getInstance(context: Context): WeakReference<JcNotificationPlayer> {
            val existing = INSTANCE
            return if (existing?.get() != null) existing else
                WeakReference(JcNotificationPlayer(context)).also { INSTANCE = it }
        }
    }

    internal fun attachSession(token: MediaSessionCompat.Token, publish: (Notification) -> Unit) {
        sessionToken = token
        publisher = publish
        lastPublishedState = null
    }

    internal fun detachSession(token: MediaSessionCompat.Token) {
        if (sessionToken == token) {
            sessionToken = null
            publisher = null
            lastPublishedState = null
        }
    }

    internal fun updateMediaState(title: String?, duration: Long, position: Long, playing: Boolean, force: Boolean = false) {
        onMainThread {
            this.title = title
            this.duration = duration
            this.time = PlayerUtil.toTimeSongString(position.coerceAtLeast(0).toInt())
            paused = !playing
            stopped = false
            renderNotification(force)
        }
    }

    @JvmOverloads
    fun createNotificationPlayer(title: String?, iconResourceResource: Int = 0) {
        onMainThread {
            if (title != null) this.title = title
            if (iconResourceResource != 0) iconResource = iconResourceResource
            stopped = false
            renderNotification(force = true)
        }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    private fun renderNotification(force: Boolean = false) {
        if (title == null) return
        // Notifications backed by a valid media session are exempt from Android 13's
        // POST_NOTIFICATIONS gate. Do not request Activity permissions from a Service.
        if (sessionToken == null && Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val state = DisplayState(title, time, duration, iconResource, paused)
        if (!force && state == lastPublishedState) return
        createNotificationChannel()
        val builder = baseBuilder(state)
            .addAction(R.drawable.ic_previous_white, context.getString(R.string.jc_media_previous), buildPendingIntent(PREVIOUS, 1))
            .addAction(if (state.paused) R.drawable.ic_play_white else R.drawable.ic_pause_white,
                context.getString(if (state.paused) R.string.jc_media_play else R.string.jc_media_pause),
                buildPendingIntent(if (state.paused) PLAY else PAUSE, if (state.paused) 2 else 3))
            .addAction(R.drawable.ic_next_white, context.getString(R.string.jc_media_next), buildPendingIntent(NEXT, 0))
            .setStyle(MediaStyle().setMediaSession(sessionToken).setShowActionsInCompactView(0, 1, 2))
        if (notifySafely(builder.build(), state)) lastPublishedState = state
    }

    private fun baseBuilder(state: DisplayState) = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
        .setSmallIcon(state.icon)
        .setContentTitle(state.title ?: applicationName)
        .setContentText(applicationName)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setShowWhen(false)
        .setSound(null)
        .setOnlyAlertOnce(true)
        .setOngoing(!state.paused)
        .setContentIntent(contentIntent)
        .setDeleteIntent(buildPendingIntent(STOP, 4))

    private fun post(notification: Notification) {
        publisher?.invoke(notification) ?: notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifySafely(notification: Notification, state: DisplayState): Boolean {
        try {
            post(notification)
            return true
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission unavailable", error)
        } catch (error: ArrayIndexOutOfBoundsException) {
            if (!frameworkFailureLogged) Log.w(TAG, "Framework notification failure; using simple layout", error)
            frameworkFailureLogged = true
            try {
                post(baseBuilder(state).setStyle(MediaStyle().setMediaSession(sessionToken)).build())
                return true
            } catch (retry: ArrayIndexOutOfBoundsException) { return false }
            catch (retry: SecurityException) { return false }
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26 && !channelCreated) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL, "JcPlayer media playback", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            context.getSystemService(NotificationManager::class.java)?.let {
                it.createNotificationChannel(channel)
                channelCreated = true
            }
        }
    }

    fun updateNotification() = onMainThread { if (!stopped) renderNotification() }

    private fun buildIntentFlags() = if (Build.VERSION.SDK_INT >= 23)
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT

    private fun buildPendingIntent(action: String, id: Int): PendingIntent = actionIntents.getOrPut(action) {
        val intent = Intent(context, JcPlayerNotificationReceiver::class.java).putExtra(ACTION, action)
        PendingIntent.getBroadcast(context, id, intent, buildIntentFlags())
    }

    override fun onPreparedAudio(status: JcStatus) = onPlaying(status)
    override fun onCompletedAudio() {}
    override fun onPaused(status: JcStatus) = onMainThread {
        paused = true
        status.jcAudio?.title?.let { title = it }
        if (!stopped) renderNotification()
    }
    override fun onStopped(status: JcStatus) = destroyNotificationIfExists()
    override fun onContinueAudio(status: JcStatus) = onPlaying(status)
    override fun onPlaying(status: JcStatus) = onMainThread {
        stopped = false
        paused = false
        status.jcAudio?.title?.let { title = it }
        renderNotification()
    }
    override fun onTimeChanged(status: JcStatus) {
        val audio = status.jcAudio ?: return
        val progress = Progress(audio.title, PlayerUtil.toTimeSongString(status.currentPosition.toInt()), status.duration)
        val needsPost = synchronized(progressLock) {
            val wasEmpty = pendingProgress == null
            pendingProgress = progress
            wasEmpty
        }
        if (needsPost) handler.post(publishProgress)
    }
    fun destroyNotificationIfExists() = onMainThread {
        stopped = true
        title = null
        handler.removeCallbacks(publishProgress)
        synchronized(progressLock) { pendingProgress = null }
        lastPublishedState = null
        try { notificationManager.cancel(NOTIFICATION_ID) }
        catch (error: ArrayIndexOutOfBoundsException) { Log.w(TAG, "Framework cancellation failed", error) }
        catch (error: SecurityException) { Log.w(TAG, "Notification cancellation unavailable", error) }
    }
    override fun onJcpError(throwable: Throwable) {}
}