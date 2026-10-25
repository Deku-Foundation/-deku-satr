# Native Android architecture

Updated: 2026-10-25. The project now contains an embedded model and a full-screen mask renderer; Android/OEM behavior remains unvalidated outside GitHub-hosted build tests.

## Implemented on this branch

- Kotlin Android app: min SDK 26, compile/target SDK 36, JDK 17, Gradle 8.13.
- Arabic-first responsive Material 3 1.4.0 UI, system light/dark themes, permission state, inference counters, latest inference time, mask coverage, and a blur-strength control.
- Explicit user permission for overlay access and fresh MediaProjection consent for each capture session.
- A mediaProjection foreground service with persistent notification and immediate stop action.
- A touch-pass-through transparent full-screen filter overlay and a separate draggable status/stop panel.
- ML Kit Selfie Segmentation 16.0.0-beta6, bundled at build time; no runtime model download.
- Frames capped at a longest edge of 640 pixels, a two-image ImageReader, a 250 ms inference throttle, and a single in-flight inference task.
- A CPU two-pass box blur clipped to a thresholded/dilated person mask. Failed inference triggers an opaque fail-closed overlay while the user-accessible stop panel remains visible.
- Unit tests for capture scaling, mask thresholding, dilation, and coverage. Android CI builds a debug APK and checks that the merged manifest does not include INTERNET permission.

## Model choice

ML Kit Selfie Segmentation is the first embedded baseline because it is bundled and optimized for person masks, supports stream mode, and has an output-mask API. Google's docs say the bundled dependency adds approximately 4.5 MB and publish 25–65 ms latency for Pixel 4, depending on image size. Those numbers are reference results, not a promise for Satr or lower-end phones.

Source: https://developers.google.com/ml-kit/vision/selfie-segmentation/android

The API is beta, and the underlying model is optimized for person/selfie imagery. It may miss people that are small, distant, occluded, stylized, or part of unusual application content. The intent is to mask people, not classify gender.

## Pipeline and constraints

1. The user explicitly grants overlay access and approves MediaProjection through the Android system prompt.
2. The visible foreground service starts, displays a stop notification, and creates a downscaled virtual display.
3. The ImageReader callback drops frames when another inference is active or the throttle interval has not elapsed.
4. A captured frame is converted to an in-memory bitmap and passed to the bundled segmenter in stream mode.
5. The confidence map is thresholded and dilated. A CPU blur is generated and only masked pixels are drawn through a transparent full-screen overlay.
6. An exception or stale inference causes a solid fail-closed overlay and warning rather than leaving the last successful mask on-screen indefinitely.
7. Stop, MediaProjection revocation, and service teardown release the model, worker thread, display, reader, and overlays.

## Highest-risk open item: overlay feedback

MediaProjection operates on screen content, while Satr draws a separate window above it. The current implementation does **not** prove that the filter overlay is excluded from later captured frames. If the previous blurred mask enters the next frame, segmentation can become temporally unstable, create trails, or miss the original boundary. This must be characterized on physical devices before calling the filter reliable.

The acceptance gate is tracked at https://github.com/Deku-Foundation/deku-satr/issues/2. It includes:
- Whether frames stay usable while the overlay is shown.
- Whether the overlay is recursively captured and how visible feedback behaves.
- Touch pass-through, drag/stop panel, orientation/display changes, and overlay revocation.
- Permission revocation, lock/unlock, secure windows, DRM playback, and resource cleanup.
- Memory, thermal, battery, p50/p95 inference time, and mask quality on low-end phones.

If a device cannot reliably capture or composite the overlay, the UI must clearly show the limitation. Do not bypass secure capture restrictions.

## Build locally

Install JDK 17, Gradle 8.13, Android SDK Platform 36 and accept the SDK licenses:

    gradle --no-daemon -p android testDebugUnitTest assembleDebug

GitHub Actions uploads android/app/build/outputs/apk/debug/app-debug.apk as a development artifact; it is not production-signed.
