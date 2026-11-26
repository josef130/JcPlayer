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
@Config(sdk = {31, 34}, shadows = MediaIntegrationTest.RecordingMediaSession.class)
public class MediaIntegrationTest {
    private static final String FIRST = "https://example.com/jc-first.mp3";
    private static final String SECOND = "https://example.com/jc-second.mp3";
    private Activity activity;
    private org.robolectric.android.controller.ActivityController<Activity> activityController;
    private JcPlayerService service;
    private ServiceController<JcPlayerService> serviceController;
    private AudioManager audioManager;
    private JcAudio first;
    private JcAudio second;
    private MediaSessionCompat session;
    private MediaSessionCompat.Callback callbacks;

    @Before public void setUp() throws Exception {
        JcPlayerManager.setArtworkProvider(null);
        resetSingleton(JcPlayerManager.class);
        resetSingleton(JcNotificationPlayer.class);
        activityController = Robolectric.buildActivity(Activity.class).setup();
        activity = activityController.get();
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

    @After public void tearDown() {
        if (!activity.isDestroyed()) activityController.pause().stop().destroy();
        JcPlayerManager.setArtworkProvider(null);
        serviceController.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void start() {
        service.play(first);
        idle(20);
        assertTrue(service.isPlaying());
    }
    private void idle(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }
    private Notification notification() {
        return Shadows.shadowOf((NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE)).getNotification(100);
    }

    @Test public void filteredQueueNextUsesCurrentTrackAndKeepsTheSessionAfterSameSongSelection() {
        JcAudio third=JcAudio.createFromURL("Third song","https://example.com/jc-third.mp3");
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(third.getPath()),new ShadowMediaPlayer.MediaInfo(90000,10));
        JcPlayerManager manager=JcPlayerManager.getInstance(activity,null,null).get();idle(1);
        manager.setPlaylist(new ArrayList<>(java.util.Arrays.asList(first,second,third)));
        manager.playAudio(second);idle(20);
        android.media.MediaPlayer original=service.getMediaPlayer();
        Object token=service.getMediaSessionToken().getToken();
        JcAudio secondInFavorites=JcAudio.createFromURL(second.getTitle(),second.getPath());secondInFavorites.setPosition(0);
        JcAudio thirdInFavorites=JcAudio.createFromURL(third.getTitle(),third.getPath());thirdInFavorites.setPosition(1);
        manager.setPlaylist(new ArrayList<>(java.util.Arrays.asList(secondInFavorites,thirdInFavorites)));
        manager.playAudio(secondInFavorites);idle(1);
        assertSame(original,service.getMediaPlayer());assertEquals(token,service.getMediaSessionToken().getToken());
        manager.nextAudio();idle(20);
        assertSame(thirdInFavorites,service.getCurrentAudio());assertTrue(service.isPlaying());
    }
    @Test public void filteredQueuePreviousUsesTheNewIndexAfterPausedSongSelection() {
        JcAudio third=JcAudio.createFromURL("Third song","https://example.com/jc-third.mp3");
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(third.getPath()),new ShadowMediaPlayer.MediaInfo(90000,10));
        JcPlayerManager manager=JcPlayerManager.getInstance(activity,null,null).get();idle(1);
        manager.setPlaylist(new ArrayList<>(java.util.Arrays.asList(first,second,third)));
        manager.playAudio(third);idle(20);service.pause(third);
        android.media.MediaPlayer original=service.getMediaPlayer();
        JcAudio firstInFavorites=JcAudio.createFromURL(first.getTitle(),first.getPath());firstInFavorites.setPosition(0);
        JcAudio thirdInFavorites=JcAudio.createFromURL(third.getTitle(),third.getPath());thirdInFavorites.setPosition(1);
        manager.setPlaylist(new ArrayList<>(java.util.Arrays.asList(firstInFavorites,thirdInFavorites)));
        manager.playAudio(thirdInFavorites);idle(1);
        assertSame(original,service.getMediaPlayer());
        manager.previousAudio();idle(1);
        assertSame(firstInFavorites,service.getCurrentAudio());
        manager.nextAudio();idle(20);assertSame(thirdInFavorites,service.getCurrentAudio());
    }    @Test public void playingUsesForegroundServiceAndSystemMediaStyleWithSessionToken() {
        start();
        assertTrue(session.isActive());
        Notification posted = Shadows.shadowOf(service).getLastForegroundNotification();
        assertNotNull(posted);
        assertEquals(100, Shadows.shadowOf(service).getLastForegroundNotificationId());
        assertEquals("android.app.Notification$MediaStyle", posted.extras.getString("android.template"));
        assertEquals(service.getMediaSessionToken().getToken(), posted.extras.getParcelable("android.mediaSession"));
        assertEquals("First song", posted.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility);
        assertNotNull(posted.getLargeIcon());
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

    @Test public void durationReplacesDuplicateApplicationNameAndChangesWithTrackNotPosition() {
        start();
        android.support.v4.media.MediaMetadataCompat metadata = publishedMetadata();
        assertEquals("02:00", metadata.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST));
        assertEquals("02:00", metadata.getDescription().getSubtitle());
        assertEquals("02:00", notification().extras.getCharSequence(Notification.EXTRA_TEXT));
        callbacks.onSeekTo(30000); idle(20);
        callbacks.onPause();
        assertEquals("02:00", notification().extras.getCharSequence(Notification.EXTRA_TEXT));
        service.play(second); idle(20);
        assertEquals("Second song", notification().extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals("01:30", notification().extras.getCharSequence(Notification.EXTRA_TEXT));
        assertEquals("01:30", publishedMetadata().getDescription().getSubtitle());
    }

    @Test public void optionalArtworkChangesWithTrackWithoutWorkOnProgressTicks() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        android.graphics.Bitmap firstCover = android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);
        firstCover.eraseColor(android.graphics.Color.RED);
        android.graphics.Bitmap secondCover = android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888);
        secondCover.eraseColor(android.graphics.Color.BLUE);
        JcPlayerManager.setArtworkProvider((context,audio) -> {
            assertSame(activity.getApplicationContext(),context);
            calls.incrementAndGet();
            return FIRST.equals(audio.getPath()) ? firstCover : secondCover;
        });
        start();
        assertEquals(64, publishedMetadata().getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART).getWidth());
        assertSame(firstCover,com.example.jean.jcplayer.service.notification.JcMediaPresentation.INSTANCE.artwork(activity,first));
        idle(3200);
        assertEquals(1,calls.get());
        service.play(second); idle(20);
        assertEquals(2,calls.get());
        assertEquals(96,publishedMetadata().getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART).getWidth());
        assertSame(secondCover,com.example.jean.jcplayer.service.notification.JcMediaPresentation.INSTANCE.artwork(activity,second));
        assertTrue(((android.graphics.drawable.BitmapDrawable)notification().getLargeIcon().loadDrawable(activity)).getBitmap().getWidth() <= 96);
        assertTrue(service.isPlaying());
    }
    @Test public void artworkProviderRejectsRecycledAndBoundsOversizeAndHandlesFailure() {
        android.graphics.Bitmap huge=android.graphics.Bitmap.createBitmap(800,400,android.graphics.Bitmap.Config.ARGB_8888);
        JcPlayerManager.setArtworkProvider((context,audio)->huge);
        start();
        android.graphics.Bitmap art=publishedMetadata().getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART);
        assertEquals(256,art.getWidth()); assertEquals(128,art.getHeight());
        assertFalse(huge.isRecycled());
        android.graphics.Bitmap recycled=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);
        recycled.recycle();
        JcPlayerManager.setArtworkProvider((context,audio)->recycled);
        service.play(second); idle(20);
        assertEquals(256,publishedMetadata().getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART).getHeight());
        JcPlayerManager.setArtworkProvider((context,audio)->{throw new IllegalStateException("Unavailable cover");});
        service.play(first); idle(20);
        assertTrue(service.isPlaying());
        assertEquals(256,publishedMetadata().getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART).getWidth());
    }
    @Test public void changingProviderRefreshesActiveSessionWithoutRestartingSong() throws Exception {
        JcPlayerManager manager=JcPlayerManager.getInstance(activity,null,null).get();
        idle(1);
        start();
        Field field=JcPlayerService.class.getDeclaredField("mediaPlayer"); field.setAccessible(true);
        Object player=field.get(service);
        android.graphics.Bitmap cover=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);
        JcPlayerManager.setArtworkProvider((context,audio)->cover);
        idle(1);
        assertSame(player,field.get(service));
        assertTrue(service.isPlaying());
        assertSame(first,service.getCurrentAudio());
        assertEquals(64,publishedMetadata().getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART).getWidth());
        assertSame(cover,com.example.jean.jcplayer.service.notification.JcMediaPresentation.INSTANCE.artwork(activity,first));
    }    @Test public void artworkIsSharedBySessionAndNotificationAndSurvivesTrackChanges() {
        start();
        android.graphics.Bitmap artwork = publishedMetadata()
                .getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART);
        assertNotNull(artwork);
        assertEquals(256, artwork.getWidth());
        assertEquals(256, artwork.getHeight());
        assertTrue(Shadows.shadowOf(notification().getLargeIcon()).getBitmap().getWidth() <= 256);
        service.play(second); idle(20);
        assertEquals(artwork.getWidth(), publishedMetadata()
                .getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART).getWidth());
        assertTrue(Shadows.shadowOf(notification().getLargeIcon()).getBitmap().getWidth() <= 256);
        assertEquals(R.drawable.jc_media_previous, notification().actions[0].icon);
        assertEquals(R.drawable.jc_media_pause, notification().actions[1].icon);
        assertEquals(R.drawable.jc_media_next, notification().actions[2].icon);
    }
    @Test public void completionAdvancesWithoutAnyPlayerViewAndKeepsForegroundAndFocus() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1); manager.playAudio(first); idle(20);
        org.robolectric.shadows.ShadowApplication application = Shadows.shadowOf(activity.getApplication());
        while (application.getNextStartedService() != null) { }
        activityController.pause().stop().destroy();
        Shadows.shadowOf((android.os.PowerManager) service.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        android.media.MediaPlayer completed = service.getMediaPlayer();
        service.onCompletion(completed); idle(20);
        assertEquals("Queue advancement must not need a JcPlayerView", second, service.getCurrentAudio());
        assertTrue(service.isPlaying());
        assertFalse("Foreground state must survive the track boundary", Shadows.shadowOf(service).isForegroundStopped());
        assertNull("Do not restart a foreground service from a background track boundary", application.getNextStartedService());
        assertNull("Continuous playback must retain audio focus", Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    @Test public void completionWithTwoRegisteredViewsMustNotSkipOrStopTheFollowingTrack() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        com.example.jean.jcplayer.view.JcPlayerView original = new com.example.jean.jcplayer.view.JcPlayerView(activity);
        com.example.jean.jcplayer.view.JcPlayerView recreated = new com.example.jean.jcplayer.view.JcPlayerView(activity);
        original.initPlaylist(tracks, null); recreated.initPlaylist(tracks, null);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, null, null).get();
        idle(1); manager.playAudio(first); idle(20);
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertEquals(second, service.getCurrentAudio());
        assertTrue(service.isPlaying());
    }
    @Test public void completedQueueStopsAndRemovesNotificationInsteadOfLoadingForever() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1); manager.playAudio(first); idle(20);
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertNull(service.getCurrentAudio());
        assertFalse(service.isPlaying());
        assertNull(notification());
        assertNull(manager.getCurrentStatus().getJcAudio());
        assertNotNull(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    @Test public void repeatQueueContinuesAcrossTheLastTrackWithoutForegroundRestart() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1); manager.activeRepeat(); manager.playAudio(second); idle(20);
        org.robolectric.shadows.ShadowApplication application = Shadows.shadowOf(activity.getApplication());
        while (application.getNextStartedService() != null) { }
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertEquals(first, service.getCurrentAudio());
        assertTrue(service.isPlaying());
        assertNull(application.getNextStartedService());
        assertNull(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    @Test public void repeatOneReplaysOnceAndRetainsTheExistingForegroundService() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1); manager.activeRepeat(); manager.activeRepeat(); manager.playAudio(first); idle(20);
        android.media.MediaPlayer original = service.getMediaPlayer();
        org.robolectric.shadows.ShadowApplication application = Shadows.shadowOf(activity.getApplication());
        while (application.getNextStartedService() != null) { }
        service.onCompletion(original); idle(20);
        assertSame(original, service.getMediaPlayer());
        assertNull("Repeat-one seeking must not demote/restart foreground playback", application.getNextStartedService());
        assertEquals(first, service.getCurrentAudio());
        assertTrue(service.isPlaying());
        assertTrue(service.getMediaPlayer().getCurrentPosition() < 1000);
        assertNull(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    @Test public void explicitCompletionSelectionIsNotAdvancedASecondTime() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1);
        manager.setJcPlayerManagerListener(new ListenerAdapter() {
            @Override public void onCompletedAudio() { manager.playAudio(second); }
        });
        manager.playAudio(first); idle(20);
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertEquals(second, service.getCurrentAudio());
        assertTrue(service.isPlaying());
    }

    @Test public void lateCompletionAfterUserPauseNeverStartsAnotherTrack() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1); manager.playAudio(first); idle(20);
        service.pause(first);
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertEquals(first, service.getCurrentAudio());
        assertFalse(service.isPlaying());
        assertEquals(first, manager.getCurrentStatus().getJcAudio());
        assertTrue(Shadows.shadowOf(service).isForegroundStopped());
    }

    @Test public void disablingAutomaticAdvancementPublishesPausedStateAtTheEnd() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, tracks, null).get();
        idle(1); manager.setAutoAdvance(false); manager.playAudio(first); idle(20);
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertEquals(first, service.getCurrentAudio());
        assertFalse(service.isPlaying());
        assertEquals(com.example.jean.jcplayer.general.JcStatus.PlayState.PAUSE, manager.getCurrentStatus().getPlayState());
        assertNotNull(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest());
    }

    @Test public void detachedViewAndRemovedActivityListenerDoNotOwnQueuePlayback() {
        ArrayList<JcAudio> tracks = new ArrayList<>(); tracks.add(first); tracks.add(second);
        int[] updates = {0};
        ListenerAdapter listener = new ListenerAdapter() {
            @Override public void onPreparedAudio(com.example.jean.jcplayer.general.JcStatus status) { updates[0]++; }
        };
        com.example.jean.jcplayer.view.JcPlayerView view = new com.example.jean.jcplayer.view.JcPlayerView(activity);
        view.initPlaylist(tracks, listener); activity.setContentView(view);
        JcPlayerManager manager = JcPlayerManager.getInstance(activity, null, null).get();
        idle(1); manager.playAudio(first); idle(20);
        manager.removeListener(listener);
        activityController.pause().stop().destroy();
        service.onCompletion(service.getMediaPlayer()); idle(20);
        assertEquals(second, service.getCurrentAudio());
        assertTrue(service.isPlaying());
        assertEquals(1, updates[0]);
    }

    private static class ListenerAdapter implements JcPlayerManagerListener {
        public void onPreparedAudio(com.example.jean.jcplayer.general.JcStatus status) { }
        public void onCompletedAudio() { }
        public void onPaused(com.example.jean.jcplayer.general.JcStatus status) { }
        public void onContinueAudio(com.example.jean.jcplayer.general.JcStatus status) { }
        public void onPlaying(com.example.jean.jcplayer.general.JcStatus status) { }
        public void onTimeChanged(com.example.jean.jcplayer.general.JcStatus status) { }
        public void onStopped(com.example.jean.jcplayer.general.JcStatus status) { }
        public void onJcpError(Throwable error) { }
    }
    // Robolectric does not forward session metadata through its controller Binder.
    // Capture the actual framework publication instead of replacing production code.
    private android.support.v4.media.MediaMetadataCompat publishedMetadata() {
        RecordingMediaSession shadow = org.robolectric.shadow.api.Shadow.extract(session.getMediaSession());
        return android.support.v4.media.MediaMetadataCompat.fromMediaMetadata(shadow.metadata);
    }

    @org.robolectric.annotation.Implements(android.media.session.MediaSession.class)
    public static class RecordingMediaSession extends org.robolectric.shadows.ShadowMediaSession {
        android.media.MediaMetadata metadata;
        @org.robolectric.annotation.Implementation
        protected void setMetadata(android.media.MediaMetadata value) { metadata = value; }
    }
    private static void resetSingleton(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("INSTANCE"); field.setAccessible(true); field.set(null, null);
    }
}
