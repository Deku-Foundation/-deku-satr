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
| TorchVision LR-ASPP + MobileNetV3-Large, LiteRT conversion | LiteRT Community lists a dynamic weight-only INT8 / FP32-activation artifact at about 3.52 MB; the file is pinned by SHA-256 in Satr. | **Integrated on dev as an experimental candidate** using LiteRT/TFLite Interpreter. Accuracy, device performance and distribution licensing remain release gates. |
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

The Android APK now uses LR-ASPP + MobileNetV3-Large through LiteRT/TFLite Interpreter. The model artifact is downloaded during Gradle builds from a pinned revision and checked against SHA-256 before packaging. Live frames and still images share the same engine. This is an experimental integration and has not yet passed a labelled benchmark or real-device test.

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

This candidate is now integrated experimentally on `dev` with an immutable upstream revision, a SHA-256-verified model artifact, runtime tensor-shape checks, ImageNet normalization, the VOC person-class mapping, and CPU fallback if NNAPI inference fails. This verifies packaging and runtime compatibility only—not detection accuracy. The app still requires license review and a labelled screen/video benchmark before production release.

Compare it with ML Kit on the same labelled screen sample set including multiple people, short/long hair, covered heads, different clothing/skin tones, dark screens, small/distant people, occlusion, motion, animation and rotation. Report person recall/miss rate, mask leakage, false-positive area, p50/p95 latency, peak RAM, sustained FPS, temperature, battery and APK size. Published timings on different devices are not directly comparable.

The Settings screen therefore defaults to all people. The current model produces a person/background mask, not a gender label; hair and clothing are not a dependable substitute for a separately validated classifier. Gender-selective blur remains unavailable until a candidate is tested and its error rates are documented.


## Satr 0.4.2 pipeline update (2026-10-25)

This release improves temporal stability and effect selection; it does **not** claim that a replacement model has beaten the current model on a labelled benchmark.

- Keep ML Kit Selfie Segmentation in `STREAM_MODE` with raw-size masks. Google documents stream smoothing, multi-person support, and an approximately 4.5 MB bundled footprint; the API remains beta. See the official Android guide above.
- Raise the frame admission ceiling from 4 fps to about 7 fps (140 ms minimum interval), while preserving single-flight inference and dropping stale frames rather than queueing them. Actual sustained rate remains device-dependent.
- If a single mask briefly collapses to background, reuse the last non-empty mask for at most 650 ms when the mask dimensions match. Reset that cache on session start and display resize. This is a short dropout guard, not temporal tracking; fast-moving subjects can leave the cached region.
- Delay recycling replaced overlay bitmaps to reduce the chance that the renderer reads a bitmap while it is being recycled. Solid-cover mode uses an opaque overlay; Android may block interaction with the app underneath, so the visible Stop control remains important.
- Add blur, pixelation, and solid-cover render modes without adding another runtime dependency. The selected effect is stored locally.

### Historical model replacement gate (superseded by 0.5.1 integration)

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


## Active model state (0.5.1, 2026-10-25)

The dev branch has switched from ML Kit Selfie Segmentation to `litert-community/lraspp_mobilenet_v3_large_dynamic_wi8_afp32.tflite` at immutable revision `ac7d12fd05b7542cd884c85968b3193c8f8e4fa7`. Artifact size is 3,519,728 bytes; SHA-256 is `1a0a31a925f1c8e2abb76e96c4bd4bdcbc625f8389f1029530007fded4ea9ed9`. Gradle verifies this digest before packaging.

Runtime: LiteRT/TFLite Interpreter 1.4.2, 3 threads with NNAPI requested, tensor layout validation, ImageNet RGB normalization, aspect-preserving letterbox, and VOC person class index 15. The shared mask is converted to a confidence map and then passed through the existing threshold/dilation/effect pipeline. Still images use the same segmenter.

