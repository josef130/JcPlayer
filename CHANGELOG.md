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