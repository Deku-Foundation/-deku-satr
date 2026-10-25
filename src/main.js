import "./style.css";
import { renderFullyObscured, renderMaskedBlur, segmentPeople } from "./vision.js";

const imageInput = document.querySelector("#imageInput");
const cameraButton = document.querySelector("#cameraButton");
const processButton = document.querySelector("#processButton");
const downloadButton = document.querySelector("#downloadButton");
const blurStrength = document.querySelector("#blurStrength");
const blurValue = document.querySelector("#blurValue");
const statusTitle = document.querySelector("#statusTitle");
const statusText = document.querySelector("#statusText");
const statusDot = document.querySelector("#statusDot");
const emptyState = document.querySelector("#emptyState");
const canvasWrap = document.querySelector("#canvasWrap");
const resultCanvas = document.querySelector("#resultCanvas");
const cameraVideo = document.querySelector("#cameraVideo");
const modeBadge = document.querySelector("#modeBadge");
const previewHeading = document.querySelector("#previewHeading");
const resolutionLabel = document.querySelector("#resolutionLabel");
const elapsedLabel = document.querySelector("#elapsedLabel");

let selectedImage = null;
let sourceCanvas = document.createElement("canvas");
let cameraStream = null;
let cameraActive = false;
let cameraBusy = false;
let cameraLoopTimer = null;
let lastSuccessfulPersonMask = false;

