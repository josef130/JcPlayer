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