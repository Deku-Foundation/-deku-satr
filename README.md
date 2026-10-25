# Deku Satr

Privacy-first, on-device visual protection for people in images and video.

Deku Satr is an independent Deku Foundation project. Its first milestone is a local-first Progressive Web App for testing people segmentation and blur on user-selected images. The long-term direction includes Android, desktop, and browser integrations.

> Status: early prototype. Do not treat it as a guaranteed safety filter. Detection can miss people or leak pixels at boundaries; inspect the output before sharing.

## Principles

- Process user media on the device; never upload image or video content to a Deku server.
- Keep models replaceable behind a small inference interface.
- Prefer small, quantized vision models and measure quality, latency, memory, and energy on real low-end devices.
- Fail visibly; never claim a frame is protected when inference failed.
- Respect model and dataset licenses and publish a model card for every shipped weight file.

## Development

See `ROADMAP.md` and `docs/model-evaluation.md` before selecting a production model.

## Project status

The repository is being bootstrapped. The first working demo will be a browser-installable PWA; native Android camera processing and broader desktop/browser integration require separate platform testing.
