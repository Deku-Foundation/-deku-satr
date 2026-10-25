# Deku Satr

**Privacy-first visual protection for images and a live camera preview.**

The first milestone is an installable Progressive Web App (PWA): choose an image or start the camera, run segmentation in the browser, and blur detected people. There is no image-upload endpoint, login, analytics, or application server.

> **Prototype warning:** segmentation can miss people or leak pixels at boundaries. Do not rely on this version as a guaranteed safety filter; inspect the output before sharing sensitive media.

## Run locally

Requires Node.js 22.

\`\`\`sh
npm install
npm run dev
\`\`\`

Open the local URL Vite prints. Model weights download from Hugging Face on first inference; image/video pixels are processed in the browser. Camera access requires HTTPS or localhost.

\`\`\`sh
npm test
npm run build
npm run preview
\`\`\`

## What works now

- Local image selection, segmentation, masked blur, and PNG export.
- Live camera preview with serial inference.
- If camera inference has no usable person mask or fails, the entire preview is replaced with a solid obscuring frame.
- WebGPU is attempted when available, with a WASM fallback.
- Responsive PWA layout, mask-processing unit tests, and GitHub Actions checks.

## Model baseline

The current browser prototype uses \`Xenova/segformer-b0-finetuned-ade-512-512\` through Transformers.js. This is a semantic-segmentation baseline, **not the final tiny production model**. The upstream NVIDIA model card lists license \`other\`; model licensing must be resolved before bundling or shipping weights. Models are downloaded at run time and are not committed to Git.

See [model evaluation](docs/model-evaluation.md) and [roadmap](ROADMAP.md).

## Privacy boundaries

- Selected images and camera pixels are processed locally; the app has no server endpoint for uploading media.
- The first-use model download makes network requests to the upstream model host, which can see ordinary download metadata.
- Strict offline operation has not yet been audited.
- This app handles selected images and the camera preview it starts. It is **not** a system-wide Android overlay and does not automatically protect other apps.

## Structure

\`\`\`text
src/                 Browser UI, inference, and mask utilities
tests/               Deterministic unit tests
docs/                Model selection and limitations
.github/workflows/   CI and GitHub Pages deployment
\`\`\`

## License

The code in this repository is MIT-licensed. Third-party models and libraries have separate licenses.