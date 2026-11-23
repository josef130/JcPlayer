# 2.8.3 (pre-release)

- Add optional per-track JcArtworkProvider for shared MediaSession/notification artwork, configured without starting a player.
- Cache artwork per track, cap custom bitmap dimensions at 256 pixels, and fall back to the existing default when a provider returns null/recycled data or throws an exception.
- Refresh an active session without replacing MediaPlayer, resetting playback or changing its small status icon.
- Keep default artwork and the background autoplay/audio-focus fixes from 2.8.2 unchanged for existing integrations.
# 2.8.2 (pre-release)

- Keep the foreground media service and audio focus active while a completed track is replaced or replayed. Avoid a background foreground-service restart at each track boundary.
- Move automatic queue advancement from JcPlayerView into JcPlayerManager. Continue without an attached Activity or player view, and prevent duplicate advancement from multiple views.
- Preserve host completion callbacks that already stop, replay or select another track; provide an autoAdvance opt-out.
- Unregister detached views and add removeListener for external Activity callbacks without stopping playback.
- Publish a fresh paused status when foreground startup fails, avoiding a stale preparing/loading state.
- Add regression coverage for screen-off playback, destroyed/detached UI, repeat modes, queue completion and user pause.
# 2.8.1 (pre-release)

- Supply a large white music-note placeholder as system media artwork, including the lock screen. Reuse one fixed 256x256 bitmap for notification and session metadata; never decode full-size application artwork on progress updates.
- Use dedicated 24dp vector transport icons with standard glyph padding for Android 12 MediaStyle actions. System button containers and Android 13+ controls remain OS-managed.
- Replace the repeated application name in notification text and media subtitle with the track's fixed total duration. Android's native seek/progress remains available.
- Preserve custom status-bar icons, audio focus handling and foreground playback behavior from 2.8.0.
- Extend regression coverage to Android 12 and Android 14 media metadata, artwork reuse, track changes and duration display.

# 2.8.0 (pre-release)

- Replace custom RemoteViews notifications with AndroidX MediaStyle and an active MediaSessionCompat, so Android controls the media panel appearance.
- Publish track title, duration, position and playback state; support system play, pause, previous, next, seek and stop controls, including the lock screen where device settings allow.
- Run active playback in a mediaPlayback foreground service. Retain the session when the Activity is closed; stop explicitly with the player stop/kill API.
- Request audio focus before playback. Pause on permanent loss; resume after transient loss only if the user has not paused or stopped. Pause on headphone disconnection.
- Serialize playback/progress on the main looper and close raw/asset descriptors after loading.
- Preserve stable small icons and the 2.7.9 notification memory/framework-crash protections; no full-resolution artwork decoding.
- Keep the existing MediaPlayer/JcPlayer API and minSdk 14. Add AndroidX Media 1.6.0 and foreground-service/wake-lock manifest declarations.
- Include an offline sample playlist and 24 automated regression/integration tests. Physical-device verification remains pending when no ADB device is connected.
# 2.7.9 (pre-release)

- Preserve the 2.7.8 notification memory and crash fixes.
- Correct the playing/pause control layout when paused audio is continued.
- Correct newly prepared track controls after pause or stop, even when no onPlaying callback is delivered.
- Add resume and new-track regression tests. Use 2.7.9 instead of 2.7.8.

# 2.7.8 (pre-release)

- Remove full-resolution Bitmap decoding of small notification icons on each progress tick; the custom notification artwork remains resource-based.
- Serialize notification state, channel creation, posting and cancellation on the main looper.
- Coalesce queued progress callbacks and skip unchanged displayed seconds.
- Preserve the 2.7.7 default/custom small-icon behavior, including calls from background threads.
- Handle the reported framework ArraySet ArrayIndexOutOfBoundsException at the notify boundary with one simple-layout retry; if the framework still fails, skip that update instead of crashing playback.
- Cache action PendingIntents and create the notification channel only once per player instance.
- Drop late progress after stop and cancel only the player's notification, preserving unrelated push notifications.
- Request notification permission only on Android 13+ and from a valid Activity, once while a request is pending.

# 2.7.7

- Honor and preserve the selected notification small icon across playback and foreground/background transitions.
