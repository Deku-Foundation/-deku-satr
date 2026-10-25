package org.dekufoundation.satr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LiteRT/TFLite LR-ASPP + MobileNetV3-Large semantic segmentation.
 *
 * The model's VOC-style label order assigns "person" to class 15.
 * This is semantic segmentation, not per-person instance segmentation or gender recognition.
 */
data class PersonSegmentationMask(
    val width: Int,
    val height: Int,
    val confidence: FloatArray,
)

class LrasppPersonSegmenter(context: Context) : AutoCloseable {
    private lateinit var interpreter: Interpreter
    private lateinit var modelFile: File
    private var usingNnapi = false
    private val inputShape: IntArray
    private val outputShape: IntArray
    private val inputNchw: Boolean
    private val outputNchw: Boolean
    private val inputWidth: Int
    private val inputHeight: Int
    private val outputWidth: Int
    private val outputHeight: Int
    private val outputClasses: Int
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: ByteBuffer
    private lateinit var pixels: IntArray

    init {
        modelFile = File(context.cacheDir, MODEL_FILE)
        if (!modelFile.isFile || sha256(modelFile) != MODEL_SHA256) {
            context.assets.open(MODEL_FILE).use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
        }
        check(modelFile.isFile && sha256(modelFile) == MODEL_SHA256) {
            "The bundled LR-ASPP model failed its SHA-256 check."
        }
        val accelerated = runCatching { createInterpreter(useNnapi = true) }
        if (accelerated.isSuccess) {
            interpreter = accelerated.getOrThrow()
            usingNnapi = true
        } else {
            interpreter = createInterpreter(useNnapi = false)
        }
        require(interpreter.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32) {
            "The LR-ASPP input tensor must be float32."
        }
        require(interpreter.getOutputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32) {
            "The LR-ASPP output tensor must be float32."
        }
        inputShape = interpreter.getInputTensor(0).shape()
        outputShape = interpreter.getOutputTensor(0).shape()
        require(inputShape.size == 4 && inputShape[0] == 1) { "Unsupported model input tensor." }
        inputNchw = inputShape[1] == 3
        inputHeight = if (inputNchw) inputShape[2] else inputShape[1]
        inputWidth = if (inputNchw) inputShape[3] else inputShape[2]
        require(!inputNchw && inputShape[3] == 3 || inputNchw && inputShape[1] == 3) {
            "Unsupported model input channel layout."
        }
        require(inputWidth in 32..1024 && inputHeight in 32..1024) {
            "Unexpected LR-ASPP input dimensions."
        }
        require(outputShape.size == 4 && outputShape[0] == 1) { "Unsupported model output tensor." }
        outputNchw = outputShape[1] == PERSON_CLASS_COUNT
        outputClasses = if (outputNchw) outputShape[1] else outputShape[3]
        outputHeight = if (outputNchw) outputShape[2] else outputShape[1]
        outputWidth = if (outputNchw) outputShape[3] else outputShape[2]
        require(outputClasses == PERSON_CLASS_COUNT && outputWidth > 0 && outputHeight > 0) {
            "Unexpected LR-ASPP class layout."
        }
        val inputCount = inputWidth.toLong() * inputHeight * 3L
        val outputCount = outputWidth.toLong() * outputHeight * outputClasses
        require(inputCount in 1..MAX_TENSOR_FLOATS && outputCount in 1..MAX_TENSOR_FLOATS) {
            "The model tensor exceeds the configured memory limit."
        }
        // Reuse the large direct buffers across frames and tiles to avoid per-frame allocation spikes.
        inputBuffer = ByteBuffer.allocateDirect(inputCount.toInt() * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outputCount.toInt() * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(inputWidth * inputHeight)
    }

    @Synchronized
    fun process(source: Bitmap, tileWideFrames: Boolean = true): PersonSegmentationMask {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        val tiles = SegmentationTilePolicy.plan(source.width, source.height, tileWideFrames)
        if (tiles.size == 1 && tiles[0].left == 0 && tiles[0].top == 0 &&
            tiles[0].width == source.width && tiles[0].height == source.height
        ) {
            return processSingle(source)
        }

        val pixelCount = source.width.toLong() * source.height.toLong()
        require(pixelCount in 1..PersonMaskMath.MAX_MASK_PIXELS.toLong()) {
            "Frame is too large for tiled segmentation."
        }
        val merged = FloatArray(pixelCount.toInt())
        for (tile in tiles) {
            val crop = Bitmap.createBitmap(source, tile.left, tile.top, tile.width, tile.height)
            val mask = try {
                processSingle(crop)
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
            for (y in 0 until tile.height) {
                val maskY = min(mask.height - 1, (y.toLong() * mask.height / tile.height).toInt())
                val targetRow = (tile.top + y) * source.width + tile.left
                val maskRow = maskY * mask.width
                for (x in 0 until tile.width) {
                    val maskX = min(mask.width - 1, (x.toLong() * mask.width / tile.width).toInt())
                    val targetIndex = targetRow + x
                    merged[targetIndex] = max(merged[targetIndex], mask.confidence[maskRow + maskX])
                }
            }
        }
        return PersonSegmentationMask(source.width, source.height, merged)
    }

    private fun processSingle(source: Bitmap): PersonSegmentationMask {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        val scale = minOf(inputWidth.toFloat() / source.width, inputHeight.toFloat() / source.height)
        val resizedWidth = (source.width * scale).roundToInt().coerceIn(1, inputWidth)
        val resizedHeight = (source.height * scale).roundToInt().coerceIn(1, inputHeight)
        val left = (inputWidth - resizedWidth) / 2
        val top = (inputHeight - resizedHeight) / 2

        // Letterbox with the ImageNet mean so padding becomes approximately zero after normalization.
        val inputBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inputBitmap)
        canvas.drawColor(Color.rgb(123, 116, 103))
        val scaled = Bitmap.createScaledBitmap(source, resizedWidth, resizedHeight, true)
        try {
            canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        }

        inputBuffer.clear()
        outputBuffer.clear()
        val input = inputBuffer
        val output = outputBuffer
        val inputFloats = input.asFloatBuffer()
        inputBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        inputBitmap.recycle()
        for (channel in 0..2) {
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val color = pixels[y * inputWidth + x]
                    val raw = when (channel) {
                        0 -> Color.red(color)
                        1 -> Color.green(color)
                        else -> Color.blue(color)
                    }.toFloat() / 255f
                    val mean = MEAN[channel]
                    val normalized = (raw - mean) / STD[channel]
                    val index = if (inputNchw) {
                        channel * inputWidth * inputHeight + y * inputWidth + x
                    } else {
                        (y * inputWidth + x) * 3 + channel
                    }
                    inputFloats.put(index, normalized)
                }
            }
        }
        inputFloats.rewind()

