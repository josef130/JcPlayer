package com.example.jean.jcplayer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import com.example.jean.jcplayer.model.JcAudio;
import com.example.jean.jcplayer.service.JcPlayerService;
import com.example.jean.jcplayer.service.notification.JcNotificationPlayer;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MediaIntegrationTest {
    private static final String FIRST = "https://example.com/jc-first.mp3";
    private static final String SECOND = "https://example.com/jc-second.mp3";
    private Activity activity;
    private JcPlayerService service;
    private ServiceController<JcPlayerService> serviceController;
    private AudioManager audioManager;
    private JcAudio first;
    private JcAudio second;
    private MediaSessionCompat session;
    private MediaSessionCompat.Callback callbacks;

    @Before public void setUp() throws Exception {
        resetSingleton(JcPlayerManager.class);
        resetSingleton(JcNotificationPlayer.class);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        serviceController = Robolectric.buildService(JcPlayerService.class).create();
        service = serviceController.get();
        audioManager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        Shadows.shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Shadows.shadowOf(activity.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName(activity, JcPlayerService.class), service.onBind(new Intent()));
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(FIRST), new ShadowMediaPlayer.MediaInfo(120000, 10));
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(SECOND), new ShadowMediaPlayer.MediaInfo(90000, 10));
        first = JcAudio.createFromURL("First song", FIRST);
        second = JcAudio.createFromURL("Second song", SECOND);
        Field sessionField = JcPlayerService.class.getDeclaredField("mediaSession");
        sessionField.setAccessible(true);
        session = (MediaSessionCompat) sessionField.get(service);
        Field callbackField = JcPlayerService.class.getDeclaredField("sessionCallback");
        callbackField.setAccessible(true);
        callbacks = (MediaSessionCompat.Callback) callbackField.get(service);
    }

    @After public void tearDown() { serviceController.destroy(); }

    private void start() {
        service.play(first);
        idle(20);
        assertTrue(service.isPlaying());
    }
    private void idle(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }
    private Notification notification() {
        return Shadows.shadowOf((NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE)).getNotification(100);
    }

    @Test public void playingUsesForegroundServiceAndSystemMediaStyleWithSessionToken() {
        start();
        assertTrue(session.isActive());
        Notification posted = Shadows.shadowOf(service).getLastForegroundNotification();
        assertNotNull(posted);
        assertEquals(100, Shadows.shadowOf(service).getLastForegroundNotificationId());
        assertEquals("android.app.Notification$MediaStyle", posted.extras.getString("android.template"));
        assertEquals(service.getMediaSessionToken().getToken(), posted.extras.getParcelable("android.mediaSession"));
        assertEquals("First song", posted.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility);
        assertNull(posted.getLargeIcon());
        assertNull(posted.contentView);
    }

    @Test public void sessionCallbacksPauseResumeSeekAndStopTheActualPlayer() {
        start();
        callbacks.onPause();
        assertFalse(service.isPlaying());
        assertTrue(service.isPaused());
        callbacks.onPlay();
        idle(1);
        assertTrue(service.isPlaying());
        callbacks.onSeekTo(5000);
        idle(1);
        assertTrue(service.getMediaPlayer().getCurrentPosition() >= 5000);
        callbacks.onStop();
        assertFalse(service.isPlaying());
        assertFalse(session.isActive());
        assertNull(notification());
        assertNull(service.getMediaPlayer());
    }

    @Test public void skipCallbacksUseTheHostPlaylistWithoutAView() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1);
        manager.playAudio(first); idle(20);
        callbacks.onSkipToNext(); idle(20);
        assertEquals(second, service.getCurrentAudio());
        assertTrue(service.isPlaying());
        callbacks.onSkipToPrevious(); idle(20);
        assertEquals(first, service.getCurrentAudio());
    }

    @Test public void permanentFocusLossPausesAndDoesNotAutoResume() {
        start();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertFalse(service.isPlaying());
        assertTrue(service.isPaused());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(service.isPlaying());
    }

    @Test public void transientFocusLossResumesOnlyIfUserDidNotPause() {
        start();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertFalse(service.isPlaying());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(service.isPlaying());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.pause(first);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(service.isPlaying());
    }

    @Test public void duckRequestsPauseMusicInsteadOfMixing() {
        start();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertFalse(service.isPlaying());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(service.isPlaying());
    }

    @Test public void deniedFocusNeverStartsTheDecoder() {
        Shadows.shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        service.play(first); idle(20);
        assertFalse(service.isPlaying());
        assertNull(service.getMediaPlayer());
        assertTrue(Shadows.shadowOf(service).isForegroundStopped());
    }

    @Test public void focusLossDuringPreparationDoesNotStartAudioWhenPreparationFinishes() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(FIRST), new ShadowMediaPlayer.MediaInfo(120000, 100));
        service.play(first);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        idle(150);
        assertFalse(service.isPlaying());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        idle(1);
        assertTrue(service.isPlaying());
    }

    @Test public void headphoneDisconnectPausesAndDoesNotResumeOnStaleFocusGain() {
        start();
        activity.sendBroadcast(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        idle(1);
        assertFalse(service.isPlaying());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(service.isPlaying());
    }

    @Test public void stopClearsFutureProgressAndReleasesFocus() {
        start();
        service.stop(); idle(2500);
        assertFalse(service.isPlaying());
        assertNull(service.getMediaPlayer());
        assertNull(notification());
        assertNotNull(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    private static void resetSingleton(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("INSTANCE"); field.setAccessible(true); field.set(null, null);
    }
}