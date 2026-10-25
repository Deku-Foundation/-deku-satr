package org.dekufoundation.satr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RTMDet-Ins-S per-person masks. It does not classify gender or identify people.
 * The model is pinned and verified before ONNX Runtime opens it.
 */
class RtmdetPersonSegmenter(context: Context) : PersonSegmentationEngine {
    private val environment = OrtEnvironment.getEnvironment()
    private val modelFile: File
    private var session: OrtSession
    private var usingNnapi = false
    private val inputName: String
    private val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputBitmap)
    private val inputRect = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixelScratch = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val inputFloats = inputBuffer.asFloatBuffer()

    init {
        modelFile = File(context.cacheDir, MODEL_FILE)
        if (!modelFile.isFile || modelFile.length() != MODEL_BYTES || sha256(modelFile) != MODEL_SHA256) {
            context.assets.open(MODEL_FILE).use { source ->
                FileOutputStream(modelFile).use { destination -> source.copyTo(destination) }
            }
        }
        check(modelFile.isFile && modelFile.length() == MODEL_BYTES && sha256(modelFile) == MODEL_SHA256) {
            "RTMDet-Ins-S failed its model size or SHA-256 check."
        }
        val accelerated = runCatching { createSession(preferNnapi = true) }
        if (accelerated.isSuccess) {
            session = accelerated.getOrThrow()
            usingNnapi = true
        } else {
            session = createSession(preferNnapi = false)
        }
        inputName = session.inputNames.first()
        val inputInfo = session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
            ?: error("RTMDet input tensor metadata is missing.")
        require(inputInfo.shape.contentEquals(longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))) {
            "RTMDet input shape differs from the pinned model contract."
        }
    }

    @Synchronized
    override fun process(source: Bitmap, tileWideFrames: Boolean): PersonSegmentationMask {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        // The upstream contract is 640-square, BGR normalization in 0..255 space, and
        // top-left anchored padding with [114,114,114] on the right and bottom.
        val plan = RtmdetGeometry.plan(source.width, source.height, INPUT_SIZE)
        inputCanvas.drawColor(Color.rgb(114, 114, 114))
        inputRect.set(0, 0, plan.resizedWidth, plan.resizedHeight)
        inputCanvas.drawBitmap(source, null, inputRect, paint)
        inputBitmap.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        inputFloats.clear()
        val planeSize = INPUT_SIZE * INPUT_SIZE
        for (channel in 0..2) {
            for (index in 0 until planeSize) {
                val pixel = pixelScratch[index]
                val raw = when (channel) {
                    0 -> Color.blue(pixel)
                    1 -> Color.green(pixel)
                    else -> Color.red(pixel)
                }.toFloat()
                inputFloats.put(channel * planeSize + index, (raw - MEAN_BGR[channel]) / STD_BGR[channel])
            }
        }
        inputFloats.position(0)
        val tensor = OnnxTensor.createTensor(
            environment,
            inputFloats,
            longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        try {
            return try {
                runInference(session, tensor, source, plan)
            } catch (nnapiError: Exception) {
                if (!usingNnapi) throw nnapiError
                runCatching { session.close() }
                session = createSession(preferNnapi = false)
                usingNnapi = false
                runInference(session, tensor, source, plan)
            }
        } finally {
            tensor.close()
            pixelScratch.fill(0)
            inputFloats.clear()
        }
    }

    private fun runInference(
        activeSession: OrtSession,
        inputTensor: OnnxTensor,
        source: Bitmap,
        plan: RtmdetResizePlan,
    ): PersonSegmentationMask {
        activeSession.run(mapOf(inputName to inputTensor)).use { result ->
            val dets = result.get("dets").orElseThrow { IllegalStateException("RTMDet dets output is missing.") } as OnnxTensor
            val labels = result.get("labels").orElseThrow { IllegalStateException("RTMDet labels output is missing.") } as OnnxTensor
            val masks = result.get("masks").orElseThrow { IllegalStateException("RTMDet masks output is missing.") } as OnnxTensor
            val detShape = dets.info.shape
            val labelShape = labels.info.shape
            val maskShape = masks.info.shape
            require(detShape.size == 3 && detShape[0] == 1L && detShape[2] == 5L)
            require(labelShape.size == 2 && labelShape[0] == 1L)
            require(maskShape.size == 4 && maskShape[0] == 1L &&
                maskShape[2] == INPUT_SIZE.toLong() && maskShape[3] == INPUT_SIZE.toLong()) {
                "RTMDet output tensors differ from the pinned model contract."
            }
            val detectionCount = min(detShape[1].toInt(), min(labelShape[1].toInt(), maskShape[1].toInt()))
            val detValues = dets.floatBuffer.apply { position(0) }
            val labelValues = labels.longBuffer.apply { position(0) }
            val maskValues = masks.floatBuffer.apply { position(0) }
            val candidates = (0 until detectionCount).mapNotNull { index ->
                val offset = index * 5
                val score = detValues.get(offset + 4)
                if (labelValues.get(index) != PERSON_CLASS_ID || !score.isFinite() || score < SCORE_THRESHOLD) {
                    null
                } else {
                    val box = plan.toNormalizedBox(
                        detValues.get(offset),
                        detValues.get(offset + 1),
                        detValues.get(offset + 2),
                        detValues.get(offset + 3),
                    ) ?: return@mapNotNull null
                    Candidate(index, box, score)
                }
            }.sortedByDescending { it.score }.take(MAX_CANDIDATES)

            val accepted = mutableListOf<SegmentedPersonInstance>()
            val unionMask = BooleanArray(source.width * source.height)
            val maskPlaneSize = INPUT_SIZE * INPUT_SIZE
            for (candidate in candidates) {
                val offset = candidate.index * maskPlaneSize
                val rawMask = FloatArray(maskPlaneSize) { maskValues.get(offset + it) }
                val pixels = RtmdetGeometry.sampleMask(rawMask, plan)
                rawMask.fill(0f)
                val area = pixels.count { it }
                if (area == 0) continue
                var duplicate = false
                for (previous in accepted) {
                    val previousArea = previous.pixels.count { it }
                    val smallerArea = min(area, previousArea)
                    if (smallerArea == 0) continue
                    var intersection = 0
                    for (index in pixels.indices) {
                        if (pixels[index] && previous.pixels[index]) intersection++
                    }
                    if (intersection.toFloat() / smallerArea >= MASK_OVERLAP_THRESHOLD) {
                        duplicate = true
                        break
                    }
                }
                if (duplicate) {
                    pixels.fill(false)
                    continue
                }
                accepted += SegmentedPersonInstance(candidate.box, candidate.score, pixels)
                for (index in pixels.indices) if (pixels[index]) unionMask[index] = true
                if (accepted.size >= MAX_PERSON_INSTANCES) break
            }
            val confidence = FloatArray(unionMask.size) { if (unionMask[it]) 1f else 0f }
            return PersonSegmentationMask(source.width, source.height, confidence, accepted)
        }
    }

    private data class Candidate(
        val index: Int,
        val box: NormalizedPersonBox,
        val score: Float,
    )

    private fun createSession(preferNnapi: Boolean): OrtSession {
        val options = OrtSession.SessionOptions()
        return try {
            options.setIntraOpNumThreads(3)
            options.setInterOpNumThreads(1)
            if (preferNnapi) options.addNnapi()
            environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { session.close() }
        pixelScratch.fill(0)
        inputBitmap.eraseColor(Color.TRANSPARENT)
        inputBitmap.recycle()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODEL_FILE = "rtmdet-ins_s_640x640.onnx"
        const val MODEL_SHA256 = "9cd1787fbf3eb2bd64cc2d8154f41c036f5484b85599e1cbe0227a2dc5a4a08b"
        const val MODEL_BYTES = 43_236_976L
        const val INPUT_SIZE = 640
        const val PERSON_CLASS_ID = 0L
        const val SCORE_THRESHOLD = 0.30f
        const val MASK_OVERLAP_THRESHOLD = 0.85f
        const val MAX_CANDIDATES = 16
        const val MAX_PERSON_INSTANCES = 8
        val MEAN_BGR = floatArrayOf(103.53f, 116.28f, 123.675f)
        val STD_BGR = floatArrayOf(57.375f, 57.12f, 58.395f)
    }
}
