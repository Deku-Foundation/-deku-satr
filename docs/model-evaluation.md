# Model evaluation

Updated: 2026-10-25. This is a source-based shortlist, not a claim that every candidate passed a Deku Satr benchmark.

## Task definition

A useful filter needs a conservative pixel mask for each person in a still image or video frame. Keep these task types separate:
- **Semantic segmentation:** a shared `person` mask can cover multiple people but does not distinguish individuals.
- **Instance segmentation:** a separate mask per person is best for per-person controls, but usually costs more.
- **Portrait segmentation / matting:** may be fast and detailed for one prominent subject but is not automatically a crowd filter.
- **Promptable segmentation:** generally needs a click, box or prompt, so full-frame automatic coverage is a different problem.

## Candidates and current decisions

| Candidate | Verified source evidence | Current decision |
|---|---|---|
| `Xenova/segformer-b0-finetuned-ade-512-512` | Browser-ready ONNX model for Transformers.js; upstream card says 3.75M parameters and the ADE20K label set includes `person`. The ONNX repository listing is about 27.7 MB overall. | Temporary web baseline only. Upstream NVIDIA model card lists license `other`; do not bundle or ship the weights until the exact terms are reviewed. Actual download size depends on the selected ONNX variant and cache. |
| MediaPipe Selfie Segmenter | Official Google guide describes 256×256 float16 models and reports about 33.46 ms CPU latency on Pixel 6 for the square model. | Speed reference; design assumes a prominent selfie subject, not guaranteed multi-person segmentation. |
| ML Kit Selfie Segmentation 16.0.0-beta6 (Android APK) | Google documents stream mode, a confidence mask, static bundled dependency and about 4.5 MB added app size; 25–65 ms reference latency on Pixel 4 depending on input size. | **Integrated native baseline**: frames are downscaled to 640 px, only one inference runs at a time, masks are thresholded/dilated, and a two-pass CPU blur is drawn over the mask. Full-screen screenshot quality is unvalidated. |
| TorchVision LR-ASPP + MobileNetV3-Large, LiteRT conversion | LiteRT Community has converted regular and dynamic INT8-compatible artifacts. The architecture is a compact semantic segmentation candidate. | Evaluate as a smaller Android candidate after confirming class mapping, license, output shape, and target-device performance. |
| YOLACT ResNet-50 LiteRT | Model card documents instance masks and MIT source weights; reports roughly 38–41 ms GPU graph latency on recent devices. | Keep as an accuracy/reference baseline, not the low-end default: model artifact is about 125 MB. |
| MobileSAM2 (July 2026 research) | Research explores distilling SAM 2 into smaller image/video segmentation models for resource-limited devices. | Do not adopt until usable checkpoints, licensing, conversion, and real low-end Android results are verified. |

## Sources

- Google AI Edge Image Segmenter: https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter
- Transformers.js SegFormer ONNX model: https://huggingface.co/Xenova/segformer-b0-finetuned-ade-512-512
- Upstream NVIDIA SegFormer model/license: https://huggingface.co/nvidia/segformer-b0-finetuned-ade-512-512
- LiteRT LR-ASPP conversion: https://huggingface.co/litert-community/lraspp_mobilenet_v3_large
- LiteRT YOLACT model card: https://huggingface.co/litert-community/YOLACT-ResNet50-LiteRT
- MobileSAM2 paper: https://arxiv.org/abs/2607.12297
- LiteRT Android APIs and release history: https://developers.google.com/edge/litert/android

## Evaluation protocol

Evaluate all models on the same versioned test set and hardware:
- Pin model ID + immutable revision or file checksum; record input resolution, preprocessing, class mapping, and mask threshold.
- Measure person recall, missed-person rate, boundary leakage, false-positive area, latency p50/p95, peak RAM, sustained FPS, temperature, battery draw, APK size, and model download size.
- Include multiple and distant people, occlusion, backlighting, dark clothing, varied skin tones, seated/standing poses, cropped limbs, rotation, low resolution, and motion blur.
- Do not compare published timings across different devices as if they were apples-to-apples.
- Release only after licensing review and documented device tests; this prototype has no production accuracy or safety claim.

