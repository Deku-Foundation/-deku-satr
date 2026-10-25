# Native Android architecture

Updated: 2026-10-25. Version 0.5.1 switches the live and still-image paths to a pinned LiteRT/TFLite LR-ASPP + MobileNetV3-Large semantic person segmenter. This is an experimental model integration; Android/OEM behavior remains unvalidated outside hosted CI. Android/OEM behavior remains unvalidated outside GitHub-hosted build tests.

## Implemented on this branch

- Kotlin Android app: min SDK 26, compile/target SDK 36, JDK 17, AGP 9.3.0, Gradle 9.5.0.
- Arabic-first Material 3 UI with Home/Settings destinations, adaptive maximum width, permission state, inference metrics, and locally persisted effect, strength, sensitivity and mask expansion.
- Compose UI/Foundation 1.12.1 (stable) with compileSdk 37.
- Explicit user permission for overlay access and fresh MediaProjection consent for each capture session.
- A mediaProjection foreground service with persistent notification and immediate stop action.
- A touch-pass-through transparent full-screen filter overlay and a separate draggable status/stop panel.
- LiteRT/TFLite Interpreter 1.4.2 with LR-ASPP + MobileNetV3-Large dynamic weight-only INT8 weights and FP32 activations. Gradle downloads the model from an immutable Hugging Face revision and verifies SHA-256 before packaging; there is no runtime model download.
- Frames are bounded by the selected capture profile, use a two-image ImageReader and one in-flight inference job. Frames are dropped rather than queued while inference is busy. LR-ASPP returns a semantic 21-class tensor; VOC class index 15 is treated as person and converted to a person confidence map.
- A CPU two-pass box blur, block pixelation or solid cover clipped to a thresholded/dilated person mask. Replaced bitmaps are released after a short delay to avoid recycling during rendering. Failed inference triggers an opaque fail-closed overlay while the user-accessible stop panel remains visible.
- Unit tests for capture scaling, mask thresholding, dilation, and coverage. Android CI builds a debug APK and checks that the merged manifest does not include INTERNET permission.

## Why this dependency set

Compose 1.12.1 was considered but requires compileSdk 37 and AGP 9.2+; the Android SDK setup on the current CI runner did not provide the API 37 platform package. Compose 1.12 requires compileSdk 37, so the branch now installs API 37 in CI and uses AGP 9.3.0 with Gradle 9.5.0.

Official release notes:
- Compose UI 1.12.1: https://developer.android.com/jetpack/androidx/releases/compose-ui
- Compose Foundation 1.12.1: https://developer.android.com/jetpack/androidx/releases/compose-foundation
- Material 3 1.4.0: https://developer.android.com/jetpack/androidx/releases/compose-material3
- AGP 8.13.2: https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes

## Model choice

The active candidate is LiteRT/TFLite LR-ASPP + MobileNetV3-Large, sourced from the LiteRT Community conversion of TorchVision's LR-ASPP model. The dynamic weight-only INT8 artifact is approximately 3.52 MB and is pinned by revision and SHA-256. The implementation introspects input/output tensor layouts and rejects unexpected shapes rather than guessing. These facts do not establish accuracy or speed on Satr's captured frames.

Source: https://huggingface.co/litert-community/lraspp_mobilenet_v3_large

The model is semantic segmentation, not per-person instance segmentation and not a gender classifier. It may miss small, distant, occluded, stylized, or unusual-content subjects. The converted model card does not declare an explicit license, so legal review is required before any public release; this branch is an evaluation integration, not release approval.

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

Install JDK 17, Gradle 9.5.0, Android SDK Platform 37 and accept the SDK licenses:

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


## Adaptive layout and accessibility

The dashboard is centered and constrained to a maximum content width of 680 dp so cards remain readable on tablets, foldables, and desktop/freeform windows instead of stretching across the entire display. On narrow phones it continues to fill the available width and scroll vertically. The floating Stop control has a minimum 48 dp height and 112 dp width so the emergency action remains easier to tap.

The main screen uses Compose Material 3 components and system light/dark theme selection. Before a production release, verify TalkBack labels and focus order, large font scaling, contrast, RTL layout, keyboard/mouse interaction, landscape, split-screen, and foldable posture on physical or virtual devices. A UI build passing does not replace these checks.

Official guidance:
- Compose accessibility defaults and 48 dp minimum touch targets: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- Compose semantics for screen readers and testing: https://developer.android.com/develop/ui/compose/accessibility/semantics
- Adaptive app layouts and window size classes: https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps
- Material 3 release notes: https://developer.android.com/jetpack/androidx/releases/compose-material3

The dashboard's width constraint is a deliberate first adaptive step, not a claim that the app already has a complete multi-pane tablet layout.


