package org.dekufoundation.satr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri

data class ProcessedImage(
    val bitmap: Bitmap,
    val coveragePercent: Int,
    val targetModeApplied: PersonTargetMode,
    val usedFailClosedFallback: Boolean,
)

/** Bounded, local still-image processing. The original URI is never overwritten. */
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
        targetMode: PersonTargetMode = PersonTargetMode.MASK_ALL,
    ): ProcessedImage {
        val source = decodeBounded(context, uri)
        var segmenter: PersonSegmentationEngine? = null
        var classifier: FacePresentationClassifier? = null
        var overlay: Bitmap? = null
        try {
            segmenter = RtmdetPersonSegmenter(context)
            val mask = segmenter.process(source, tileWideFrames = true)
            val genderMode = targetMode.requiresGenderPresentation()
            // Track IDs belong to a live session and are not transferable to a still image.
            // For still-image editing, keep all people masked except explicit gender-target modes.
            val mode = if (genderMode) targetMode else PersonTargetMode.MASK_ALL
            var fallback = !genderMode && targetMode != PersonTargetMode.MASK_ALL

            val tracks = mask.instances.mapIndexed { index, instance ->
                TrackedPersonBox(
                    trackId = index.toLong(),
                    box = instance.box,
                    confidence = instance.confidence,
                )
            }
            val predictions = if (genderMode && tracks.isNotEmpty()) {
                try {
                    classifier = FacePresentationClassifier(context)
                    classifier.classify(source, tracks)
                } catch (_: OutOfMemoryError) {
                    fallback = true
                    emptyMap()
                } catch (_: Exception) {
                    fallback = true
                    emptyMap()
                }
            } else emptyMap()

            val instances = mask.instances.mapIndexed { index, instance ->
                val prediction = predictions[index.toLong()]
                    ?: FacePresentationPrediction(PersonPresentation.UNKNOWN, 0f)
                PersonInstanceMask(
                    trackId = index.toLong(),
                    width = source.width,
                    height = source.height,
                    pixels = instance.pixels,
                    confidence = instance.confidence,
                    presentation = prediction.presentation,
                    presentationConfidence = prediction.confidence,
                )
            }

            // A gender-filter request with no person masks is ambiguous: cover the full image
            // rather than assume that no one needs protection.
            val composed = if (genderMode && instances.isEmpty()) {
                fallback = true
                BooleanArray(source.width * source.height) { true }
            } else {
                PersonTargetPolicy.composeMask(
                    width = source.width,
                    height = source.height,
                    instances = instances,
                    mode = mode,
                    selectedTrackIds = emptySet(),
                )
            }
            val confidence = FloatArray(composed.size) { if (composed[it]) 1f else 0f }
            val rendered = PrivacyRenderer.render(
                source = source,
                confidence = confidence,
                maskWidth = source.width,
                maskHeight = source.height,
                threshold = threshold.coerceIn(0f, 1f),
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
            return ProcessedImage(
                bitmap = composite,
                coveragePercent = rendered.coveragePercent,
                targetModeApplied = mode,
                usedFailClosedFallback = fallback,
            )
        } finally {
            overlay?.let { if (!it.isRecycled) it.recycle() }
            classifier?.close()
            segmenter?.close()
            if (!source.isRecycled) source.recycle()
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
