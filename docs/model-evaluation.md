# Model evaluation

Updated: 2026-10-26. This is a source-based shortlist, not a claim that every candidate passed a Deku Satr benchmark.

## Current Android backend (v0.6.2)

RTMDet-Ins-Tiny ONNX is now the only bundled model. The immutable upstream revision is `e5218e8d47d7c50077f27f9893a28e0ef2ad5656`; the model is 24,032,356 bytes and its SHA-256 is `99ee670820c4493aa13e20578b6a8e39781c319846dc41796a77765734beac5c`. The build verifies the digest and packages the model plus its Apache license/NOTICE. ONNX Runtime runs CPU-only (two intra-op threads) because the export's intended provider is CPU and NNAPI startup has not been validated across devices.

The model is used for live frames and selected still images. The UI supports per-instance masking via short-lived tracks, but it does **not** automatically classify gender; the selected person is corrected manually within the active session. The smaller model reduces bundled weight size and the prior LR-ASPP model/runtime were removed. This does not establish higher recall or 99% accuracy. CI smoke tests validate the ONNX graph and tensor contract only; real-phone latency, memory, thermal behavior and screen-frame recall remain release gates.

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
| TorchVision LR-ASPP + MobileNetV3-Large, LiteRT conversion | LiteRT Community lists a dynamic weight-only INT8 / FP32-activation artifact at about 3.52 MB. | **Archived and removed from the APK in v0.6.2**; retained here as historical model research only. |
| RTMDet-Ins-S ONNX | A larger model variant was tested as a reference; it weighs ~43.24 MB and had a 186.96 ms zero-input CPU smoke result on a hosted runner. | Superseded by RTMDet-Ins-Tiny for app distribution. Hosted CPU smoke timing is not phone performance. |
| RTMDet-Ins-Tiny ONNX | Pinned model from the same Apache-2.0 repository; 24,032,356 bytes; SHA-256 checked at build. A hosted zero-input CPU graph smoke test succeeded and produced six 640×640 float masks (~9.38 MiB output) on that test input. | **Active v0.6.2 backend**. The smoke test is not a labeled accuracy result or a phone benchmark. |

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

The Android app now uses RTMDet-Ins-Tiny through ONNX Runtime for both live frames and still images. The artifact is downloaded at build time from a pinned revision, SHA-256-checked, and included in the APK; the app does not fetch weights at runtime. The backend creates per-person masks for manual target selection. It is experimental and has not passed a labeled screen-frame benchmark or real-device test.

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

The current RTMDet-Tiny model can segment individual people but has no gender output. The product goal of gender-targeted masking still requires a face detection/alignment stage and a separate gender-appearance classifier, along with an uncertain/manual-correction state. Do not infer gender from hair length, clothing, facial hair, or hairstyle; those are unreliable proxies. No 99% accuracy or complete-coverage claim is justified.

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

Historical LR-ASPP experiment: v0.6.2 removes `litert-community/lraspp_mobilenet_v3_large_dynamic_wi8_afp32.tflite` and its LiteRT dependency from the app. The current packaged model is RTMDet-Ins-Tiny as specified in the Current Android backend section.

Current runtime: ONNX Runtime Android CPU, two intra-op threads and one inter-op thread. RTMDet-Tiny uses 640×640 BGR normalization and yields `dets`, `labels` and per-instance `masks`. Live frames and still images share the same instance segmenter; selected masks are composited through the existing blur/pixelate/cover renderer.

Important caveats:
- The artifact's model card does not declare an explicit license field. The integration is on the dev/evaluation branch; do not publish a release APK until model provenance and distribution rights are reviewed.
- Historical LR-ASPP note: the publisher's LiteRT GPU compatibility statement did not establish Satr CPU/NNAPI performance, and that backend is no longer included in the app.
- Semantic segmentation identifies the person class but does not provide separate per-person masks or gender. Hairstyle, hair length and clothing are not gender ground truth.
- No 99% claim is supported. CI can prove compilation and unit tests, not model recall or stable full-screen behavior.


## Per-person targeting decision (2026-10-25)

The product requirement “men see men, women see women” is not implemented by the current RTMDet-Tiny model: it supplies per-person masks, not a gender label. A separate face-classification pipeline must distinguish probable male/female appearance only when a clear face exists and expose Unknown/manual correction when it does not. Hair length, facial hair, clothing, body shape and presentation are not dependable labels for gender. A false classification would leave the wrong people visible or blur the wrong people.

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