## UX/performance changes in 0.4.0
- Home contains only session status and Start/Stop. Filter controls and permission details moved to Settings.
- Notification permission no longer blocks MediaProjection startup. Android still requires a foreground-service notification; its drawer visibility follows Android permission rules. The app/overlay Stop control remains available.
- Compose metric updates and floating-panel metrics are rate-limited to 900 ms; phase/error changes stay immediate. The floating status panel no longer invalidates the full-screen mask surface a second time.
- A five-point black-pixel heuristic was removed because dark app UI is legitimate. Actual capture errors and a longer stale-result watchdog remain.
- The service uses ML Kit raw-size confidence masks, user-adjustable threshold/expansion, and multi-frame status hysteresis. This reduces redundant resizing and status oscillation, but does not prove overlay feedback is solved.

## Build toolchain and catalog
Android Gradle Plugin 9.3.0 pairs with Gradle 9.5.0 and JDK 17. Versions are centralized in android/gradle/libs.versions.toml. The project temporarily opts out of AGP's built-in Kotlin and new DSL while keeping the standalone Kotlin/Compose plugin path; remove these compatibility flags only after dedicated migration testing.

Official sources:
- https://developer.android.com/build/releases/agp-9-3-0-release-notes
- https://developer.android.com/build/releases/about-agp
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://kotlinlang.org/docs/gradle-configure-project.html
- https://developers.google.com/ml-kit/vision/selfie-segmentation/android


### Compose update (2026-10-25)
The stable Compose UI and Foundation version is now pinned to 1.12.1 and the compile SDK is 37, as required by the Compose 1.12 line. CI installs Android platform 37 and Build Tools 36.0.0. targetSdk is deliberately unchanged at 36 to keep runtime behavior changes separate from the build toolchain migration.

References:
- https://developer.android.com/jetpack/androidx/releases/compose-ui
- https://developer.android.com/jetpack/androidx/releases/compose-foundation
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler

## Satr Blur visual identity and live-mask stability

The native UI uses the supplied product palette: deep green `#12372A`, leaf green `#1F7A58`, warm gold `#D9B45A`, and pale green-white `#F4F7F3`. The mihrab/descending-layer mark is implemented as an Android vector drawable so it remains sharp at launcher and UI sizes. Product identity is “ستر بلور / Satr Blur”; Deku Foundation is credited separately rather than being conflated with the app name.

The previous build workflow requested `platforms;android-37`, which the configured SDK repository did not provide and caused CI to stop before Gradle ran. The build now targets the available stable Android 36 platform while using AGP 9.3.0 and Gradle 9.5.0, the documented compatibility pair. Revisit compileSdk 37 when that platform package is available in the CI SDK repository.

For live video, the renderer reuses the last non-empty confidence mask for at most 650 ms when dimensions match and a fresh mask temporarily collapses to background. The cache resets on session start and display resize. Replaced bitmaps are released after a short delay rather than recycled immediately after invalidation. These changes reduce transient mask flicker but are not temporal tracking and do not prove that the separate overlay is excluded from MediaProjection; validate capture feedback and mask stability on physical devices.

References:
- AGP 9.3 compatibility table: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- AGP-to-Gradle compatibility table: https://developer.android.com/build/releases/about-agp
- ML Kit stream mode, raw masks and frame throttling: https://developers.google.com/ml-kit/vision/selfie-segmentation/android
- Android touch-target guidance: https://developer.android.com/develop/ui/compose/accessibility/api-defaults


## Version 0.5.1 model migration

The active Android model is LR-ASPP + MobileNetV3-Large through LiteRT/TFLite Interpreter 1.4.2. The build fetches the dynamic weight-only INT8 / FP32-activation artifact from immutable revision `ac7d12fd05b7542cd884c85968b3193c8f8e4fa7`, verifies SHA-256 `1a0a31a925f1c8e2abb76e96c4bd4bdcbc625f8389f1029530007fded4ea9ed9`, and packages it into generated APK assets. The app itself performs no model network access.

Input/output tensor layouts are inspected at runtime. The implementation normalizes RGB with ImageNet mean/std, letterboxes to the model input, reads the 21-class output and uses VOC person class 15. The output is a shared semantic mask: it cannot tell which mask belongs to which person and cannot infer gender. NNAPI is requested with a three-thread CPU fallback path; actual acceleration varies by device.

Release gates still open:
- Verify the upstream converted model's distribution license/provenance.
- Run labelled multi-person screen/video samples and compare recall, mask leakage and missed-person rate against the previous model.
- Measure p50/p95 latency, peak RAM, sustained throughput, heat and battery on low-, mid- and high-range physical devices.
- Test MediaProjection overlay feedback, orientation changes, safe/DRM windows and stop/revocation.
- Do not advertise 99% accuracy or complete coverage without a representative benchmark.


## Selective targeting and temporal behavior

The live pipeline holds a previous mask only briefly (160 ms) when the fresh semantic result is effectively empty and dimensions still match. This reduces a single-frame dropout without retaining a stale mask for most of a second, which can cause visible trails when people move. It is a trade-off, not optical-flow tracking; test on real devices before claiming flicker-free operation.

The current LR-ASPP output is a single semantic mask for the person class. It cannot provide per-person controls or gender categories. Selective policies require a separately licensed instance-segmentation backend and explicit user-selected target labels; visual gender inference from hair/clothing is not implemented. See the model evaluation decision and its licensing notes.
