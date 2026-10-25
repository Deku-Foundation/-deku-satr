import { env, pipeline } from "@huggingface/transformers";
import { dilateMask, thresholdMask, unionMasks } from "./mask.js";

export const MODEL_ID = "Xenova/segformer-b0-finetuned-ade-512-512";
let segmenterPromise;

env.allowRemoteModels = true;
env.allowLocalModels = false;
env.useBrowserCache = true;
if (env.backends?.onnx?.wasm) {
  env.backends.onnx.wasm.numThreads = Math.max(1, Math.min(4, (globalThis.navigator?.hardwareConcurrency || 2) - 1));
}

function reportProgress(callback, item) {
  if (typeof callback !== "function") return;
  const file = item?.file ? String(item.file).split("/").pop() : "model";
  callback(typeof item?.progress === "number"
    ? \`Downloading \${file} · \${Math.round(item.progress)}%\`
    : \`Preparing \${file}…\`);
}

export async function loadSegmenter(onProgress = () => {}) {
  if (!segmenterPromise) {
    segmenterPromise = (async () => {
      const progress_callback = (event) => reportProgress(onProgress, event);
      if ("gpu" in navigator) {
        try {
          onProgress("Starting WebGPU…");
          return await pipeline("image-segmentation", MODEL_ID, { device: "webgpu", dtype: "q8", progress_callback });
        } catch (error) {
          console.warn("WebGPU initialization failed; falling back to WASM.", error);
        }
      }
      onProgress("Starting CPU/WASM inference…");
      return await pipeline("image-segmentation", MODEL_ID, { device: "wasm", dtype: "q8", progress_callback });
    })().catch((error) => {
      segmenterPromise = undefined;
      throw error;
    });
  }
  return segmenterPromise;
}

function maskForSegment(segment) {
  const image = segment?.mask;
  if (!image?.data || !image.width || !image.height) return null;
  const channels = image.channels || 1;
  const grayscale = new Uint8Array(image.width * image.height);
  for (let i = 0; i < grayscale.length; i += 1) grayscale[i] = image.data[i * channels] ?? 0;
  return { data: thresholdMask(grayscale, 0.35), width: image.width, height: image.height };
}

export async function segmentPeople(canvas, onProgress) {
  const model = await loadSegmenter(onProgress);
  const segments = await model(canvas);
  const persons = segments.filter((item) => String(item?.label || "").trim().toLowerCase() === "person");
  if (!persons.length) return null;

  const masks = persons.map(maskForSegment).filter(Boolean);
  if (!masks.length) return null;
  const { width, height } = masks[0];
  const compatible = masks.filter((mask) => mask.width === width && mask.height === height).map((mask) => mask.data);
  if (!compatible.length) return null;
  return {
    data: dilateMask(unionMasks(compatible, width, height), width, height, Math.max(1, Math.round(width / 320))),
    width,
    height,
    personSegments: persons.length,
  };
}

export function renderMaskedBlur(sourceCanvas, mask, outputCanvas, blurStrength = 18) {
  const width = sourceCanvas.width;
  const height = sourceCanvas.height;
  if (!width || !height) throw new Error("The source frame has no dimensions.");

  const maskCanvas = document.createElement("canvas");
  maskCanvas.width = mask.width;
  maskCanvas.height = mask.height;
  const maskContext = maskCanvas.getContext("2d", { willReadFrequently: true });
  const maskPixels = maskContext.createImageData(mask.width, mask.height);
  for (let i = 0; i < mask.data.length; i += 1) {
    const offset = i * 4;
    maskPixels.data[offset] = 255;
    maskPixels.data[offset + 1] = 255;
    maskPixels.data[offset + 2] = 255;
    maskPixels.data[offset + 3] = mask.data[i];
  }
  maskContext.putImageData(maskPixels, 0, 0);

  const overlay = document.createElement("canvas");
  overlay.width = width;
  overlay.height = height;
  const overlayContext = overlay.getContext("2d");
  overlayContext.filter = \`blur(\${Math.max(2, Number(blurStrength) || 18)}px)\`;
  overlayContext.drawImage(sourceCanvas, 0, 0, width, height);
  overlayContext.filter = "none";
  overlayContext.globalCompositeOperation = "destination-in";
  overlayContext.drawImage(maskCanvas, 0, 0, width, height);
  overlayContext.globalCompositeOperation = "source-over";

  outputCanvas.width = width;
  outputCanvas.height = height;
  const outputContext = outputCanvas.getContext("2d");
  outputContext.clearRect(0, 0, width, height);
  outputContext.drawImage(sourceCanvas, 0, 0, width, height);
  outputContext.drawImage(overlay, 0, 0);
}

/** Privacy fallback: show no camera pixels when segmentation is uncertain. */
export function renderFullyObscured(sourceCanvas, outputCanvas) {
  outputCanvas.width = sourceCanvas.width;
  outputCanvas.height = sourceCanvas.height;
  const context = outputCanvas.getContext("2d");
  context.fillStyle = "#101a2a";
  context.fillRect(0, 0, outputCanvas.width, outputCanvas.height);
}