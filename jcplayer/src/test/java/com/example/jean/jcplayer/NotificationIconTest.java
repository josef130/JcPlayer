package com.example.jean.jcplayer;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import com.example.jean.jcplayer.service.JcPlayerService;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.atomic.AtomicReference;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNotificationManager;
import static org.junit.Assert.*;
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
        JcPlayerService service = Robolectric.buildService(JcPlayerService.class).create().get();
        Shadows.shadowOf(activity.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName(activity, JcPlayerService.class),
                service.onBind(new Intent(activity, JcPlayerService.class)));
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
    @Test public void notificationNeverAllocatesADuplicateLargeIcon() {
        notificationPlayer.createNotificationPlayer("Track", R.drawable.ic_default_notification);
        Notification posted = latestNotification();
        assertNull(posted.getLargeIcon());
        assertNotNull(posted.contentView);
    }

    @Test public void duplicateProgressWithinSameSecondDoesNotRebuildNotification() {
        notificationPlayer.createNotificationPlayer("Track");
        notificationPlayer.onTimeChanged(status);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Notification first = latestNotification();
        for (int i = 0; i < 100; i++) notificationPlayer.onTimeChanged(status);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(first, latestNotification());
    }

    @Test
    @Config(shadows = FailingNotificationManager.class)
    public void backgroundBurstsAreCoalescedAndOnlyPostedFromMainThread() throws Exception {
        FailingNotificationManager.reset(0);
        notificationPlayer.createNotificationPlayer("Track");
        Notification before = latestNotification();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                notificationPlayer.createNotificationPlayer("Track", R.drawable.ic_default_notification);
                for (int i = 0; i < 200; i++) notificationPlayer.onTimeChanged(status);
                notificationPlayer.createNotificationPlayer("Track");
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        worker.join();
        assertNull(failure.get());
        assertSame(before, latestNotification());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertIcon(R.drawable.ic_default_notification);
        assertEquals(0, FailingNotificationManager.offMainCalls);
        assertTrue(FailingNotificationManager.attempts <= 4);
    }

    @Test
    @Config(shadows = FailingNotificationManager.class)
    public void frameworkArraySetFailureRetriesWithSimpleLayout() {
        FailingNotificationManager.reset(1);
        notificationPlayer.createNotificationPlayer("Track");
        assertIcon(R.drawable.default_icon);
        assertNull(latestNotification().contentView);
        assertEquals(2, FailingNotificationManager.attempts);
    }

    @Test
    @Config(shadows = FailingNotificationManager.class)
    public void persistentFrameworkArraySetFailureDoesNotCrashAudioCaller() {
        FailingNotificationManager.reset(-1);
        notificationPlayer.createNotificationPlayer("Track");
        assertEquals(2, FailingNotificationManager.attempts);
        assertNull(latestNotification());
    }

    @Test public void stoppingClearsQueuedProgressButKeepsOtherAppNotifications() {
        notificationPlayer.createNotificationPlayer("Track");
        NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(200, new NotificationCompat.Builder(activity, "jcplayer.NOTIFICATION_CHANNEL")
                .setSmallIcon(R.drawable.default_icon).build());
        notificationPlayer.onTimeChanged(status);
        notificationPlayer.onStopped(status);
        notificationPlayer.onTimeChanged(status); // A late tick must not revive a stopped notification.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(latestNotification());
        assertNotNull(Shadows.shadowOf(manager).getNotification(200));
    }

    @Test public void applicationContextWithoutPermissionDoesNotCastToActivity() throws Exception {
        resetSingleton(JcNotificationPlayer.class);
        notificationPlayer = JcNotificationPlayer.getInstance(activity.getApplicationContext()).get();
        Shadows.shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notificationPlayer.createNotificationPlayer("Track", R.drawable.ic_default_notification);
        assertNull(latestNotification());
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notificationPlayer.onPlaying(status);
        assertIcon(R.drawable.ic_default_notification);
    }

    private Notification latestNotification() {
        NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        return Shadows.shadowOf(manager).getNotification(100);
    }

    @Implements(NotificationManager.class)
    public static class FailingNotificationManager extends ShadowNotificationManager {
        static int failuresRemaining;
        static int attempts;
        static int offMainCalls;
        static void reset(int failures) { failuresRemaining = failures; attempts = 0; offMainCalls = 0; }

        @Implementation @Override protected void notify(int id, Notification notification) {
            notify(null, id, notification);
        }

        @Implementation @Override protected void notify(String tag, int id, Notification notification) {
            attempts++;
            if (Looper.myLooper() != Looper.getMainLooper()) offMainCalls++;
            if (failuresRemaining != 0) {
                if (failuresRemaining > 0) failuresRemaining--;
                throw new ArrayIndexOutOfBoundsException("length=4; index=4");
            }
            super.notify(tag, id, notification);
        }
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