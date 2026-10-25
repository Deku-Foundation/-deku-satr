# Native Android architecture

Updated: 2026-10-25. This branch contains an embedded segmentation baseline and a full-screen mask renderer; Android/OEM behavior remains unvalidated outside GitHub-hosted build tests.

## Implemented on this branch

- Kotlin Android app: min SDK 26, compile/target SDK 36, JDK 17, AGP 8.13.2, Gradle 8.13.
- Arabic-first responsive Material 3 1.4.0 UI, system light/dark themes, permission state, inference counters, latest inference time, mask coverage, and a blur-strength control.
- Compose UI/Foundation 1.11.4, the newest stable series compatible with the stable Android API 36 toolchain used here.
- Explicit user permission for overlay access and fresh MediaProjection consent for each capture session.
- A mediaProjection foreground service with persistent notification and immediate stop action.
- A touch-pass-through transparent full-screen filter overlay and a separate draggable status/stop panel.
- ML Kit Selfie Segmentation 16.0.0-beta6, bundled at build time; no runtime model download.
- Frames capped at a longest edge of 640 pixels, a two-image ImageReader, a 250 ms inference throttle, and a single in-flight inference task.
- A CPU two-pass box blur clipped to a thresholded/dilated person mask. Failed inference triggers an opaque fail-closed overlay while the user-accessible stop panel remains visible.
- Unit tests for capture scaling, mask thresholding, dilation, and coverage. Android CI builds a debug APK and checks that the merged manifest does not include INTERNET permission.

## Why this dependency set

Compose 1.12.1 was considered but requires compileSdk 37 and AGP 9.2+; the Android SDK setup on the current CI runner did not provide the API 37 platform package. Rather than depend on an unavailable/preview SDK, this branch uses the latest stable Compose 1.11.4 release with API 36 and AGP 8.13.2.

Official release notes:
- Compose UI 1.11.4: https://developer.android.com/jetpack/androidx/releases/compose-ui
- Compose Foundation 1.11.4: https://developer.android.com/jetpack/androidx/releases/compose-foundation
- Material 3 1.4.0: https://developer.android.com/jetpack/androidx/releases/compose-material3
- AGP 8.13.2: https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes

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


## Touch handling and opacity

The filter surface is a full-screen, non-touchable application overlay. Android 12+ blocks touches passing through untrusted overlays when the window's combined obscuring opacity exceeds 0.8. The normal filter layer now uses 0.8 alpha to preserve tap pass-through outside the controls. That also means the result is composited over the original screen with some residual original signal, so this is a visual blur experiment rather than guaranteed concealment. If a frame cannot be processed, the layer changes to fully opaque fail-closed mode; the user must use the visible stop panel to resume interaction with the underlying app.

Source: https://developer.android.com/reference/android/view/WindowManager.LayoutParams


## Whole-display capture and rotation

On Android 14+ the app requests `MediaProjectionConfig.createConfigForDefaultDisplay()`, preserving the system consent prompt while restricting the requested capture to the entire default display instead of a single app window. This fits the across-app goal; it does not bypass consent. Device manufacturers may override this configuration, so device validation remains required.

The service handles `onCapturedContentResize()` by downscaling new dimensions, invalidating old in-flight inference generations, creating a new bounded ImageReader surface, resizing the existing virtual display, and switching the surface. If resizing fails, the service shows the fail-closed warning and stops. The activity is resizable and uses `maximumWindowMetrics` on Android 11+; legacy display metrics are used only below API 30.

Source: https://developer.android.com/media/grow/media-projection


On Android 8–13, where `MediaProjection.Callback.onCapturedContentResize()` is not available, a `DisplayManager.DisplayListener` watches the default display and asks the service to resize the existing virtual display and replace the ImageReader surface. The listener is unregistered during service teardown. Both pathways invalidate older inference generations to avoid painting a mask based on the previous orientation.
