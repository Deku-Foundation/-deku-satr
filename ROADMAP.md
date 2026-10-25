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
- [x] Use streaming mode and an output mask that is resized to the captured frame.
- [x] Add confidence thresholding and mask dilation tests.
- [ ] Benchmark the current model against real app screenshots with small, distant, occluded, and moving people.
- [ ] Compare a compact LiteRT semantic person segmenter on the same labelled dataset.
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
- [x] User-adjustable blur strength and inference/frame metrics in the UI.
- [x] Android unit tests and CI audit that rejects a merged INTERNET permission.
- [ ] Test installation and the capture/overlay composition loop on actual phones and Android versions.
- [ ] Confirm whether the overlay re-enters MediaProjection frames and characterize any temporal feedback/ghosting.
- [ ] Handle rotation/display-size changes and system UI/window insets throughout the session.
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
