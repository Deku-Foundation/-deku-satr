# Native Android architecture

Updated: 2026-10-25. This is an implementation note for the Android foundation, not evidence of production masking.

## What is implemented on this branch

- A native Kotlin Android app (`android/`), with `minSdk 26`, `compileSdk/targetSdk 36`, JDK 17, and Gradle 8.13.
- Explicit user permission flow for overlay access and every MediaProjection session.
- A `mediaProjection` foreground service and persistent notification with an immediate stop action.
- A small `TYPE_APPLICATION_OVERLAY` status panel with its own stop button.
- A downscaled capture surface (long edge at most 960 px) and a bounded `ImageReader`.
- Unit tests for the capture-size policy and a GitHub Actions job that tests and packages a debug APK.

The current frame listener acquires and closes frames without reading pixel planes. There is **no person detector and no blur output yet**. This is deliberate; the app labels itself as not protecting the screen.

## Intended pipeline

1. The user opens Satr and grants overlay access from Android Settings.
2. The user taps Start and approves the system MediaProjection prompt for that session.
3. A foreground service starts, displays a persistent notification, and establishes a downscaled virtual display.
4. A future `PersonMaskEngine` consumes selected in-memory frames. It must report mask confidence, latency, and failure reasons.
5. A renderer may apply blur only after the screen-capture/overlay composition loop has been proven on target devices. The app must never show a “protected” state merely because a frame was captured.
6. Stop, revoked projection, service failure, screen lock, or app shutdown must release the display, reader, worker thread, and overlay.

## Model candidates

### ML Kit Selfie Segmentation — benchmark candidate only

Google documents an Android beta API, an app-size increase of about 4.5 MB, streaming mode, and raw-size masks. Its guide reports latency of roughly 25–65 ms on a Pixel 4 depending on input size. It can support multiple people, but selfie/person segmentation is not proof that tiny or distant people embedded in arbitrary app screenshots will be found. Benchmark it with our own screen-capture test set before integrating it.

Source: https://developers.google.com/ml-kit/vision/selfie-segmentation/android

### LiteRT CompiledModel — likely runtime direction

Google's current LiteRT 2.x docs recommend the `CompiledModel` API for new high-performance on-device inference, with hardware-specific acceleration where available. Any selected model still needs a compatible input/output adapter and a reliable CPU path; the runtime alone does not make a model small or accurate.

Sources:
- https://developers.google.com/edge/litert/overview
- https://developers.google.com/edge/litert/android

### Model shape

Evaluate a compact semantic `person` segmenter before instance segmentation. A single union mask is enough for the first blur effect and usually cheaper than keeping a separate instance mask per person. Quantized INT8 and reduced input resolution are candidates, not assumed wins: compare person recall, edge leakage, false-positive area, p95 latency, memory, device temperature, energy, and APK/model size on the same devices.

Do not ship the existing browser prototype's SegFormer weights in Android by default. The license of the upstream model artifact still needs review and the browser baseline is not an established low-end mobile model.

## Hard engineering gate: overlay feedback

A normal app cannot replace another app's compositor pixels. This design can only place a separate overlay above them. Therefore, before implementing blur patches, test whether MediaProjection captures Satr's own overlay on every target Android/device combination. If the overlay enters the next inference frame, repeated blur can feed back into the mask and produce ghosting or unstable output.

The prototype must prove:
- the capture source remains usable while the status overlay is visible;
- the overlay is not recursively processed, or a tested composition strategy avoids feedback;
- taps still reach the underlying app outside the controls;
- rotations, display resizing, permission revocation, secure windows, DRM playback, and lock/unlock fail visibly and release resources.

If any of these conditions is not achievable on a device, show “not protected” and document the limitation. Do not advertise universal support for every app.

## Android platform constraints

- Android 14+ requires a fresh user approval for each MediaProjection capture session, and a projection instance can create only one virtual display.
- Android 14+ requires the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission and matching service type.
- Android 15+ prevents starting a media-projection foreground service from `BOOT_COMPLETED`; Satr must not silently start screen capture after reboot.
- `TYPE_APPLICATION_OVERLAY` requires special user-granted overlay access and can be repositioned or hidden by the system.
- Secure/DRM content can be blacked out or unavailable to capture. Satr must not claim such content is protected.

Sources:
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/about/versions/14/behavior-changes-14
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams

## Build locally

Install JDK 17, Android SDK Platform 36, and Gradle 8.13; accept the Android SDK licenses first.

```sh
gradle --no-daemon -p android testDebugUnitTest assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the same tests and build and uploads `app-debug.apk` as a workflow artifact. The APK is a development build and is not signed for production distribution.