Important caveats:
- The artifact's model card does not declare an explicit license field. The integration is on the dev/evaluation branch; do not publish a release APK until model provenance and distribution rights are reviewed.
- The artifact was validated by its publisher with the LiteRT GPU compatibility profile; Satr's own CPU/NNAPI behavior is not yet benchmarked. Inference compatibility and performance must be confirmed on physical devices.
- Semantic segmentation identifies the person class but does not provide separate per-person masks or gender. Hairstyle, hair length and clothing are not gender ground truth.
- No 99% claim is supported. CI can prove compilation and unit tests, not model recall or stable full-screen behavior.


## Per-person targeting decision (2026-10-25)

The product requirement “men see men, women see women” cannot be met by the current LR-ASPP model: it emits one semantic person mask, not one mask per individual, and has no gender output. Hair length, facial hair, clothing, body shape, and presentation are not dependable labels for gender. A false classification would leave the wrong people visible or blur the wrong people.

A current lightweight instance-segmentation candidate is YOLO26n-seg. The published EdgeFirst TFLite INT8 artifact is about 3.18 MB and the model card reports 33.25% mask mAP@[.5:.95] on COCO val2017 for the Nano FP32 reference. However, the weights remain AGPL-3.0, while the repository's code is MIT; the model card/metadata also has separate CC BY-NC 4.0 terms. It must not be bundled into this app without a deliberate licensing decision and compliance review. Published COCO metrics are not evidence of 99% recall on phone screenshots or videos.

Sources:
- https://huggingface.co/EdgeFirst/yolo26-seg
- https://huggingface.co/EdgeFirst/yolo26-seg/tree/main/tflite

Until a compatible instance-segmentation model is selected and validated, the shipped behavior stays “mask all detected people.” The correct next design for selective targeting is instance masks plus explicit user selection or user-defined labels, with temporal tracking between frames. Do not automatically infer gender from appearance. The model must be benchmarked on screen captures including distant people, long-haired men, short-haired women, occlusion, motion, and varied display scaling before enabling selective policies.


## Aspect-aware multi-crop inference (2026-10-25)

The active LR-ASPP engine now optionally splits very tall or wide frames into overlapping near-square crops before inference. A fixed square input otherwise letterboxes a portrait display heavily (for example, a 360×720 frame maps its full content to roughly 260×520 pixels when the model input is square). Overlapping crops let the model see a larger subject scale and reduce the chance that people near a tile boundary are missed. The merged confidence map takes the maximum confidence in overlapping areas.

- Power Saver: tiling disabled to bound compute.
- Balanced: tiling enabled, 180 ms minimum frame interval.
- Detail: tiling enabled, 140 ms minimum frame interval.

Tiling may require two or more model runs per captured frame, so it can reduce the achieved frame rate and increase heat on slower devices. These are workload settings, not accuracy guarantees. Unit tests cover tile geometry; real-device latency, missed-person rate and boundary quality still need measurement.

### Instance-segmentation candidate and licensing

YOLO11n-seg is a stronger task fit for selecting individual people because it returns instance masks. Upstream documentation reports 32.0 mask mAP on COCO for YOLO11n-seg at 640px and 2.9M parameters; this is a published benchmark, not a Satr benchmark. The available EdgeFirst int8 TFLite artifact is about 3.23 MB but is marked AGPL-3.0. Satr's repository is MIT-licensed, so this artifact must not be bundled as if it were license-compatible without an explicit licensing decision. The STMicroelectronics conversion lists a separate SLA0044 license, so it is not an automatic substitute either.

Current decision: retain the small LR-ASPP model for now, add aspect-aware tiled inference, and keep the instance-segmentation model as a gated candidate until a compatible model artifact, preprocessing/output contract, and on-device benchmark are verified. Do not infer gender from appearance; per-person selection requires instance masks and explicit user selection/tracking.

Sources:
- https://github.com/ultralytics/ultralytics/blob/main/docs/en/models/yolo11.md
- https://huggingface.co/EdgeFirst/yolo11-seg/tree/main/tflite
- https://huggingface.co/STMicroelectronics/yolov11n_seg