Superseded decision (historical): LR-ASPP was removed in v0.6.2. RTMDet-Ins-Tiny is now the active per-instance backend, with explicit user selection and short-lived tracking. Gender targeting remains a separate, unvalidated milestone.

Sources:
- https://github.com/ultralytics/ultralytics/blob/main/docs/en/models/yolo11.md
- https://huggingface.co/EdgeFirst/yolo11-seg/tree/main/tflite
- https://huggingface.co/STMicroelectronics/yolov11n_seg


## Frame stability and next model candidate (2026-10-25)

The live path now applies a small, per-pixel confidence blend to adjacent masks only when geometry is unchanged and the gap is at most 450 ms. The current inference has 72% weight; the previous map contributes 28%. This is boundary stabilization, not a tracker: a fully empty fresh pixel becomes 0.28 after one blend and stays below the default 0.42 mask threshold, so a departed person is not kept as an opaque silhouette. The blend mutates the fresh confidence buffer in place and retains one prior map to limit allocation churn. Geometry changes and new sessions reset the state. Unit tests cover the blend, disappearance, elapsed-time limit, dimensions, and reset behavior.

The screen-resize path also respects the selected capture profile instead of silently returning to the default 640 px after rotation. This closes a configuration mismatch; it does not imply fixed frame rate or eliminate all capture-overlay feedback.

### Instance model candidate

RTMDet-Ins-S was evaluated as a larger reference variant but is no longer bundled. Its published COCO/TensorRT numbers are not Android-phone measurements. RTMDet-Ins-Tiny is the active smaller variant, pinned to a fixed revision and Apache-2.0 model files; phone latency, memory and heat still need measurement.

Sources:
- https://github.com/open-mmlab/mmdetection/blob/main/configs/rtmdet/README.md
- https://www.libreyolo.com/docs/models/rtmdet

### Selective gender/presentation targeting

An instance-mask model alone cannot decide which people the user wants blurred. Each instance needs an associated, independently evaluated face or appearance classifier, and uncertain cases must use a conservative fallback. FastFace publishes MobileNetV3 ONNX variants with Apache-2.0 metadata; the maintainer-reported results differ across datasets and crop/model sizes, so those are a candidate for a controlled comparison, not a validated Satr result. A face model also misses people when faces are too small, turned away, covered, or absent.

Source: https://huggingface.co/iFurySt/fastface

No gender-selective mode is exposed as functional yet. The current mask is semantic and unions all persons, so it cannot correctly apply one blur policy per individual. Before enabling “blur men / blur women” modes, the instance model and classifier must be combined and tested on labeled phone-screen frames—including long-haired men, short-haired women, multiple/distant people, faces hidden, motion blur, and different brightness/orientation. The default must stay “blur all detected people” whenever a per-instance classification is low confidence or missing. Do not market 99% accuracy without independent, held-out measurements.


## Live-path optimization (0.5.3)

Historical LR-ASPP implementation note (not used in v0.6.2): the decoder checked the person logit against the maximum class logit before its 21-class softmax. Those optimizations only applied to the removed semantic backend.

The low-latency capture profile uses a 360 px long edge, an 80 ms minimum admission interval, and one full-frame inference without tiling. It reduces model calls per captured frame but loses spatial detail on small or distant people. The configured interval is a throttle, not a promised frame rate. Inference time, rendering and device load determine actual FPS; no sub-millisecond or 99% claim is made.

During warm-up, the full-screen green placeholder is gone. The control panel says filtering is not active until the first successful mask. A real inference error or stale session still uses an opaque fail-closed surface, now charcoal. The control panel starts compact and reveals details on tap.

Gender classification is not added. The current semantic mask cannot separate individuals. Selective targeting requires a compatible instance mask model and explicit user selection; see issue #11.


## Allocation reduction (0.5.4)

Historical LR-ASPP optimization note (not used in v0.6.2): that segmenter reused one input bitmap and tensor buffers. Current RTMDet-Tiny similarly reuses its input bitmap/buffers and clears the pixel scratch array after each inference.

This is a code-level allocation reduction. No latency or memory percentage is claimed until measured on physical devices.


## Per-person model shortlist (2026-10-25, source review)

The generic semantic mask was replaced in v0.6.2: RTMDet-Ins-Tiny emits separate masks per detected person, and the session-local user policy chooses which masks to composite. The remaining unmet milestone is a well-calibrated face-appearance classifier for optional gender-targeted policy, with Unknown and manual correction.

