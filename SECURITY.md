# Security policy

## Project status

Deku Satr is an experimental local visual filter, not a guaranteed safety barrier. ML Kit Selfie Segmentation is a beta API and has not been validated for every application screenshot, body size, distance, occlusion, Android version, or device vendor.

The app uses Android MediaProjection with explicit consent and a visible foreground notification. It does not request camera or microphone access, and CI checks that the merged app manifest excludes the INTERNET permission. These controls reduce some risks; they do not prove that every captured frame is masked or that a device cannot leak unblurred image detail.

## Reporting a vulnerability

Please do not publish exploit details, sensitive screenshots, or private screen content in a public issue.

Use GitHub's **private vulnerability reporting** flow from the repository Security tab if it is enabled. If private reporting is not available, open a minimal public issue asking the maintainers to provide a private reporting channel; do not include exploit details until a private channel is established.

Include the affected commit, Android version/OEM, device type, reproduction steps, and relevant logs with all personal screen content removed. Never attach captured screen frames.

## Security boundaries

- MediaProjection requires visible user consent for each session and can be stopped by Android.
- A normal overlay cannot replace another app's compositor output. Overlay composition and touch pass-through are subject to Android platform rules.
- Android 12+ limits untrusted application-overlay pass-through opacity to 0.8. Normal mode therefore mixes some original image signal with blur; it is not perfect concealment.
- If inference fails, the full opaque cover may block interaction with the underlying app until the visible Stop control is pressed.
- Secure windows and DRM content may be unavailable to capture.
- The current segmentation model can miss people. Do not use the app as the sole control for high-risk privacy situations.
- The application must not use Accessibility APIs to read other apps or bypass secure-window / DRM restrictions.

## Release gates

Before a release can be treated as reliable, maintainers must review CodeQL alerts and dependency updates, audit the final APK/merged manifest, test capture revocation and cleanup, and measure false negatives, mask-edge leakage, latency, memory, heat, battery, and overlay feedback on physical devices.
