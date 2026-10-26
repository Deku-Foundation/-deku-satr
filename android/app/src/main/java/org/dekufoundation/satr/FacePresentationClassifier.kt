package org.dekufoundation.satr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Optional, on-device face presentation classifier.
 *
 * It estimates the dataset's female/male appearance label from an aligned face crop.
 * It is not a person's gender identity. Missing/low-confidence faces return UNKNOWN.
 * Load this engine only after the user explicitly selects a gender-targeting policy.
 */
class FacePresentationClassifier(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val detectorModel = verifiedAsset(
        context, FACE_MODEL_FILE, FACE_MODEL_BYTES, FACE_MODEL_SHA256,
    )
    private val classifierModel = verifiedAsset(
        context, CLASSIFIER_MODEL_FILE, CLASSIFIER_MODEL_BYTES, CLASSIFIER_MODEL_SHA256,
    )
    private val detectorSession: OrtSession
    private val classifierSession: OrtSession
    private val detectorInputName: String
    private val classifierInputName: String
    private val detectorInputBitmap = Bitmap.createBitmap(FACE_INPUT_SIZE, FACE_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val detectorCanvas = Canvas(detectorInputBitmap)
    private val detectorRect = Rect()
    private val detectorPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val detectorPixels = IntArray(FACE_INPUT_SIZE * FACE_INPUT_SIZE)
    private val detectorInputBuffer = ByteBuffer.allocateDirect(FACE_INPUT_SIZE * FACE_INPUT_SIZE * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val detectorInputFloats = detectorInputBuffer.asFloatBuffer()
    private val classifierPixels = IntArray(CLASSIFIER_INPUT_SIZE * CLASSIFIER_INPUT_SIZE)
    private val classifierInputBuffer = ByteBuffer.allocateDirect(CLASSIFIER_INPUT_SIZE * CLASSIFIER_INPUT_SIZE * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val classifierInputFloats = classifierInputBuffer.asFloatBuffer()
    private val alignedFace = Bitmap.createBitmap(CLASSIFIER_INPUT_SIZE, CLASSIFIER_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val alignCanvas = Canvas(alignedFace)
    private val alignMatrix = Matrix()
    private val alignPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        detectorSession = createSession(detectorModel)
        classifierSession = createSession(classifierModel)
        detectorInputName = detectorSession.inputNames.firstOrNull()
            ?: error("The face detector has no input tensor.")
        classifierInputName = classifierSession.inputNames.firstOrNull()
            ?: error("The FastFace model has no input tensor.")
        val detectorInfo = detectorSession.inputInfo[detectorInputName]?.info as? ai.onnxruntime.TensorInfo
            ?: error("Face detector input metadata is missing.")
        require(detectorInfo.shape.contentEquals(longArrayOf(1L, 3L, FACE_INPUT_SIZE.toLong(), FACE_INPUT_SIZE.toLong()))) {
            "Face detector input differs from the pinned 640x640 contract."
        }
        val classifierInfo = classifierSession.inputInfo[classifierInputName]?.info as? ai.onnxruntime.TensorInfo
            ?: error("FastFace input metadata is missing.")
        require(classifierInfo.shape.size == 4 &&
            classifierInfo.shape[1] == 3L &&
            classifierInfo.shape[2] == CLASSIFIER_INPUT_SIZE.toLong() &&
            classifierInfo.shape[3] == CLASSIFIER_INPUT_SIZE.toLong()
        ) { "FastFace input differs from the pinned NCHW contract." }
        require(classifierSession.outputNames.contains("gender_logits")) {
            "FastFace gender_logits output is missing."
        }
    }

    @Synchronized
    fun classify(frame: Bitmap, people: List<TrackedPersonBox>): Map<Long, FacePresentationPrediction> {
        require(!frame.isRecycled && frame.width > 0 && frame.height > 0)
        if (people.isEmpty()) return emptyMap()
        val faces = detectFaces(frame)
        if (faces.isEmpty()) return emptyMap()

        val available = people.sortedByDescending { it.confidence }
        val usedFaceIndices = mutableSetOf<Int>()
        val result = linkedMapOf<Long, FacePresentationPrediction>()
        for (person in available) {
            val candidate = faces.withIndex()
                .filter { it.index !in usedFaceIndices && belongsToPerson(it.value, person, frame.width, frame.height) }
                .maxByOrNull { it.value.score }
                ?: continue
            usedFaceIndices += candidate.index
            val face = candidate.value
            if (face.bottom - face.top < MIN_FACE_HEIGHT_PX || face.right - face.left < MIN_FACE_WIDTH_PX) {
                result[person.trackId] = FacePresentationPrediction(PersonPresentation.UNKNOWN, 0f)
                continue
            }
            val prediction = runCatching { classifyFace(frame, face) }
                .getOrElse { FacePresentationPrediction(PersonPresentation.UNKNOWN, 0f) }
            result[person.trackId] = prediction
        }
        return result
    }

    private fun detectFaces(frame: Bitmap): List<FaceCandidate> {
        val scale = min(FACE_INPUT_SIZE.toFloat() / frame.width, FACE_INPUT_SIZE.toFloat() / frame.height)
        val resizedWidth = (frame.width * scale).toInt().coerceIn(1, FACE_INPUT_SIZE)
        val resizedHeight = (frame.height * scale).toInt().coerceIn(1, FACE_INPUT_SIZE)
        val scaleX = resizedWidth.toFloat() / frame.width
        val scaleY = resizedHeight.toFloat() / frame.height

        detectorCanvas.drawColor(Color.BLACK)
        detectorRect.set(0, 0, resizedWidth, resizedHeight)
        detectorCanvas.drawBitmap(frame, null, detectorRect, detectorPaint)
        detectorInputBitmap.getPixels(detectorPixels, 0, FACE_INPUT_SIZE, 0, 0, FACE_INPUT_SIZE, FACE_INPUT_SIZE)
        detectorInputFloats.clear()
        val plane = FACE_INPUT_SIZE * FACE_INPUT_SIZE
        for (channel in 0..2) {
            val offset = channel * plane
            for (index in 0 until plane) {
                val pixel = detectorPixels[index]
                detectorInputFloats.put(
                    offset + index,
                    when (channel) {
                        0 -> Color.blue(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.red(pixel)
                    }.toFloat(),
                )
            }
        }
        detectorInputFloats.position(0)
        val tensor = OnnxTensor.createTensor(
            environment,
            detectorInputFloats,
            longArrayOf(1L, 3L, FACE_INPUT_SIZE.toLong(), FACE_INPUT_SIZE.toLong()),
        )
        try {
            val outputs = detectorSession.run(mapOf(detectorInputName to tensor))
            outputs.use { result ->
                val candidates = mutableListOf<FaceCandidate>()
                for (stride in STRIDES) {
                    val cls = outputFloatBuffer(result, "cls_$stride")
                    val obj = outputFloatBuffer(result, "obj_$stride")
                    val bbox = outputFloatBuffer(result, "bbox_$stride")
                    val landmarks = outputFloatBuffer(result, "kps_$stride")
                    val columns = FACE_INPUT_SIZE / stride
                    val rows = columns
                    for (row in 0 until rows) {
                        for (column in 0 until columns) {
                            val index = row * columns + column
                            val clsScore = cls.get(index).coerceIn(0f, 1f)
                            val objScore = obj.get(index).coerceIn(0f, 1f)
                            val score = sqrt(clsScore * objScore)
                            if (!score.isFinite() || score < FACE_SCORE_THRESHOLD) continue

                            val offset = index * 4
                            val cx = (column + bbox.get(offset)) * stride
                            val cy = (row + bbox.get(offset + 1)) * stride
                            val width = exp(bbox.get(offset + 2).coerceIn(-8f, 8f)) * stride
                            val height = exp(bbox.get(offset + 3).coerceIn(-8f, 8f)) * stride
                            if (!listOf(cx, cy, width, height).all { it.isFinite() } || width <= 0f || height <= 0f) continue
                            val leftInput = cx - width / 2f
                            val topInput = cy - height / 2f
                            val rightInput = cx + width / 2f
                            val bottomInput = cy + height / 2f
                            // Ignore boxes whose centers fall in the black letterbox padding.
                            if (cx !in 0f..resizedWidth.toFloat() || cy !in 0f..resizedHeight.toFloat()) continue
                            val faceLeft = (leftInput / scaleX).coerceIn(0f, frame.width.toFloat())
                            val faceTop = (topInput / scaleY).coerceIn(0f, frame.height.toFloat())
                            val faceRight = (rightInput / scaleX).coerceIn(0f, frame.width.toFloat())
                            val faceBottom = (bottomInput / scaleY).coerceIn(0f, frame.height.toFloat())
                            if (faceRight <= faceLeft || faceBottom <= faceTop) continue
                            val points = FloatArray(10)
                            for (point in 0 until 5) {
                                val lx = landmarks.get(index * 10 + point * 2) + column
                                val ly = landmarks.get(index * 10 + point * 2 + 1) + row
                                // YuNet's landmark head regresses coordinates in feature-map units.
                                points[point * 2] = (lx * stride / scaleX).coerceIn(0f, frame.width.toFloat())
                                points[point * 2 + 1] = (ly * stride / scaleY).coerceIn(0f, frame.height.toFloat())
                            }
                            if (!points.all { it.isFinite() }) continue
                            candidates += FaceCandidate(faceLeft, faceTop, faceRight, faceBottom, points, score)
                        }
                    }
                }
                return nonMaximumSuppression(candidates)
            }
        } finally {
            tensor.close()
            detectorPixels.fill(0)
            detectorInputFloats.clear()
        }
    }

    private fun classifyFace(frame: Bitmap, face: FaceCandidate): FacePresentationPrediction {
        val sourcePoints = floatArrayOf(
            face.landmarks[0], face.landmarks[1],
            face.landmarks[2], face.landmarks[3],
            face.landmarks[4], face.landmarks[5],
        )
        val scale = CLASSIFIER_INPUT_SIZE / 112f
        val targetPoints = floatArrayOf(
            38.2946f * scale, 51.6963f * scale,
            73.5318f * scale, 51.5014f * scale,
            56.0252f * scale, 71.7366f * scale,
        )
        alignMatrix.reset()
        if (!alignMatrix.setPolyToPoly(sourcePoints, 0, targetPoints, 0, 3)) {
            return FacePresentationPrediction(PersonPresentation.UNKNOWN, 0f)
        }
        alignCanvas.drawColor(Color.BLACK)
        alignCanvas.drawBitmap(frame, alignMatrix, alignPaint)
        alignedFace.getPixels(classifierPixels, 0, CLASSIFIER_INPUT_SIZE, 0, 0, CLASSIFIER_INPUT_SIZE, CLASSIFIER_INPUT_SIZE)

        classifierInputFloats.clear()
        val plane = CLASSIFIER_INPUT_SIZE * CLASSIFIER_INPUT_SIZE
        for (channel in 0..2) {
            val mean = IMAGENET_MEAN[channel]
            val std = IMAGENET_STD[channel]
            val offset = channel * plane
            for (index in 0 until plane) {
                val pixel = classifierPixels[index]
                val raw = when (channel) {
                    0 -> Color.red(pixel)
                    1 -> Color.green(pixel)
                    else -> Color.blue(pixel)
                }.toFloat() / 255f
                classifierInputFloats.put(offset + index, (raw - mean) / std)
            }
        }
        classifierInputFloats.position(0)
        val tensor = OnnxTensor.createTensor(
            environment,
            classifierInputFloats,
            longArrayOf(1L, 3L, CLASSIFIER_INPUT_SIZE.toLong(), CLASSIFIER_INPUT_SIZE.toLong()),
        )
        try {
            classifierSession.run(mapOf(classifierInputName to tensor)).use { output ->
                val logitsTensor = output.get("gender_logits").orElseThrow {
                    IllegalStateException("FastFace gender logits are missing.")
                } as OnnxTensor
                val logits = logitsTensor.floatBuffer
                require(logits.remaining() >= 2) { "FastFace returned fewer than two gender logits." }
                val first = logits.get(0)
                val second = logits.get(1)
                if (!first.isFinite() || !second.isFinite()) {
                    return FacePresentationPrediction(PersonPresentation.UNKNOWN, 0f)
                }
                val maxLogit = max(first, second)
                val expFemale = exp((first - maxLogit).toDouble())
                val expMale = exp((second - maxLogit).toDouble())
                val denominator = expFemale + expMale
                if (denominator <= 0.0 || !denominator.isFinite()) {
                    return FacePresentationPrediction(PersonPresentation.UNKNOWN, 0f)
                }
                val femaleProbability = (expFemale / denominator).toFloat()
                val maleProbability = (expMale / denominator).toFloat()
                val confidence = max(femaleProbability, maleProbability)
                if (confidence < MINIMUM_GENDER_CONFIDENCE) {
                    return FacePresentationPrediction(PersonPresentation.UNKNOWN, confidence)
                }
                val presentation = if (maleProbability > femaleProbability) {
                    PersonPresentation.MALE_PRESENTING
                } else {
                    PersonPresentation.FEMALE_PRESENTING
                }
                return FacePresentationPrediction(presentation, confidence)
            }
        } finally {
            tensor.close()
            classifierPixels.fill(0)
            classifierInputFloats.clear()
            alignedFace.eraseColor(Color.BLACK)
        }
    }

    private fun belongsToPerson(face: FaceCandidate, person: TrackedPersonBox, width: Int, height: Int): Boolean {
        val centerX = (face.left + face.right) / (2f * width)
        val centerY = (face.top + face.bottom) / (2f * height)
        val box = person.box
        val personHeight = box.bottom - box.top
        return centerX >= box.left - PERSON_BOX_HORIZONTAL_MARGIN &&
            centerX <= box.right + PERSON_BOX_HORIZONTAL_MARGIN &&
            centerY >= box.top - PERSON_BOX_TOP_MARGIN &&
            centerY <= box.top + personHeight * PERSON_FACE_VERTICAL_FRACTION
    }

    private fun nonMaximumSuppression(faces: List<FaceCandidate>): List<FaceCandidate> {
        val pending = faces.sortedByDescending { it.score }.toMutableList()
        val accepted = mutableListOf<FaceCandidate>()
        while (pending.isNotEmpty() && accepted.size < MAX_FACES) {
            val best = pending.removeAt(0)
            accepted += best
            pending.removeAll { intersectionOverUnion(best, it) > FACE_NMS_THRESHOLD }
        }
        return accepted
    }

    private fun intersectionOverUnion(a: FaceCandidate, b: FaceCandidate): Float {
        val intersectionWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val intersectionHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun outputFloatBuffer(result: OrtSession.Result, name: String): java.nio.FloatBuffer {
        val tensor = result.get(name).orElseThrow { IllegalStateException("Face detector output $name is missing.") } as OnnxTensor
        val expectedCount = when {
            name.endsWith("_8") -> 6_400
            name.endsWith("_16") -> 1_600
            else -> 400
        }
        val featureWidth = when {
            name.startsWith("bbox_") -> 4
            name.startsWith("kps_") -> 10
            else -> 1
        }
        require(tensor.info.shape.size == 3 &&
            tensor.info.shape[0] == 1L &&
            tensor.info.shape[1] == expectedCount.toLong() &&
            tensor.info.shape[2] == featureWidth.toLong()
        ) { "Face detector output shape changed: $name" }
        return tensor.floatBuffer.apply { position(0) }
    }

    private fun createSession(model: File): OrtSession {
        val options = OrtSession.SessionOptions()
        return try {
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { classifierSession.close() }
        runCatching { detectorSession.close() }
        detectorPixels.fill(0)
        classifierPixels.fill(0)
        detectorInputBitmap.eraseColor(Color.BLACK)
        detectorInputBitmap.recycle()
        alignedFace.eraseColor(Color.BLACK)
        alignedFace.recycle()
    }

    private data class FaceCandidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val landmarks: FloatArray,
        val score: Float,
    )

    private fun verifiedAsset(context: Context, name: String, expectedBytes: Long, expectedSha: String): File {
        val file = File(context.cacheDir, name)
        if (file.isFile && file.length() == expectedBytes && sha256(file) == expectedSha) return file
        val temporary = File(context.cacheDir, name + ".download")
        try {
            context.assets.open(name).use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
            check(temporary.length() == expectedBytes && sha256(temporary) == expectedSha) {
                "The packaged model $name failed integrity verification."
            }
            if (file.exists() && !file.delete()) error("Cannot replace cached model $name.")
            if (!temporary.renameTo(file)) error("Cannot install cached model $name.")
            return file
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FACE_MODEL_FILE = "face_detection_yunet_2023mar.onnx"
        const val FACE_MODEL_BYTES = 232_589L
        const val FACE_MODEL_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
        const val CLASSIFIER_MODEL_FILE = "fastface-large-128-int8.onnx"
        const val CLASSIFIER_MODEL_BYTES = 4_008_918L
        const val CLASSIFIER_MODEL_SHA256 = "2834e70884800019972e588fbe95eb73ebe942120a364d8aaa60085ba4f5b9cf"
        const val FACE_INPUT_SIZE = 640
        const val CLASSIFIER_INPUT_SIZE = 128
        const val FACE_SCORE_THRESHOLD = 0.82f
        const val FACE_NMS_THRESHOLD = 0.30f
        const val MINIMUM_GENDER_CONFIDENCE = 0.88f
        const val MIN_FACE_WIDTH_PX = 18f
        const val MIN_FACE_HEIGHT_PX = 18f
        const val PERSON_BOX_HORIZONTAL_MARGIN = 0.035f
        const val PERSON_BOX_TOP_MARGIN = 0.04f
        const val PERSON_FACE_VERTICAL_FRACTION = 0.55f
        const val MAX_FACES = 12
        val STRIDES = intArrayOf(8, 16, 32)
        val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