| Candidate | Model footprint | Public benchmark / behavior | License and decision |
|---|---:|---|---|
| RTMDet-Ins-S, ONNX | ~43 MB | COCO val2017 instance segmentation: mask mAP 38.7; output contains person boxes and individual masks; conversion guide recommends score threshold 0.30 and mask-overlap NMS 0.85 | Apache-2.0. Integrated as an optional manual-targeting backend. Android latency, memory, and thermal behavior remain unmeasured. |
| RTMDet-Ins-Tiny, ONNX | ~23 MB | COCO mask mAP 35.4; fastest variant in that model set, but lower confidence on crowded scenes | Apache-2.0. Fallback candidate for low-memory devices after measuring accuracy and sustained speed. |
| YOLO26n-seg, TFLite INT8 | ~3.18 MB | EdgeFirst reports COCO mask mAP 33.19 for its Nano reference | We will not bundle in this MIT repository as-is: weights are AGPL-3.0 and the model card/validation assets include CC BY-NC 4.0 terms. |
| YOLACT-ResNet50, LiteRT | ~125 MB | Per-instance masks; reference graph latency around 41 ms on Pixel 8a GPU | MIT weights, but exceeds the size budget by a large margin. |
| MobileSAM | ~40.7 MB | Promptable point/box mask; it does not automatically discover every person, and CPU timings are not competitive for every-frame screening | Apache-2.0. Useful for user-guided still-image selection, not the default live video segmenter. |
| EfficientDet-Lite0 INT8 | ~4.4 MB | COCO object detector, ~29.31 ms CPU reference on Pixel 6 | Small auxiliary detector, but gives boxes rather than per-person masks; alone it is not sufficient for precise body-shaped blur. |

Sources: 
- RTMDet-Ins-ONNX model cards, dimensions, license and output contract: https://huggingface.co/tori29umai/rtmdet-ins-onnx-person-masks
- RTMDet tiny/s/supplementary COCO results: https://huggingface.co/spaces/facebook/sapiens-pose/blob/main/external/det/configs/rtmdet/README.md
- YOLO26-seg model card and licensing: https://huggingface.co/EdgeFirst/yolo26-seg
- YOLACT LiteRT card: https://huggingface.co/litert-community/YOLACT-ResNet50-LiteRT
- MobileSAM license and task behavior: https://www.libreyolo.com/docs/models/mobilesam
- EfficientDet-Lite0 size/latency reference: https://developers.google.com/edge/litert/libraries/modify/object_detection
- ONNX Runtime Android release stream: https://github.com/microsoft/onnxruntime/releases

These published metrics are not evidence of 99% recall on phone screenshots. COCO segmentation average precision is a general-object benchmark; our own test set must include multiple people, small faces/bodies, partial occlusion, hair styles and lengths that differ from gender presentation, video motion, unusual illustration styles, compression, and screen rotation.

## Targeting and classifier boundary

Automatic gender inference from faces or appearance is not part of the person-mask model. Gender cannot be reliably inferred from hair length, clothing, facial hair, or body shape; facial-attribute research also reports subgroup disparities across datasets and architectures. The target policy is user-controlled: explicitly select the detected person(s) to blur, blur all except selected person(s), or conservatively blur all detected people. This avoids treating a model guess as a reliable identity label. If the user wants an entirely automatic gender mode, it would require separate product, ethical, bias and benchmark review rather than being silently folded into a person segmenter.

The repository now contains a pure, unit-tested composition policy for per-instance masks. It is infrastructure only; the UI does not yet expose selective person targeting until an instance-mask backend is integrated and its performance and asset integrity are verified.


## Manual correction workflow (2026-10-25)

The Settings screen now exposes the lightweight semantic model and the optional RTMDet-Ins-S instance model. The latter is about 43 MB and yields separate person masks so the user can select which tracked instances to blur or keep visible. A selection is applied during the current live session and follows short-lived track IDs; it is not persistent identity recognition.

The user can choose “blur all”, “blur selected only”, or “blur all except selected”. If selected tracks disappear, selected-only mode fails closed to blur all detected instances. This is a manual targeting workflow, not gender recognition. The app does not infer gender from faces, hair, clothes, or body shape. The current correction action changes the target policy; it does not fine-tune or retrain model weights. Actual online learning would need an explicit opt-in, a carefully designed local training dataset, deletion controls, and on-device compute evaluation.

RTMDet-Ins-tiny remains a smaller future option (~24 MB; COCO mask mAP 35.4) if device testing shows the S variant is too heavy. The S variant is currently selected for its stronger published COCO mask mAP (38.7). Published metrics use COCO and must not be interpreted as recall on phone screenshots. No 99% accuracy claim is made.

Source: https://huggingface.co/tori29umai/rtmdet-ins-onnx-person-masks
