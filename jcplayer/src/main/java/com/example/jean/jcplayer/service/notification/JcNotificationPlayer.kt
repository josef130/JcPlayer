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
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
import androidx.core.app.NotificationManagerCompat
import com.example.jean.jcplayer.JcPlayerManager
import com.example.jean.jcplayer.JcPlayerManagerListener
import com.example.jean.jcplayer.R
import com.example.jean.jcplayer.general.JcStatus
import com.example.jean.jcplayer.general.PlayerUtil
import java.lang.ref.WeakReference

class JcNotificationPlayer private constructor(private val context: Context) :
    JcPlayerManagerListener {

    private var title: String? = null
    private var time = "00:00"
    private var iconResource: Int = R.drawable.default_icon
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }
    private var notification: Notification? = null

    companion object {
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
        fun getInstance(context: Context): WeakReference<JcNotificationPlayer> = INSTANCE ?: let {
            INSTANCE = WeakReference(JcNotificationPlayer(context))
            INSTANCE!!
        }
    }

    fun createNotificationPlayer(title: String?, iconResourceResource: Int) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // If permission isn't granted, request permission or handle accordingly
            ActivityCompat.requestPermissions(
                context as Activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
            return // Exit if permission isn't granted
        }

        this.title = title
        this.iconResource = iconResourceResource
        val openUi = Intent(context, context.javaClass)
        openUi.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP

        notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(iconResourceResource)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, iconResourceResource))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContent(createNotificationPlayerView())
            .setSound(null)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    openUi,
                    buildIntentFlags()
                )
            )
            .setAutoCancel(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        try {
            notification?.let { notificationManager.notify(NOTIFICATION_ID, it) }
        } catch (e: SecurityException) {
            e.printStackTrace() // Log or handle SecurityException if permission is revoked
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "JcPlayer Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun updateNotification() {
        createNotificationPlayer(title, R.drawable.ic_default_notification)
    }

    private fun createNotificationPlayerView(): RemoteViews {
        val remoteView: RemoteViews

        if (JcPlayerManager.getInstance(context).get()?.isPaused() == true) {
            remoteView = RemoteViews(context.packageName, R.layout.layout_paused_notification)
            remoteView.setOnClickPendingIntent(
                R.id.btn_play_notification,
                buildPendingIntent(PLAY, PLAY_ID)
            )
        } else {
            remoteView = RemoteViews(context.packageName, R.layout.layout_playing_notification)
            remoteView.setOnClickPendingIntent(
                R.id.btn_pause_notification,
                buildPendingIntent(PAUSE, PAUSE_ID)
            )
        }

        remoteView.setTextViewText(R.id.txt_current_music_notification, title)
        remoteView.setTextViewText(R.id.txt_duration_notification, time)
        remoteView.setImageViewResource(R.id.icon_player, R.drawable.ic_default_notification)
        remoteView.setOnClickPendingIntent(
            R.id.btn_next_notification,
            buildPendingIntent(NEXT, NEXT_ID)
        )
        remoteView.setOnClickPendingIntent(
            R.id.btn_prev_notification,
            buildPendingIntent(PREVIOUS, PREVIOUS_ID)
        )

        return remoteView
    }

    private fun buildIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun buildPendingIntent(action: String, id: Int): PendingIntent {
        val playIntent =
            Intent(context.applicationContext, JcPlayerNotificationReceiver::class.java)
        playIntent.putExtra(ACTION, action)

        return PendingIntent.getBroadcast(
            context.applicationContext,
            id,
            playIntent,
            buildIntentFlags()
        )
    }

    override fun onPreparedAudio(status: JcStatus) {}

    override fun onCompletedAudio() {}

    override fun onPaused(status: JcStatus) {
        createNotificationPlayer(title, R.drawable.ic_default_notification)
    }

    override fun onStopped(status: JcStatus) {
        destroyNotificationIfExists()
    }

    override fun onContinueAudio(status: JcStatus) {}

    override fun onPlaying(status: JcStatus) {
        createNotificationPlayer(title, R.drawable.ic_default_notification)
    }

    override fun onTimeChanged(status: JcStatus) {
        this.time = PlayerUtil.toTimeSongString(status.currentPosition.toInt())
        this.title = status.jcAudio.title
        createNotificationPlayer(title, R.drawable.ic_default_notification)
    }

    fun destroyNotificationIfExists() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.cancelAll()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
    }

    override fun onJcpError(throwable: Throwable) {}
}