        output.clear()
        try {
            interpreter.run(input, output)
        } catch (error: Exception) {
            if (!usingNnapi) throw error
            val expectedInputShape = inputShape
            val expectedOutputShape = outputShape
            runCatching { interpreter.close() }
            interpreter = createInterpreter(useNnapi = false)
            usingNnapi = false
            require(interpreter.getInputTensor(0).shape().contentEquals(expectedInputShape)) {
                "CPU fallback input tensor does not match the accelerated model."
            }
            require(interpreter.getOutputTensor(0).shape().contentEquals(expectedOutputShape)) {
                "CPU fallback output tensor does not match the accelerated model."
            }
            input.rewind()
            output.clear()
            interpreter.run(input, output)
        }
        output.rewind()
        val logits = output.asFloatBuffer()
        val cropLeft = (left.toFloat() * outputWidth / inputWidth).roundToInt().coerceIn(0, outputWidth - 1)
        val cropTop = (top.toFloat() * outputHeight / inputHeight).roundToInt().coerceIn(0, outputHeight - 1)
        val cropWidth = (resizedWidth.toFloat() * outputWidth / inputWidth).roundToInt()
            .coerceIn(1, outputWidth - cropLeft)
        val cropHeight = (resizedHeight.toFloat() * outputHeight / inputHeight).roundToInt()
            .coerceIn(1, outputHeight - cropTop)
        val confidence = FloatArray(cropWidth * cropHeight)

        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                val ox = x + cropLeft
                val oy = y + cropTop
                var maxLogit = Float.NEGATIVE_INFINITY
                for (classIndex in 0 until outputClasses) {
                    val value = logits.get(outputIndex(classIndex, ox, oy))
                    require(value.isFinite()) { "The model returned a non-finite logit." }
                    if (value > maxLogit) maxLogit = value
                }
                var denominator = 0.0
                var personNumerator = 0.0
                for (classIndex in 0 until outputClasses) {
                    val score = exp((logits.get(outputIndex(classIndex, ox, oy)) - maxLogit).toDouble())
                    denominator += score
                    if (classIndex == PERSON_CLASS_INDEX) personNumerator = score
                }
                confidence[y * cropWidth + x] =
                    if (denominator > 0.0) (personNumerator / denominator).toFloat().coerceIn(0f, 1f) else 0f
            }
        }
        pixels.fill(0)
        return PersonSegmentationMask(cropWidth, cropHeight, confidence)
    }

    private fun createInterpreter(useNnapi: Boolean): Interpreter {
        val options = Interpreter.Options().setNumThreads(3)
        if (useNnapi) options.setUseNNAPI(true)
        return Interpreter(modelFile, options).also { it.allocateTensors() }
    }

    private fun outputIndex(classIndex: Int, x: Int, y: Int): Int =
        if (outputNchw) classIndex * outputWidth * outputHeight + y * outputWidth + x
        else (y * outputWidth + x) * outputClasses + classIndex

    override fun close() {
        interpreter.close()
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    companion object {
        const val MODEL_FILE = "lraspp_mobilenet_v3_large_dynamic_wi8_afp32.tflite"
        const val MODEL_SHA256 = "1a0a31a925f1c8e2abb76e96c4bd4bdcbc625f8389f1029530007fded4ea9ed9"
        private const val PERSON_CLASS_INDEX = 15
        private const val PERSON_CLASS_COUNT = 21
        private const val MAX_TENSOR_FLOATS = 8_000_000L
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
