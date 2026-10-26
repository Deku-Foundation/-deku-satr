package org.dekufoundation.satr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri

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
        val segmenter: PersonSegmentationEngine = RtmdetPersonSegmenter(context)
        var overlay: Bitmap? = null
        try {
            val mask = segmenter.process(source)
            val rendered = PrivacyRenderer.render(
                source = source,
                confidence = mask.confidence,
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
}