## Technical direction

The Android APK now embeds ML Kit Selfie Segmentation 16.0.0-beta6 as the first offline baseline. Its stream-mode person mask feeds a bounded in-memory blur pipeline; input edge is capped at 640 px and frame processing is single-flight/throttled. LiteRT `CompiledModel` remains a candidate for a later controlled comparison on the same screen-frame dataset, not an additional runtime to add without measured benefit.

## Native Android research refresh (2026-10-25)

This section is a source-based shortlist, not a report of completed Deku Satr benchmarks.

### ML Kit Selfie Segmentation

Google's Android guide lists the beta dependency as approximately 4.5 MB added to app size and documents STREAM_MODE plus raw-size masks. It reports about 25–65 ms latency on a Pixel 4 depending on image size. This is a useful small-runtime baseline to test, but it is designed for person/selfie segmentation and must be evaluated against actual full-screen app frames, including distant and small people. Beta means the API can change and carries no SLA.

Source: https://developers.google.com/ml-kit/vision/selfie-segmentation/android

### LiteRT runtime

As verified against Google's current Android release table on 2026-10-25, LiteRT 2.3.0 is the latest listed CompiledModel release. The current overview recommends CompiledModel for new accelerated on-device inference; older Interpreter use remains available for compatibility. We should select a model artifact independently of the runtime and require CPU fallback where accelerators are unavailable.

Sources:
- https://developers.google.com/edge/litert/android
- https://developers.google.com/edge/litert/overview

### Model-selection gate

No model should be selected based solely on benchmark numbers published for another device or selfie dataset. For the system-screen task, first compare a compact semantic person segmenter and ML Kit on the same labelled display frames. Report recall on small/distant people, boundary leakage, false-positive area, p50/p95 end-to-end latency, peak native memory, sustained frame rate, heat, battery impact, and total distribution size. Review model and training-data licenses before packaging weights.

### Screen capture is a separate engineering risk

The native path now reads reduced screen frames, runs the bundled model, and renders its person mask through a separate touch-transparent overlay. This is an integrated experimental pipeline, not a validated system-wide filter. The app's own overlay may be included in subsequent MediaProjection frames, reprocessing the previous blur and producing ghosting or unstable masks. Physical-device tests must determine whether that feedback occurs and whether it is controllable; unsupported configurations must be labelled rather than advertised as protected.

Official platform references:
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/about/versions/14/behavior-changes-14
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams


## Candidate for multi-person screenshots: LR-ASPP + MobileNetV3-Large

LiteRT Community provides a TorchVision LR-ASPP + MobileNetV3-Large conversion: the FP32 TFLite artifact is listed at 12.9 MB, with a dynamic-weight-INT8 / FP32-activation artifact listed around 3.52 MB. TorchVision reports about 3.22M parameters for the architecture, trained using a COCO subset restricted to VOC categories including person.

Sources:
- https://huggingface.co/litert-community/lraspp_mobilenet_v3_large
- https://huggingface.co/litert-community/lraspp_mobilenet_v3_large/blob/main/lraspp_mobilenet_v3_large.tflite
- https://github.com/pytorch/vision/blob/main/torchvision/models/segmentation/lraspp.py

This is a promising general semantic person-mask candidate, but it is NOT integrated or benchmarked in Satr. The converted model card lacks sufficient deployment details for preprocessing, tensor shapes/layout, person class index, and exact binary/license provenance. We will not ship it until its immutable revision/hash and license are verified, the tensor contract and normalization are confirmed, and CPU execution is validated.

