# Roadmap

A planned feature is not an implementation; a CI pass is not evidence of real-device performance.

## M0 — Browser proof of concept

- [x] Local image selection and PNG export.
- [x] Browser-side segmentation and masked blur.
- [x] Boundary dilation and mask unit tests.
- [x] Live camera preview and a conservative obscuring fallback when inference is uncertain.
- [x] PWA manifest, responsive UI, CI, and Pages workflow.
- [ ] Validate the complete flow across Android Chrome, desktop Chromium, Firefox, and Safari.
- [ ] Review exact model artifacts, license terms, and browser-cache/offline behavior.
- [ ] Publish measured quality, latency, and memory results.

## M1 — Choose and validate the production model

- [ ] Benchmark ML Kit Selfie Segmentation as a compact baseline, without assuming selfie quality generalizes to arbitrary screen content.
- [ ] Benchmark at least one compact LiteRT semantic person segmenter; verify class mapping, input/output shape, model provenance, and license.
- [ ] Compare FP32/FP16/INT8 where supported and measure accuracy drift.
- [ ] Build a versioned annotated dataset for multiple, small, distant, partially occluded, and moving people in real app screenshots.
- [ ] Set release gates for person recall, boundary leakage, false masks, p50/p95 latency, peak RAM, sustained FPS, heat, energy, and download/APK size.

## M2 — Native Android (in progress)

- [x] Native Kotlin app module and app launch screen.
- [x] Explicit overlay-access and MediaProjection-consent flow.
- [x] mediaProjection foreground service and persistent stop notification.
- [x] Floating status/stop overlay.
- [x] Downscaled bounded ImageReader capture surface and unit-tested size policy.
- [x] Privacy disclosure; no camera, microphone, storage, or internet permission in the current native module.
- [x] Android CI workflow for unit tests, debug APK build, and artifact upload.
- [ ] Verify the workflow build and install the APK on physical devices.
- [ ] Test overlay visibility/feedback, touch pass-through, rotations, revocation, secure windows, and DRM behavior on several Android versions/OEMs.
- [ ] Implement pixel ingestion with bounded in-memory buffers only after the capture/overlay composition test is passed.
- [ ] Integrate a replaceable person-mask interface and a benchmarked model.
- [ ] Apply blur to the mask, display honest confidence/staleness states, and fall back to "not protected".
- [ ] Measure performance and thermal throttling on low-cost phones.

## M3 — Desktop and browser integration

- [ ] Offline-capable desktop utility for Linux and Windows.
- [ ] Browser extension with explicit per-site permissions and visible enable/disable controls.
- [ ] Evaluate system-wide screen capture separately for platform permission, privacy, and packaging constraints.

## M4 — Release hardening

- [ ] Pin and hash every model artifact; publish model cards and notices.
- [ ] Add malformed-frame, rotation, service-stop, and permission-revocation tests.
- [ ] Add dependency/security scanning and reproducible release checks.
- [ ] Audit network traffic and ensure no media payload leaves the device.
- [ ] Publish tested device/OS matrix and explicit limits; do not claim universal coverage.
