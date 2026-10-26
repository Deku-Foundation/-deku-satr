# Native Android architecture

Updated: 2026-10-26. Version 0.6.3 adds an optional, on-demand face-presentation path above the RTMDet-Ins-Tiny ONNX person-instance segmentation backend. YuNet and FastFace-Large-128 are loaded only for gender-targeted policies, while the ordinary mask-all path does not load face models. Model load is deferred to a foreground service or image worker, and the obsolete RTMDet-S and LR-ASPP cache files are deleted during migration. Hosted CI validates compilation and pinned model artifacts; it cannot prove phone compatibility, accuracy or demographic fairness.

## Implemented on this branch

- Kotlin Android app: min SDK 26, compile/target SDK 36, JDK 17, AGP 9.3.0, Gradle 9.5.0.
- Arabic-first Material 3 UI with Home, Gallery, Editor and Settings destinations, adaptive maximum width, first-run welcome, permission state, inference metrics, and locally persisted effect, strength, sensitivity and mask expansion. Gallery/editor let the user choose mask-all or an optional face-presentation target policy.
- Compose UI/Foundation 1.12.1 (stable) with compileSdk 37.
- Explicit user permission for overlay access and fresh MediaProjection consent for each capture session.
- A mediaProjection foreground service with persistent notification and immediate stop action.
- A touch-pass-through transparent full-screen filter overlay and a separate draggable status/stop panel.
- ONNX Runtime Android 1.31.0 with RTMDet-Ins-Tiny (24,032,356 bytes). Gradle downloads the Apache-2.0 model at immutable revision `e5218e8d47d7c50077f27f9893a28e0ef2ad5656`, checks SHA-256, and includes the LICENSE/NOTICE; there is no runtime weight download.
- Frames are bounded by the selected capture profile, use a two-image ImageReader and one in-flight inference job. Frames are dropped rather than queued while inference is busy. RTMDet-Tiny returns separate person masks, enabling manual per-person selection. The session begins with conservative mask-all behavior.
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

The active model is RTMDet-Ins-Tiny from the pinned ONNX conversion repository. It is approximately 24.03 MB and produces per-instance masks, enabling selection of tracks in the current session. The Android adapter verifies file size and SHA-256, validates the input tensor shape, and rejects unexpected output layouts. The ONNX runtime is CPU-only: the exporter documents CPU execution, so the app no longer attempts NNAPI initialization. The tiny variant was selected over RTMDet-Ins-S to reduce shipped weight size and load pressure; this is not evidence of higher accuracy on phones.

Source: https://huggingface.co/tori29umai/rtmdet-ins-onnx-person-masks

RTMDet-Tiny provides per-person instance masks but does not itself classify face presentation. It may miss small, distant, occluded, stylized, or unusual-content subjects. The person model and face models are pinned and include upstream license texts; nevertheless, model-use terms and privacy disclosures must be reviewed before public release.

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

Superseded in v0.6.2: the former LR-ASPP/LiteRT artifact and dependency were removed from the Android package. The app now packages only the pinned RTMDet-Ins-Tiny instance model and runs it through ONNX Runtime CPU. The APK performs no model network access.

Input/output tensor layouts are inspected at runtime. The implementation normalizes RGB with ImageNet mean/std, letterboxes to the model input, reads the 21-class output and uses VOC person class 15. The output is a shared semantic mask: it cannot tell which mask belongs to which person and cannot infer gender. NNAPI is requested with a three-thread CPU fallback path; actual acceleration varies by device.

Release gates still open:
- Verify the upstream converted model's distribution license/provenance.
- Run labelled multi-person screen/video samples and compare recall, mask leakage and missed-person rate against the previous model.
- Measure p50/p95 latency, peak RAM, sustained throughput, heat and battery on low-, mid- and high-range physical devices.
- Test MediaProjection overlay feedback, orientation changes, safe/DRM windows and stop/revocation.
- Do not advertise 99% accuracy or complete coverage without a representative benchmark.


## Selective targeting and temporal behavior

The live pipeline holds a previous mask only briefly (250 ms) when the fresh semantic result is effectively empty and dimensions still match. This reduces a single-frame dropout without retaining a stale mask for most of a second, which can cause visible trails when people move. It is a trade-off, not optical-flow tracking; test on real devices before claiming flicker-free operation.

RTMDet-Tiny provides per-person masks, while the optional YuNet + FastFace path estimates visual face presentation for target policies. The label is not gender identity. Hair/clothing/body-shape cues are never used. A low-confidence/missing face becomes Unknown and remains blurred; a live label needs two high-confidence scans. Users can override the per-track label during the current session, but the correction does not train weights or save face crops.


