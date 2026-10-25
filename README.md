# Deku Satr

**Local-first visual privacy tooling from Deku Foundation.**

Deku Satr is being developed as a native Android utility that attempts to mask people visible on the screen across apps. The repository also retains a browser prototype for research. Android work is the active product path.

> **Prototype warning:** the Android filter now runs an embedded person-segmentation model and draws a blurred mask, but the segmentation model is optimized for selfie/person imagery, not validated for all screen content. It can miss small or distant people. Secure windows and DRM content may be un-capturable. Do not treat this release as guaranteed protection.

## Native Android — current milestone

Path: android/

- Kotlin app with an Arabic-first, responsive Material 3 interface and light/dark system themes.
- Explicit overlay permission and Android MediaProjection consent for every capture session.
- A visible media-projection foreground service, notification stop action, draggable floating stop panel, and a full-screen transparent mask layer that does not accept touches.
- Bundled model: ML Kit Selfie Segmentation 16.0.0-beta6. The model assets are statically linked at build time; the app does not download model weights on first launch.
- Local in-memory processing: screen frames are downscaled to a 640 px longest edge, inference is throttled, new frames are dropped while the model is busy, and masked areas are blurred with a bounded two-pass CPU blur.
- No camera or microphone permission, no screenshot file storage, and the manifest merger is instructed to remove the INTERNET permission.
- Unit tests for capture-size policy and confidence-mask thresholding/dilation.
- GitHub Actions builds/tests the Android app, audits the merged manifest for the INTERNET permission, and uploads a debug APK artifact.

The current model adds approximately 4.5 MB according to Google's ML Kit guide. It is a beta API and uses a selfie segmentation model, so the screen-capture domain still requires real-device evaluation. See Android architecture (docs/android-architecture.md), privacy notes (docs/android-privacy.md), and model evaluation (docs/model-evaluation.md).

## Download a development APK

Open the latest successful Android build workflow:
https://github.com/Deku-Foundation/deku-satr/actions/workflows/android.yml

Download the deku-satr-debug-apk artifact from a completed run. It is a debug build, not a production-signed release.

To build locally, install JDK 17, Gradle 8.13, Android SDK Platform 36, and accept the SDK licenses:

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

Repository code is MIT-licensed. ML Kit, AndroidX, and other third-party libraries have their own license terms.
