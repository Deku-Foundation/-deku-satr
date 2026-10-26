# Deku Satr

**Local-first visual privacy tooling from Deku Foundation.**

Deku Satr is being developed as a native Android utility that attempts to mask people visible on the screen across apps. The repository also retains a browser prototype for research. Android work is the active product path.

> **Prototype warning:** Android v0.6.2 uses the pinned RTMDet-Ins-Tiny instance-segmentation model, with manual per-person selection. It is not a validated full-screen filter and can miss small, distant, occluded, or unusual-content people. Secure windows and DRM content may be un-capturable. Do not treat this release as guaranteed protection.

## Native Android — current milestone

Path: android/

- Kotlin app with Arabic-first Material 3 UI, separate Home/Settings destinations, adaptive 680 dp maximum width, system light/dark themes, and a 48 dp floating Stop control.
- Explicit overlay permission and Android MediaProjection consent for every capture session; Android 14+ requests the full default display rather than a single app window.
- A visible media-projection foreground service, notification stop action, draggable floating stop panel, and a full-screen transparent mask layer that does not accept touches.
- Active model: RTMDet-Ins-Tiny ONNX (24,032,356 bytes), pinned to an immutable revision and SHA-256-verified at build time. It creates per-person masks and runs through ONNX Runtime CPU; the prior LR-ASPP model and NNAPI initialization path were removed to reduce package size and model-startup instability.
- Local in-memory processing offers four profiles: low-latency (360 px, single pass, 80 ms admission interval), power saver (480 px, single pass), balanced (640 px with tiling on tall/wide frames), and detail (720 px with tiling). The interval is a throttle, not a promised frame rate. Busy frames are dropped rather than queued. The selected effect can be blur, pixelation, or solid cover.
- Android 12+ pass-through touch requires a maximum 0.8 opacity for the normal overlay. This is a documented residual-image tradeoff; fail-closed mode is fully opaque and may block underlying touches until Stop is tapped.
- No camera or microphone permission, no screenshot file storage, and the manifest merger is instructed to remove the INTERNET permission.
- Unit tests for capture-size policy, mask confidence bounds, and persisted filter-setting ranges.
- GitHub Actions builds/tests the Android app, audits the merged manifest for the INTERNET permission, and uploads a debug APK artifact.

The live path uses one pinned instance-segmentation model, reusable input buffers, one in-flight inference, optional tiling for large frames, and session-local manual target selection. The fastest profile reduces input size and can miss people farther away. Frame-admission intervals are throttles, not promised FPS. The app does not infer gender yet; manual corrections change the current mask policy but do not train weights or build persistent identities. Accuracy, latency and heat on real phones remain unmeasured. No 99% recall claim is made. See Android architecture (docs/android-architecture.md), privacy notes (docs/android-privacy.md), and model evaluation (docs/model-evaluation.md).


## Upgrading development APKs

Debug APKs built by GitHub Actions use one stable **development-only** signing identity so future updates can install over the same app without clearing settings. APKs downloaded from older workflow runs may have been signed with a different auto-generated key; Android does not allow an in-place update across signing identities, so those installs require one clean reinstall. After migrating to the stable signer, later development APKs can update in place. This key is public for test builds only and must never be used to sign a production release.

## Download a development APK

Open the latest successful Android build workflow:
https://github.com/Deku-Foundation/deku-satr/actions/workflows/android.yml

Download the deku-satr-debug-apk artifact from a completed run. It is a debug build, not a production-signed release.

To build locally, install JDK 17, Gradle 9.5.0, Android SDK Platform 36, and accept the SDK licenses:

    gradle --no-daemon -p android testDebugUnitTest assembleDebug

The output is android/app/build/outputs/apk/debug/app-debug.apk.

## Browser research prototype

The repository root contains a Progressive Web App for experimenting with selected images and a live camera preview.

Requires Node.js 22:

    npm install
    npm run dev
    npm test
    npm run build

The PWA uses Xenova/segformer-b0-finetuned-ade-512-512 through Transformers.js as a research baseline. The upstream model license is listed as "other"; review exact terms before distributing those weights. This browser model is separate from the native Android model.

