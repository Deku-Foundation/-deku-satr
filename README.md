# Deku Satr

**Local-first visual privacy tooling from Deku Foundation.**

Deku Satr is being developed as a native Android utility that attempts to mask people visible on the screen across apps. The repository also retains a browser prototype for research. Android work is the active product path.

> **Prototype warning:** the Android filter now runs an embedded person-segmentation model and draws a blurred mask, the LR-ASPP semantic person segmenter is integrated as an experimental candidate, but has not been validated for all screen content. It can miss small or distant people. Secure windows and DRM content may be un-capturable. Do not treat this release as guaranteed protection.

## Native Android — current milestone

Path: android/

- Kotlin app with Arabic-first Material 3 UI, separate Home/Settings destinations, adaptive 680 dp maximum width, system light/dark themes, and a 48 dp floating Stop control.
- Explicit overlay permission and Android MediaProjection consent for every capture session; Android 14+ requests the full default display rather than a single app window.
- A visible media-projection foreground service, notification stop action, draggable floating stop panel, and a full-screen transparent mask layer that does not accept touches.
- Bundled model: LiteRT/TFLite LR-ASPP + MobileNetV3-Large, dynamic weight-only INT8 with FP32 activations (about 3.52 MB). Gradle downloads the pinned artifact at build time and verifies SHA-256 before packaging it; the app does not download weights at runtime.
- Local in-memory processing offers four profiles: low-latency (360 px, single pass, 80 ms admission interval), power saver (480 px, single pass), balanced (640 px with tiling on tall/wide frames), and detail (720 px with tiling). The interval is a throttle, not a promised frame rate. Busy frames are dropped rather than queued. The selected effect can be blur, pixelation, or solid cover.
- Android 12+ pass-through touch requires a maximum 0.8 opacity for the normal overlay. This is a documented residual-image tradeoff; fail-closed mode is fully opaque and may block underlying touches until Stop is tapped.
- No camera or microphone permission, no screenshot file storage, and the manifest merger is instructed to remove the INTERNET permission.
- Unit tests for capture-size policy, mask confidence bounds, and persisted filter-setting ranges.
- GitHub Actions builds/tests the Android app, audits the merged manifest for the INTERNET permission, and uploads a debug APK artifact.

The live path uses a pinned semantic person mask, reusable tensor buffers and one reusable input bitmap, NNAPI-to-CPU fallback, short temporal smoothing, and a bounded softmax early-exit for background pixels. The low-latency profile disables tiling to reduce work per frame; it can miss people farther away. None of the profiles has a guaranteed FPS or 99% recall claim. The model returns one shared mask for all people; it does not split individuals or classify gender. Current behavior blurs all detected people. Selective targeting remains gated on a compatible instance model and explicit user selection. See Android architecture (docs/android-architecture.md), privacy notes (docs/android-privacy.md), and model evaluation (docs/model-evaluation.md).

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

Repository code is MIT-licensed. LiteRT/TFLite and AndroidX have their own license terms. The LR-ASPP converted model's distribution license still needs explicit verification before a release APK is published.


## Native milestone — 0.5.4

- The overlay stays clear during model warm-up and explicitly reports that filtering is not active until the first valid mask. Real inference failure still activates an opaque charcoal fallback.
- The floating panel is compact; tap its title to reveal status details, or drag it to move it. The stop target remains accessible.
- The decoder skips full softmax only where the person probability is mathematically below the minimum supported mask threshold. Accepted pixels retain the original softmax path.
- The live model reuses one letterbox bitmap/canvas and draws the source directly into the model input rectangle, avoiding a temporary scaled bitmap allocation per frame or tile.
- Selective targeting by gender is not implemented. The bundled semantic model cannot separate individuals, and visual gender inference is not used. The next research gate is a license-compatible instance-mask model plus explicit user selection.

## Historical notes: version 0.4.2

The Satr Blur identity now follows the supplied mihrab-and-fading-layers mark and its deep-green, botanical-green, warm-gold and off-white palette. Settings include working blur, pixelation and opaque-cover choices. Live rendering now defers old bitmap recycling, holds a recent mask briefly through intermittent model dropouts, and targets up to roughly 7 frame admissions per second on devices that can sustain it. The live and still-image paths now use LR-ASPP + MobileNetV3-Large through LiteRT/TFLite. This is a model migration for evaluation, not evidence of 99% accuracy or a validated replacement-model win. Solid cover can block interaction with the app underneath, while the floating Stop control remains available.

## Version 0.4.1
The Arabic-first Home destination contains session status and Start/Stop. Filter strength, confidence threshold and mask expansion are on the separate Settings destination and persist locally. The brand uses the supplied Satr Blur palette and mihrab mark, with Deku Foundation credited separately. Denying POST_NOTIFICATIONS no longer blocks the capture flow; Android may restrict the notification's drawer visibility while the system foreground-service rules and the app/overlay Stop controls remain in force. Metrics are throttled to avoid recomposing the whole dashboard at inference frequency. The live mask is held through two transient empty frames to reduce visible blinking on video; empty masks also skip the CPU blur pass.

## Android build tools
Build plugin and library pins live in android/gradle/libs.versions.toml. The current toolchain is AGP 9.3.0, Gradle 9.5.0, Compose UI/Foundation 1.11.4, compileSdk 36 and JDK 17. The existing Kotlin/Compose compiler plugins are kept through explicit AGP migration flags until the built-in Kotlin/new DSL migration has its own validation.
