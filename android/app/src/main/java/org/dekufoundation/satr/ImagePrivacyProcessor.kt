package org.dekufoundation.satr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class ProcessedImage(val bitmap: Bitmap, val coveragePercent: Int)

/** Bounded, on-device still-image processing. The source URI is never overwritten. */
object ImagePrivacyProcessor {
    private const val MAX_LONG_EDGE = 1280
    private const val MAX_IMAGE_PIXELS = 1_500_000

    fun process(
        context: Context,
        uri: Uri,
        effect: PrivacyEffect,
        blurRadius: Int,
        threshold: Float,
        maskExpansionRadius: Int,
    ): ProcessedImage {
        val source = decodeBounded(context, uri)
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build(),
        )
        var overlay: Bitmap? = null
        try {
            val mask = Tasks.await(
                segmenter.process(InputImage.fromBitmap(source, 0)),
                30,
                TimeUnit.SECONDS,
            )
            val confidence = copyConfidence(mask)
            val rendered = PrivacyRenderer.render(
                source = source,
                confidence = confidence,
                maskWidth = mask.width,
                maskHeight = mask.height,
                threshold = threshold,
                blurRadius = blurRadius,
                maskExpansionRadius = maskExpansionRadius,
                effect = effect,
            )
            overlay = rendered.overlay
            val composite = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(composite).apply {
                drawBitmap(source, 0f, 0f, null)
                drawBitmap(rendered.overlay, 0f, 0f, null)
            }
            return ProcessedImage(composite, rendered.coveragePercent)
        } finally {
            overlay?.let { if (!it.isRecycled) it.recycle() }
            if (!source.isRecycled) source.recycle()
            segmenter.close()
        }
    }

    private fun decodeBounded(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not open the selected image." }
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image." }
        var sample = 1
        while (
            maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_LONG_EDGE ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_IMAGE_PIXELS
        ) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not reopen image." }
            requireNotNull(BitmapFactory.decodeStream(stream, null, options)) { "Image decode failed." }
        }
    }

    private fun copyConfidence(mask: SegmentationMask): FloatArray {
        val count = mask.width.toLong() * mask.height.toLong()
        require(mask.width > 0 && mask.height > 0 && count in 1..PersonMaskMath.MAX_MASK_PIXELS.toLong()) {
            "Invalid model mask dimensions."
        }
        val buffer = mask.buffer.duplicate().order(ByteOrder.nativeOrder())
        buffer.rewind()
        require(buffer.remaining().toLong() >= count * Float.SIZE_BYTES) { "Incomplete confidence mask." }
        return FloatArray(count.toInt()) { buffer.float.coerceIn(0f, 1f) }
    }
}