function setStatus(title, message, tone = "neutral") {
  statusTitle.textContent = title;
  statusText.textContent = message;
  statusDot.dataset.tone = tone;
}
function showCanvas() { emptyState.hidden = true; canvasWrap.hidden = false; }
function hideCanvas() { canvasWrap.hidden = true; emptyState.hidden = false; }
function updateBlurLabel() {
  blurValue.value = `${blurStrength.value} px`;
  blurValue.textContent = `${blurStrength.value} px`;
}
function dimensionsFor(source, maxEdge = 1280) {
  const width = source.videoWidth || source.naturalWidth || source.width;
  const height = source.videoHeight || source.naturalHeight || source.height;
  if (!width || !height) throw new Error("The selected source is not ready.");
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}
function captureSource(source, maxEdge = 1280) {
  const size = dimensionsFor(source, maxEdge);
  sourceCanvas.width = size.width;
  sourceCanvas.height = size.height;
  const context = sourceCanvas.getContext("2d");
  context.clearRect(0, 0, size.width, size.height);
  context.drawImage(source, 0, 0, size.width, size.height);
  return size;
}
function setOutputMetadata(width, height, elapsedMs) {
  resolutionLabel.textContent = `${width} × ${height} · local`;
  elapsedLabel.textContent = elapsedMs == null ? "Live preview" : `${(elapsedMs / 1000).toFixed(2)} s inference`;
}
function stopCamera() {
  cameraActive = false;
  cameraBusy = false;
  if (cameraLoopTimer) clearTimeout(cameraLoopTimer);
  cameraLoopTimer = null;
  if (cameraStream) for (const track of cameraStream.getTracks()) track.stop();
  cameraStream = null;
  cameraVideo.srcObject = null;
  cameraButton.textContent = "Start camera preview";
}
async function onImageSelected(file) {
  stopCamera();
  selectedImage = null;
  lastSuccessfulPersonMask = false;
  downloadButton.disabled = true;
  processButton.disabled = true;
  if (!file) return;
  if (!file.type.startsWith("image/")) {
    setStatus("Unsupported file", "Choose a still image (JPG, PNG, or WebP).", "error");
    return;
  }
  const imageUrl = URL.createObjectURL(file);
  const image = new Image();
  image.onload = () => {
    URL.revokeObjectURL(imageUrl);
    selectedImage = image;
    processButton.disabled = false;
    previewHeading.textContent = "Image preview";
    modeBadge.textContent = "Image mode";
    modeBadge.dataset.tone = "neutral";
    const size = captureSource(image);
    resultCanvas.width = size.width;
    resultCanvas.height = size.height;
    resultCanvas.getContext("2d").drawImage(sourceCanvas, 0, 0);
    showCanvas();
    setOutputMetadata(size.width, size.height, null);
    setStatus("Image ready", "Select “Load model and protect image” to begin local segmentation.");
  };
  image.onerror = () => {
    URL.revokeObjectURL(imageUrl);
    setStatus("Could not open image", "Try another image file.", "error");
  };
  image.src = imageUrl;
}
async function processStillImage() {
  if (!selectedImage) return;
  processButton.disabled = true;
  downloadButton.disabled = true;
  lastSuccessfulPersonMask = false;
  const start = performance.now();
  try {
    const size = captureSource(selectedImage);
    setStatus("Preparing vision model", "Loading the model for local inference. First use may need a download.");
    const mask = await segmentPeople(sourceCanvas, (message) => setStatus("Preparing vision model", message));
    const elapsed = performance.now() - start;
    if (!mask || !mask.data.some((value) => value > 0)) {
      resultCanvas.width = sourceCanvas.width;
      resultCanvas.height = sourceCanvas.height;
      resultCanvas.getContext("2d").drawImage(sourceCanvas, 0, 0);
      setStatus("No reliable person mask", "No automatic blur was applied. Do not treat this image as protected.", "error");
      setOutputMetadata(size.width, size.height, elapsed);
      showCanvas();
      return;
    }
    renderMaskedBlur(sourceCanvas, mask, resultCanvas, Number(blurStrength.value));
    lastSuccessfulPersonMask = true;
    downloadButton.disabled = false;
    showCanvas();
    setStatus("Person mask found", "Blur applied locally. Review hair, clothing edges, and people in the background.", "success");
    setOutputMetadata(size.width, size.height, elapsed);
    modeBadge.textContent = "Processed locally";
    modeBadge.dataset.tone = "success";
  } catch (error) {
    console.error(error);
    setStatus("Inference failed", "No protected output was created. Check the model download connection, then retry.", "error");
  } finally {
    processButton.disabled = !selectedImage;
  }
}
async function processCameraLoop() {
  if (!cameraActive || cameraBusy) return;
  cameraBusy = true;
  const start = performance.now();
  try {
    if (cameraVideo.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return;
    const size = captureSource(cameraVideo, 512);
    const mask = await segmentPeople(sourceCanvas, (message) => setStatus("Loading camera model", message));
    if (!cameraActive) return;
    if (mask && mask.data.some((value) => value > 0)) {
      renderMaskedBlur(sourceCanvas, mask, resultCanvas, Number(blurStrength.value));
      setStatus("Live protection preview", "A person mask is active. This preview is not a guarantee of complete coverage.", "success");
    } else {
      renderFullyObscured(sourceCanvas, resultCanvas);
      setStatus("Uncertain frame", "No reliable person mask; the whole frame is hidden as a precaution.", "warning");
    }
    showCanvas();
    setOutputMetadata(size.width, size.height, performance.now() - start);
  } catch (error) {
    console.error(error);
    if (sourceCanvas.width && sourceCanvas.height) {
      renderFullyObscured(sourceCanvas, resultCanvas);
      showCanvas();
    }
    setStatus("Camera processing error", "The frame was hidden. Stop and restart the camera or try another browser.", "error");
  } finally {
    cameraBusy = false;
    if (cameraActive) cameraLoopTimer = setTimeout(processCameraLoop, 180);
  }
}
async function startCamera() {
  if (cameraActive) {
    stopCamera();
    hideCanvas();
    setStatus("Camera stopped", "The camera stream has been closed.");
    return;
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    setStatus("Camera unavailable", "Camera access requires a supported browser on HTTPS or localhost.", "error");
    return;
  }
  stopCamera();
  selectedImage = null;
  processButton.disabled = true;
  downloadButton.disabled = true;
  lastSuccessfulPersonMask = false;
  try {
    setStatus("Requesting camera", "Allow camera access in the browser permission prompt.");
    cameraStream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: "environment" }, width: { ideal: 640 }, height: { ideal: 480 } },
    });
    cameraVideo.srcObject = cameraStream;
    await cameraVideo.play();
    cameraActive = true;
    cameraButton.textContent = "Stop camera preview";
    previewHeading.textContent = "Live camera";
    modeBadge.textContent = "Camera mode";
    modeBadge.dataset.tone = "warning";
    renderFullyObscured(sourceCanvas, resultCanvas);
    showCanvas();
    setStatus("Camera started", "The frame stays hidden until local processing produces a usable mask.", "warning");
    await processCameraLoop();
  } catch (error) {
    console.error(error);
    stopCamera();
    setStatus("Could not start camera", "Check permission and ensure another app is not using the camera.", "error");
  }
}
imageInput.addEventListener("change", (event) => onImageSelected(event.target.files?.[0]));
processButton.addEventListener("click", processStillImage);
cameraButton.addEventListener("click", startCamera);
blurStrength.addEventListener("input", () => {
  updateBlurLabel();
  if (cameraActive && !cameraBusy) processCameraLoop();
});
downloadButton.addEventListener("click", () => {
  if (!lastSuccessfulPersonMask || !resultCanvas.width || !resultCanvas.height) return;
  resultCanvas.toBlob((blob) => {
    if (!blob) { setStatus("Export failed", "The browser could not encode the protected PNG.", "error"); return; }
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "deku-satr-protected.png";
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }, "image/png");
});
window.addEventListener("pagehide", stopCamera);
updateBlurLabel();