Compare it with ML Kit on the same labelled screen sample set including multiple people, short/long hair, covered heads, different clothing/skin tones, dark screens, small/distant people, occlusion, motion, animation and rotation. Report person recall/miss rate, mask leakage, false-positive area, p50/p95 latency, peak RAM, sustained FPS, temperature, battery and APK size. Published timings on different devices are not directly comparable.

The Settings screen therefore defaults to all people. The current model produces a person/background mask, not a gender label; hair and clothing are not a dependable substitute for a separately validated classifier. Gender-selective blur remains unavailable until a candidate is tested and its error rates are documented.


## Satr 0.4.2 pipeline update (2026-10-25)

This release improves temporal stability and effect selection; it does **not** claim that a replacement model has beaten the current model on a labelled benchmark.

- Keep ML Kit Selfie Segmentation in `STREAM_MODE` with raw-size masks. Google documents stream smoothing, multi-person support, and an approximately 4.5 MB bundled footprint; the API remains beta. See the official Android guide above.
- Raise the frame admission ceiling from 4 fps to about 7 fps (140 ms minimum interval), while preserving single-flight inference and dropping stale frames rather than queueing them. Actual sustained rate remains device-dependent.
- If a single mask briefly collapses to background, reuse the last non-empty mask for at most 650 ms when the mask dimensions match. Reset that cache on session start and display resize. This is a short dropout guard, not temporal tracking; fast-moving subjects can leave the cached region.
- Delay recycling replaced overlay bitmaps to reduce the chance that the renderer reads a bitmap while it is being recycled. Solid-cover mode uses an opaque overlay; Android may block interaction with the app underneath, so the visible Stop control remains important.
- Add blur, pixelation, and solid-cover render modes without adding another runtime dependency. The selected effect is stored locally.

### Model replacement gate remains closed

The current model is still a portrait-oriented person segmenter and cannot infer a person's gender reliably. We have not integrated LR-ASPP/MobileNetV3 or MediaPipe's multi-class model because their end-to-end screen-capture behavior, model artifact provenance/licence, preprocessing/output contract, and low-end-device performance have not yet been validated in this repository. The MediaPipe multiclass model is substantially slower on CPU in Google's published Pixel 6 benchmark, so it is not an automatic upgrade for low-power phones. No 99% accuracy or complete-coverage claim is justified.

The next model comparison must use the same labelled screen/video-frame set and report person recall (including small/distant/occluded subjects), boundary leakage, p50/p95 latency, sustained throughput, peak RAM, thermal behavior, battery draw, and distribution size. Gender-selective filtering is not part of this model contract; the safe default remains all-person segmentation or explicit manual regions.


## Native runtime and media workflow update (2026-10-25)

The APK keeps ML Kit Selfie Segmentation in `STREAM_MODE` for live display frames. Google documents temporal smoothing for stream mode, about 4.5 MB added APK size, and 25–65 ms reference latency on Pixel 4 depending on input size. These are vendor references, not benchmarks of this app.

Added capture profiles:
- Power saver: 480 px long edge, 200 ms minimum inference interval.
- Balanced: 640 px, 140 ms.
- Detail: 720 px, 100 ms.

These expose workload trade-offs, not guaranteed accuracy or fixed FPS. Frames are still dropped while inference is busy. Higher resolution can help small subjects but increases processing cost, heat, and memory. The selected capture size applies on the next session; the interval is read dynamically.

Still-image editing uses the same bundled segmenter in single-image mode, downsamples the selected file before inference, composites the effect in memory, and exports only through Android's document picker. The original is not overwritten, and images are not uploaded. Mask coverage is diagnostic, not proof every person was detected.

The model does **not** infer gender. Hair length, hairstyle, clothing and appearance are unreliable proxies; the privacy default remains to mask all confidently detected people. Per-person targeting should use explicit user selection or validated instance masks, not inferred gender.

Official references:
- https://developers.google.com/ml-kit/vision/selfie-segmentation/android
- https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter
