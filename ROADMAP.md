# Roadmap

A planned feature is not an implementation; a CI pass is not evidence of real-device performance.

## M0 — Browser proof of concept
- [x] Local image selection and PNG export.
- [x] Browser-side person segmentation and masked blur.
- [x] Boundary dilation and mask unit tests.
- [x] Live camera preview with sequential inference and a solid-frame fallback when the mask is uncertain.
- [x] PWA manifest, responsive UI, and CI / Pages workflow.
- [ ] Validate full flow on Android Chrome, desktop Chromium, Firefox and Safari.
- [ ] Audit model license terms, exact downloaded artifacts, and browser-cache/offline behaviour.
- [ ] Publish measured quality, latency, and memory results.

## M1 — Select the production model
- [ ] Benchmark a compact semantic-person segmenter and an instance-segmentation option.
- [ ] Record checkpoint revision/checksum, license, dataset provenance, preprocessing, I/O shapes, and decoder.
- [ ] Evaluate INT8 / FP16 quantization quality drift.
- [ ] Build an annotated test set for multiple people, distance, occlusion, lighting, clothing, varied skin tones, and motion.
- [ ] Set measurable gates for recall, boundary leakage, false masks, latency, RAM, thermal impact, and battery draw.

## M2 — Native Android
- [ ] Kotlin + CameraX app using LiteRT CompiledModel.
- [ ] CPU-only fallback and opportunistic GPU/NPU acceleration.
- [ ] No microphone permission, no background camera, and no persistent frame storage.
- [ ] Thermal-aware frame scheduler and visible stale-frame indicator.
- [ ] Test on low-cost devices, not just flagships.

## M3 — Desktop and browser integration
- [ ] Offline-capable desktop utility for Linux and Windows.
- [ ] Browser extension with explicit per-site permissions and visible enable/disable controls.
- [ ] Evaluate any system-wide screen capture separately for privacy, platform permission, and distribution-policy constraints.

## M4 — Release hardening
- [ ] Pin and hash every model artifact; publish model cards and license notices.
- [ ] Add malformed-media tests, dependency scanning, and reproducible release checks.
- [ ] Audit network requests and confirm no media payload leaves the device.
- [ ] Publish tested device/browser matrix and explicit model limitations.
