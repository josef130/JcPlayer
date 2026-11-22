# 2.7.7

- Honor the custom notification icon passed to JcPlayerView and JcPlayerManager.
- Use default_icon for the default small status-bar icon.
- Preserve the selected icon across playback callbacks, time updates, notification
  refreshes, and repeated no-argument createNotification calls.
- Remove forced ic_stat_onesignal_default references from notification creation.
- Keep notification artwork unchanged and retain the existing custom-icon API.
- Add notification regression tests covering default/custom icons and background return.