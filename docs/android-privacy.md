# Android capture and privacy notes

## Current implementation

The Android module declares only these permissions:

- `SYSTEM_ALERT_WINDOW` — the user-visible floating control.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` — the visible capture session.
- `POST_NOTIFICATIONS` — so the user can see that capture is running and stop it.

It does **not** request camera, microphone, contacts, location, storage, or network access. It has no internet permission or application server.

After the user approves MediaProjection, Android writes the displayed content to an `ImageReader` surface. The current service only acquires each available `Image`, closes it, and periodically counts samples. It does not access the pixel planes, save screenshot files, upload frames, or run an AI model. The overlay and notification both say that AI blur is inactive.

## Required before inference ships

- Keep captured frames and masks in memory only; do not log pixels or dump bitmaps.
- Do not add `INTERNET` permission for runtime model downloading without a separate design review. Prefer a reviewed model bundled with the release or a clearly explained optional download.
- Keep only one bounded latest frame; drop stale frames rather than queueing a backlog.
- Stop capture and close resources immediately on system revocation, user stop, or unrecoverable processing failure.
- Exclude private content from crash reports and diagnostics. Do not attach screenshots to crash reports.
- Keep the status overlay and notification visible whenever capture is active.
- Never use Accessibility APIs to read other apps' UI or bypass secure capture restrictions.
- Never classify a person's gender. The task is to mask visible human figures, without demographic inference.

## Known limitations

MediaProjection requires explicit user consent for each capture session; it is not an invisible, always-on system hook. The user can stop sharing through Android's system controls. Some apps, `FLAG_SECURE` windows, DRM video, and secure surfaces may not be capturable. Overlay behavior and composition vary by OS version and manufacturer and require physical-device testing.

This build is a capture/overlay proof of concept only. It is not yet a blur tool and must not be used as if it protects content.
