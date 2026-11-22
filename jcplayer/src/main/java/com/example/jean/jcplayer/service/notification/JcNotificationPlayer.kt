package com.example.jean.jcplayer.service.notification

import android.Manifest
import android.app.Activity
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
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.jean.jcplayer.JcPlayerManager
import com.example.jean.jcplayer.JcPlayerManagerListener
import com.example.jean.jcplayer.R
import com.example.jean.jcplayer.general.JcStatus
import com.example.jean.jcplayer.general.PlayerUtil
import java.lang.ref.WeakReference

class JcNotificationPlayer private constructor(private val context: Context) :
    JcPlayerManagerListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    // Notification state, RemoteViews and framework calls have one main-thread owner.
    private var title: String? = null
    private var time = "00:00"
    private var iconResource: Int = R.drawable.default_icon
    private var pausedState: Boolean? = null
    private var stopped = false
    private var channelCreated = false
    private var permissionRequested = false
    private var frameworkFailureLogged = false
    private var lastPublishedState: DisplayState? = null
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }
    private val actionIntents = mutableMapOf<String, PendingIntent>()
    private val contentIntent: PendingIntent? by lazy {
        val openUi = if (context is Activity) Intent(context, context.javaClass)
            else context.packageManager.getLaunchIntentForPackage(context.packageName)
        openUi?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, buildIntentFlags())
        }
    }

    private data class DisplayState(val title: String?, val time: String, val icon: Int, val paused: Boolean)
    private data class Progress(val title: String, val time: String)
    private val progressLock = Any()
    private var pendingProgress: Progress? = null
    private val publishProgress = Runnable {
        val progress = synchronized(progressLock) {
            pendingProgress.also { pendingProgress = null }
        }
        if (progress != null && !stopped) {
            title = progress.title
            time = progress.time
            renderNotification()
        }
    }

    companion object {
        private const val TAG = "JcNotificationPlayer"
        private const val NOTIFICATION_PERMISSION_CODE = 101
        const val NEXT = "jcplayer.NEXT"
        const val PREVIOUS = "jcplayer.PREVIOUS"
        const val PAUSE = "jcplayer.PAUSE"
        const val PLAY = "jcplayer.PLAY"
        const val ACTION = "jcplayer.ACTION"
        const val PLAYLIST = "jcplayer.PLAYLIST"
        const val CURRENT_AUDIO = "jcplayer.CURRENT_AUDIO"
        private const val NOTIFICATION_ID = 100
        private const val NOTIFICATION_CHANNEL = "jcplayer.NOTIFICATION_CHANNEL"
        private const val NEXT_ID = 0
        private const val PREVIOUS_ID = 1
        private const val PLAY_ID = 2
        private const val PAUSE_ID = 3

        @Volatile
        private var INSTANCE: WeakReference<JcNotificationPlayer>? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): WeakReference<JcNotificationPlayer> {
            val existing = INSTANCE
            return if (existing?.get() != null) existing else
                WeakReference(JcNotificationPlayer(context)).also { INSTANCE = it }
        }
    }

    // Zero is the default-argument sentinel. Resolve the chosen icon on the owning
    // thread, rather than capturing a stale icon while another caller changes it.
    @JvmOverloads
    fun createNotificationPlayer(title: String?, iconResourceResource: Int = 0) {
        onMainThread {
            this.title = title
            if (iconResourceResource != 0) iconResource = iconResourceResource
            stopped = false
            renderNotification(force = true)
        }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private fun renderNotification(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested && context is Activity && !context.isFinishing && !context.isDestroyed) {
                permissionRequested = true
                ActivityCompat.requestPermissions(context,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
            }
            return
        }
        permissionRequested = false
        val state = DisplayState(title, time, iconResource,
            pausedState ?: (JcPlayerManager.getInstance(context).get()?.isPaused() == true))
        if (!force && state == lastPublishedState) return
        createNotificationChannel()
        // The custom RemoteViews already contain artwork. Never decode the host's
        // small-icon drawable into a full-resolution Bitmap on every progress tick.
        val notification = baseBuilder(state)
            .setContent(createNotificationPlayerView(state))
            .build()
        if (notifySafely(notification, state)) lastPublishedState = state
    }

    private fun baseBuilder(state: DisplayState) =
        NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(state.icon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)

    private fun notifySafely(notification: Notification, state: DisplayState): Boolean {
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            return true
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission unavailable", error)
        } catch (error: ArrayIndexOutOfBoundsException) {
            // Some framework ArraySet implementations fail inside notify(). Retry
            // once with a simple system layout; never crash audio for a failed post.
            if (!frameworkFailureLogged) Log.w(TAG, "Framework notification failure; using simple layout", error)
            frameworkFailureLogged = true
            try {
                notificationManager.notify(NOTIFICATION_ID, baseBuilder(state)
                    .setContentTitle(state.title).setContentText(state.time).build())
                return true
            } catch (retry: ArrayIndexOutOfBoundsException) {
                return false
            } catch (retry: SecurityException) {
                return false
            }
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelCreated) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL, "JcPlayer Notifications",
                NotificationManager.IMPORTANCE_LOW).apply {
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

    fun updateNotification() {
        onMainThread { if (!stopped) renderNotification() }
    }

    private fun createNotificationPlayerView(state: DisplayState): RemoteViews {
        val remoteView = RemoteViews(context.packageName,
            if (state.paused) R.layout.layout_paused_notification else R.layout.layout_playing_notification)
        if (state.paused) {
            remoteView.setOnClickPendingIntent(R.id.btn_play_notification, buildPendingIntent(PLAY, PLAY_ID))
        } else {
            remoteView.setOnClickPendingIntent(R.id.btn_pause_notification, buildPendingIntent(PAUSE, PAUSE_ID))
        }
        remoteView.setTextViewText(R.id.txt_current_music_notification, state.title)
        remoteView.setTextViewText(R.id.txt_duration_notification, state.time)
        remoteView.setImageViewResource(R.id.icon_player, R.drawable.ic_default_notification)
        remoteView.setOnClickPendingIntent(R.id.btn_next_notification, buildPendingIntent(NEXT, NEXT_ID))
        remoteView.setOnClickPendingIntent(R.id.btn_prev_notification, buildPendingIntent(PREVIOUS, PREVIOUS_ID))
        return remoteView
    }

    private fun buildIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun buildPendingIntent(action: String, id: Int): PendingIntent =
        actionIntents.getOrPut(action) {
            val intent = Intent(context.applicationContext, JcPlayerNotificationReceiver::class.java)
            intent.putExtra(ACTION, action)
            PendingIntent.getBroadcast(context.applicationContext, id, intent, buildIntentFlags())
        }

    override fun onPreparedAudio(status: JcStatus) = onPlaying(status)
    override fun onCompletedAudio() {}

    override fun onPaused(status: JcStatus) {
        val nextTitle = status.jcAudio?.title
        onMainThread {
            pausedState = true
            if (nextTitle != null) title = nextTitle
            if (!stopped) renderNotification()
        }
    }

    override fun onStopped(status: JcStatus) = destroyNotificationIfExists()
    override fun onContinueAudio(status: JcStatus) = onPlaying(status)

    override fun onPlaying(status: JcStatus) {
        val nextTitle = status.jcAudio?.title
        onMainThread {
            stopped = false
            pausedState = false
            if (nextTitle != null) title = nextTitle
            renderNotification()
        }
    }

    override fun onTimeChanged(status: JcStatus) {
        val audio = status.jcAudio ?: return
        val progress = Progress(audio.title, PlayerUtil.toTimeSongString(status.currentPosition.toInt()))
        val needsPost = synchronized(progressLock) {
            val wasEmpty = pendingProgress == null
            pendingProgress = progress
            wasEmpty
        }
        if (needsPost) mainHandler.post(publishProgress)
    }

    fun destroyNotificationIfExists() {
        onMainThread {
            stopped = true
            mainHandler.removeCallbacks(publishProgress)
            synchronized(progressLock) { pendingProgress = null }
            lastPublishedState = null
            try {
                // A player must never cancel unrelated push notifications.
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (error: ArrayIndexOutOfBoundsException) {
                Log.w(TAG, "Framework notification cancellation failed", error)
            } catch (error: SecurityException) {
                Log.w(TAG, "Notification cancellation unavailable", error)
            }
        }
    }

    override fun onJcpError(throwable: Throwable) {}
}