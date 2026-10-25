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
