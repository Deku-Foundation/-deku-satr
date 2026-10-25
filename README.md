# Deku Satr

**Local-first visual privacy tooling from Deku Foundation.**

The long-term product goal is a lightweight tool that masks visible people in content across the Android screen, with desktop support planned later. The repository currently contains two different maturity levels: a browser prototype for testing mask quality, and an early native Android capture/overlay foundation.

> **Important:** The Android app on this branch is not a blur filter yet. It demonstrates the consent, capture-session, foreground-notification, and overlay-control plumbing. The native code intentionally does not read frame pixels or run a model. Do not rely on either prototype as a guaranteed safety filter.

## Native Android (active development)

Path: android/

- Kotlin Android app; min SDK 26, compile/target SDK 36.
- Explicit overlay access and a fresh MediaProjection consent flow for every capture session.
- A mediaProjection foreground service with a persistent notification and stop action.
- A small floating overlay with its own stop action.
- Capture surface downscaled to a maximum 960 px long edge before future inference.
- No camera or microphone permission; no internet permission; no screenshot files or network upload.
- Unit tests for the capture-size policy and a GitHub Actions workflow that builds the debug APK.

This milestone acquires and closes capture frames without reading their pixel planes. The overlay explicitly says that AI blur is inactive. Device-level validation, the overlay-feedback test, and actual person masking remain open.

See docs/android-architecture.md and docs/android-privacy.md.

## Browser research prototype

The root project is an installable Progressive Web App (PWA) for experimenting with selected images and a live camera preview in the browser.

Requires Node.js 22:

    npm install
    npm run dev
    npm test
    npm run build

The PWA currently uses Xenova/segformer-b0-finetuned-ade-512-512 through Transformers.js. This is a temporary semantic-segmentation baseline, not the production mobile model. Its upstream model license is listed as "other" and must be reviewed before bundling or shipping weights. Model weights are not committed to Git.

See docs/model-evaluation.md and ROADMAP.md.

## Android build

Install JDK 17, Gradle 8.13, Android SDK Platform 36, and accept the SDK licenses:

    gradle --no-daemon -p android testDebugUnitTest assembleDebug
    adb install -r android/app/build/outputs/apk/debug/app-debug.apk

The Android workflow runs tests and creates a debug APK artifact. A workflow pass is not proof of physical-device compatibility or model accuracy.

## Engineering principles

- Process screen frames on-device and in memory.
- Ask for explicit consent and keep an obvious stop control visible.
- Never imply protection when inference fails, goes stale, or cannot capture a screen.
- Benchmark compact quantized segmentation models on low-end devices before selecting one.
- Review model weights, training-data provenance, and licenses before shipping.
- Never classify gender; mask human figures without demographic inference.
- Do not bypass Android secure-window or DRM restrictions.

## License

Repository code is MIT-licensed. Third-party models, runtimes, and dependencies have their own licenses.
