# Roadmap

A checked box means code or documentation has been added; it does not mean device-level quality has been proven. A CI pass is not evidence of accurate screen-wide masking.

## M0 — Browser proof of concept

- [x] Local image selection and PNG export.
- [x] Browser-side segmentation and masked blur.
- [x] Boundary dilation and mask unit tests.
- [x] Live camera preview with an obscuring fallback on inference errors.
- [x] PWA manifest, responsive UI, CI, and Pages workflow.
- [ ] Validate complete flows across Android Chrome, desktop Chromium, Firefox, and Safari.
- [ ] Review exact browser model artifacts, license terms, and cache/offline behavior.
- [ ] Publish measured quality, latency, and memory results.

## M1 — Model evaluation

- [x] Integrate the bundled ML Kit Selfie Segmentation beta as the first small offline Android baseline.
- [x] Use stream mode with a raw-size confidence mask mapped to the reduced screen frame.
- [x] Add confidence thresholding and mask dilation tests.
- [ ] Benchmark the current model against real app screenshots with small, distant, occluded, and moving people.
- [ ] Benchmark the active RTMDet-Ins-Tiny instance-segmentation model on a labelled phone-screen dataset; compare recall, boundary leakage, p50/p95 latency, peak RAM, sustained heat and battery on low-, mid- and high-tier Android devices.
- [ ] Record license/model provenance, p50/p95 latency, recall, boundary leakage, false-positive area, RAM, heat, battery, and APK size.
- [ ] Decide whether a general semantic model is required before any public release.

## M2 — Native Android

- [x] Native Kotlin module and Arabic-first Material 3 dashboard with light/dark themes.
- [x] Explicit MediaProjection consent and overlay permission flow.
- [x] Visible foreground service and persistent notification with a stop action.
- [x] Draggable floating stop panel and touch-pass-through filter overlay.
- [x] Bundled ML Kit model processing captured frames in memory.
- [x] 640 px longest-edge cap, bounded ImageReader, inference throttling, and stale-frame watchdog.
- [x] Blurred mask rendering plus a fail-closed opaque overlay on inference/capture errors.
- [x] Separate Home/Settings navigation, persisted blur strength, confidence threshold, mask expansion, and inference metrics.
- [x] Android unit tests and CI audit that rejects a merged INTERNET permission.
- [x] CodeQL workflow for Kotlin/Java and JavaScript/TypeScript.
- [x] Dependabot weekly updates for GitHub Actions, npm, and Gradle dependencies.
- [x] Security policy and vulnerability-reporting guidance.
- [ ] Test installation and the capture/overlay composition loop on actual phones and Android versions.
- [ ] Confirm whether the overlay re-enters MediaProjection frames and characterize any temporal feedback/ghosting.
- [x] Request the default display (not one app window) on Android 14+ while retaining the system consent prompt.
- [x] Resize/replace the ImageReader surface on MediaProjection size-change callbacks and invalidate old inference generations.
- [x] Use maximumWindowMetrics on Android 11+ and explicitly declare the activity resizable.
- [x] Add a DisplayManager size-change fallback for Android 8–13, and unregister it on teardown.
- [ ] Finish system UI inset and OEM-specific orientation testing on physical devices.
- [x] Cap normal filter overlay opacity to Android's 0.8 pass-through limit and switch to opaque cover only in fail-closed mode.
- [ ] Expand tests for the renderer, resource teardown, permission revocation, and stale frames.
- [ ] Measure battery/thermal performance on low-end phones and tune the sampling interval.
- [ ] Publish a tested device/OS matrix; mark unsupported or secure surfaces visibly.

## M3 — Desktop and browser integration

- [ ] Offline-capable desktop utility for Linux and Windows.
- [ ] Browser extension with explicit per-site permissions and visible enable/disable controls.
- [ ] Evaluate any system-wide capture separately for platform permission and privacy boundaries.

## M4 — Release hardening

- [ ] Pin all model/runtime versions and publish license/provenance notices.
- [ ] Add dependency scanning, release signing, and reproducible release checks.
- [ ] Audit network traffic and verify no media payload leaves the device.
- [ ] Publish limitations and do not claim universal coverage.


## M2.1 — UX, performance and toolchain
- [x] Separate Home/Settings navigation and persisted blur, sensitivity and mask expansion controls.
- [x] Do not block capture startup when POST_NOTIFICATIONS is denied.
- [x] Coalesce Compose metrics and floating-panel text updates.
- [x] Remove the five-pixel black-frame heuristic that misclassified dark UI.
- [x] Use ML Kit raw-size masks and confidence threshold controls.
- [x] Centralize Android dependency pins in a Gradle version catalog.
- [ ] Verify install, overlay behavior and battery/thermal performance on physical low-end and high-density devices.


## Current Android model path (v0.6.2)

- The only bundled model is RTMDet-Ins-Tiny ONNX (~24.03 MB), pinned to an immutable revision and verified by SHA-256 during Gradle builds.
- The previous LR-ASPP/LiteRT semantic model and the larger RTMDet-Ins-S model were removed from the shipped APK. RTMDet runs via ONNX Runtime CPU, two intra-op threads; NNAPI startup was removed to reduce device-specific initialization risk.
- Per-person target correction is manual and session-local. Gender-targeted policies remain blocked on a separate face detector/alignment + classifier evaluation, an explicit Unknown state, and user correction tests. Hair/clothing heuristics are not acceptable substitutes.
- Debug APKs use a fixed development-only signing identity for in-place CI upgrades. One clean reinstall may be required when transitioning from an older artifact signed with a different key; never use this key for a production release.