## GitHub security automation

- CodeQL workflow analyzes browser JavaScript/TypeScript and the Android Kotlin code on pull requests and weekly scheduled runs.
- Dependabot checks GitHub Actions, npm packages, and Gradle dependencies weekly.
- Android CI verifies tests/build and fails if INTERNET is present in the merged APK manifest.
- See SECURITY.md for the threat model, disclosure guidance, and release gates.

## Engineering and security principles

- Process pixels on the device and keep them in memory only.
- Ask for explicit user consent; keep visible indicators and an immediate stop control while capture runs.
- Never describe the session as guaranteed protection. On inference failure, the native overlay covers the display and explains the failure.
- Avoid unbounded queues: keep the latest frame, discard stale frames, and throttle inference on low-end devices.
- Keep the Android merged manifest free of the INTERNET permission unless a separately reviewed feature needs it.
- Pin model/runtime versions, check licensing and data provenance, and benchmark recall, edge leakage, latency, memory, heat, and battery use on real devices.
- Mask visible human figures; do not infer or label gender.
- Do not bypass Android secure-window or DRM restrictions.

## License

Repository code is MIT-licensed. RTMDet-Ins-Tiny weights and their NOTICE are pinned and included from the upstream Apache-2.0 model repository; ONNX Runtime Android and AndroidX carry their own licenses. Model integration is still experimental and not a statement of accuracy.


## Native milestone — 0.5.5

- The overlay stays clear during model warm-up and explicitly reports that filtering is not active until the first valid mask. Real inference failure still activates an opaque charcoal fallback.
- The floating panel is compact; tap its title to reveal status details, or drag it to move it. The stop target remains accessible.
- The decoder skips full softmax only where the person probability is mathematically below the minimum supported mask threshold. Accepted pixels retain the original softmax path.
- The live model reuses one letterbox bitmap/canvas and draws the source directly into the model input rectangle, avoiding a temporary scaled bitmap allocation per frame or tile.
- Added RTMDet-Ins-Tiny instance segmentation and manual correction controls: blur all, blur selected people only, or blur all except selected people. Selection follows short-lived track IDs during the current session; no gender is inferred and no model weights are trained automatically.

## Historical notes: version 0.4.2

The Satr Blur identity now follows the supplied mihrab-and-fading-layers mark and its deep-green, botanical-green, warm-gold and off-white palette. Settings include working blur, pixelation and opaque-cover choices. Live rendering now defers old bitmap recycling, holds a recent mask briefly through intermittent model dropouts, and targets up to roughly 7 frame admissions per second on devices that can sustain it. The live and still-image paths now use RTMDet-Ins-Tiny through ONNX Runtime CPU. The former LR-ASPP/LiteRT backend is no longer packaged; the Tiny model was selected to lower model weight size and avoid the NNAPI startup path. This remains a model migration for evaluation, not evidence of 99% accuracy or a validated on-device accuracy win. Solid cover can block interaction with the app underneath, while the floating Stop control remains available.

## Version 0.4.1
The Arabic-first Home destination contains session status and Start/Stop. Filter strength, confidence threshold and mask expansion are on the separate Settings destination and persist locally. The brand uses the supplied Satr Blur palette and mihrab mark, with Deku Foundation credited separately. Denying POST_NOTIFICATIONS no longer blocks the capture flow; Android may restrict the notification's drawer visibility while the system foreground-service rules and the app/overlay Stop controls remain in force. Metrics are throttled to avoid recomposing the whole dashboard at inference frequency. The live mask is held through two transient empty frames to reduce visible blinking on video; empty masks also skip the CPU blur pass.

## Android build tools
Build plugin and library pins live in android/gradle/libs.versions.toml. The current toolchain is AGP 9.3.0, Gradle 9.5.0, Compose UI/Foundation 1.11.4, compileSdk 36 and JDK 17. The existing Kotlin/Compose compiler plugins are kept through explicit AGP migration flags until the built-in Kotlin/new DSL migration has its own validation.