## Low-latency profile and startup behavior (0.5.3)

- Low-latency: 360 px long edge, one full-frame inference without tiling, 80 ms minimum admission interval. This is not a guaranteed frame rate and may miss small or distant people.
- Power saver: 480 px without tiling. Balanced: 640 px with tiling on tall/wide frames. Detail: 720 px with tiling and a higher processing cost.
- The decoder skips full softmax only when a mathematical upper bound proves the person probability cannot pass the app's minimum threshold. Pixels that might be accepted retain the original softmax.
- During model warm-up the overlay stays clear and the compact indicator says protection is not active yet. Inference failure or stale output triggers an opaque charcoal fallback; the stop panel remains available.
- Tap the status title to show or hide details; drag it to move the panel. The Stop touch target remains at least 48 dp high.


## Next model candidate: per-instance masks

The active model is RTMDet-Ins-Tiny ONNX (~24.03 MB, pinned under Apache-2.0 per the downloaded license and NOTICE). Its repository publishes a 640×640 BGR input with top-left aspect-preserving resize/padding and separate `dets`, `labels`, and per-instance `masks` outputs. The model's published CPU graph checks are CI smoke tests, not phone latency benchmarks. See `docs/model-evaluation.md`.

RTMDet-Tiny and the optional face models are bundled at build time from immutable revisions after verifying size and SHA-256; license texts are packaged. Release gates remain physical-device startup, sustained memory/thermal tests, labeled screen-frame recall, face-classification subgroup error rates and false-visible-person rate in each gender-targeted mode.

A pure target-policy utility now specifies the mask-composition behavior for an instance backend: mask all by default, mask only the instances the user selected, or mask all except explicit selections. Empty selection and missing selected tracks are handled conservatively so an uninitialized selector does not silently turn off protection. The current semantic model cannot drive this feature reliably, so no misleading targeting toggle is shown in the UI yet.


## Upgrade and model migration (0.6.2–0.6.3)

The model weights in the app-private cache are version-specific. On first start after upgrading to 0.6.2, the segmenter removes cached RTMDet-S and LR-ASPP files before loading RTMDet-Tiny so obsolete weights do not continue consuming storage. SharedPreferences and target settings are preserved; per-person selections remain session-local by design.

Debug APKs are now signed with a fixed development-only signing identity so successive CI builds can update in place. Android requires one clean reinstall when moving from an APK signed with a different key; afterwards, updates from the stable development signer install over the app without clearing its data. This key must never sign a production release.


## Optional face-presentation pipeline (v0.6.3 preview)

1. The user explicitly selects a male-presenting or female-presenting target policy. The extra models remain unloaded for ordinary mask-all use, the Home screen, and general settings.
2. RTMDet-Ins-Tiny returns per-instance person masks and boxes. The face path uses a pinned YuNet ONNX detector and aligns the five facial landmarks before FastFace-Large-128 inference.
3. FastFace's binary output is interpreted as a visual presentation estimate only. Live mode samples at most once per 650 ms and requires two consecutive predictions at or above 0.88. Missing, too-small, low-confidence, unassigned, or inconsistent faces become Unknown; the corresponding person mask remains blurred.
4. The policy mask is composed using instance masks. In male-presenting mode only a high-confidence matching presentation is left visible; female-presenting mode does the converse. The default remains mask-all, and Unknown never becomes visible due to a missing classifier result.
5. The per-person correction controls override the label for the active session only. They do not save face crops, upload images, recognize people across sessions, or retrain model weights. Still-image editing offers the same target choice; when a gender-target image has no usable person masks, it covers the entire image conservatively.
6. Still-image editing is disabled while a live screen-capture session is active so the application does not instantiate a second RTMDet model alongside the screen filter and risk exhausting device RAM.

Pinned face artifacts:
- YuNet model: 232,589 bytes; SHA-256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`; MIT.
- FastFace-Large-128: 4,008,918 bytes; SHA-256 `2834e70884800019972e588fbe95eb73ebe942120a364d8aaa60085ba4f5b9cf`; Apache-2.0.
- Both are loaded on-device through ONNX Runtime CPU. Their published dataset scores are not equivalent to screen-capture accuracy.

There is no 99% accuracy claim. Before release, test recall and false-visible-person rate with labeled held-out screen frames and faces of different sizes, lighting, skin tones, occlusions and camera angles; measure p50/p95 latency, RAM, temperature and battery on actual low-, mid- and high-tier Android devices.
