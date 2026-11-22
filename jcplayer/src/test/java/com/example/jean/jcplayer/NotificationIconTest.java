package com.example.jean.jcplayer;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import com.example.jean.jcplayer.general.JcStatus;
import com.example.jean.jcplayer.model.JcAudio;
import com.example.jean.jcplayer.service.notification.JcNotificationPlayer;
import com.example.jean.jcplayer.view.JcPlayerView;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.Shadows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NotificationIconTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private JcNotificationPlayer notificationPlayer;
    private JcStatus status;

    @Before public void setUp() throws Exception {
        resetSingleton(JcPlayerManager.class);
        resetSingleton(JcNotificationPlayer.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notificationPlayer = JcNotificationPlayer.getInstance(activity).get();
        status = new JcStatus(JcAudio.createFromURL("Track", "https://example.com/track.mp3"),
                60000, 1000, JcStatus.PlayState.PLAYING);
    }

    @Test public void defaultIconSurvivesEveryPlaybackUpdate() {
        notificationPlayer.createNotificationPlayer("Track");
        assertIcon(R.drawable.default_icon);
        updateEveryState(R.drawable.default_icon);
    }

    @Test public void customIconSurvivesEveryPlaybackUpdate() {
        notificationPlayer.createNotificationPlayer("Track", R.drawable.ic_default_notification);
        assertIcon(R.drawable.ic_default_notification);
        updateEveryState(R.drawable.ic_default_notification);
    }

    @Test public void viewForwardsCustomIconAndDefaultCallPreservesItAcrossBackgroundReturn() {
        JcPlayerView view = new JcPlayerView(activity);
        view.createNotification(R.drawable.ic_default_notification);
        assertIcon(R.drawable.ic_default_notification);
        controller.pause().stop();
        view.createNotification();
        assertIcon(R.drawable.ic_default_notification);
        controller.start().resume();
        JcPlayerManager.getInstance(activity, null, null).get().updateNotification();
        assertIcon(R.drawable.ic_default_notification);
    }

    @Test public void selectingAnotherIconUpdatesThePersistentChoice() {
        notificationPlayer.createNotificationPlayer("Track", R.drawable.ic_default_notification);
        notificationPlayer.createNotificationPlayer("Track", R.drawable.default_icon);
        updateEveryState(R.drawable.default_icon);
    }

    @Test public void customIconSelectedBeforePermissionGrantIsRetained() {
        Shadows.shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notificationPlayer.createNotificationPlayer("Track", R.drawable.ic_default_notification);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notificationPlayer.onPlaying(status);
        assertIcon(R.drawable.ic_default_notification);
    }
    private void updateEveryState(int icon) {
        notificationPlayer.onPlaying(status);
        assertIcon(icon);
        notificationPlayer.onPaused(status);
        assertIcon(icon);
        notificationPlayer.onTimeChanged(status);
        assertIcon(icon);
        notificationPlayer.updateNotification();
        assertIcon(icon);
        notificationPlayer.onStopped(status);
        notificationPlayer.onPlaying(status);
        assertIcon(icon);
    }

    private void assertIcon(int expected) {
        NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = Shadows.shadowOf(manager).getNotification(100);
        assertNotNull(notification);
        assertEquals(expected, notification.getSmallIcon().getResId());
    }

    private static void resetSingleton(Class<?> type) throws Exception {
        Field instance = type.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}