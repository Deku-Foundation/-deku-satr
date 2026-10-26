package org.dekufoundation.satr

/**
 * User-controlled privacy policy over per-instance masks.
 *
 * Optional face-presentation estimates are consumed as model labels, not identity determinations.
 * Missing/low-confidence faces are treated as UNKNOWN and stay blurred in targeted modes.
 * The UI/model adapter also supports explicit per-track correction for the current session.
 */
enum class PersonTargetMode {
    /** Conservative default: blur every detected person. */
    MASK_ALL,

    /** Blur only tracks explicitly selected by the user. */
    MASK_SELECTED_ONLY,

    /** Blur every detected person except tracks explicitly selected by the user. */
    MASK_ALL_EXCEPT_SELECTED,

    /** Keep only high-confidence male-presenting faces visible; blur female and unknown tracks. */
    SHOW_MALE_PRESENTING_ONLY,

    /** Keep only high-confidence female-presenting faces visible; blur male and unknown tracks. */
    SHOW_FEMALE_PRESENTING_ONLY,
}

data class PersonInstanceMask(
    val trackId: Long,
    val width: Int,
    val height: Int,
    val pixels: BooleanArray,
    val confidence: Float,
    val presentation: PersonPresentation = PersonPresentation.UNKNOWN,
    val presentationConfidence: Float = 0f,
    val manualPresentation: PersonPresentation? = null,
)

/** Pure, validated mask composition, ready for an instance-segmentation backend. */
object PersonTargetPolicy {
    private const val MIN_PRESENTATION_CONFIDENCE = 0.88f

    fun composeMask(
        width: Int,
        height: Int,
        instances: List<PersonInstanceMask>,
        mode: PersonTargetMode,
        selectedTrackIds: Set<Long>,
    ): BooleanArray {
        val pixelCount = width.toLong() * height.toLong()
        require(width > 0 && height > 0 && pixelCount in 1..PersonMaskMath.MAX_MASK_PIXELS.toLong()) {
            "Target mask dimensions are invalid or exceed the processing budget."
        }
        require(instances.map { it.trackId }.toSet().size == instances.size) {
            "Each instance must have a unique track ID."
        }
        require(instances.all {
            it.trackId >= 0L &&
                it.width == width &&
                it.height == height &&
                it.pixels.size.toLong() == pixelCount &&
                it.confidence.isFinite() &&
                it.confidence in 0f..1f &&
                it.presentationConfidence.isFinite() &&
                it.presentationConfidence in 0f..1f
        }) { "Every instance must match the frame geometry and have valid confidence." }

        // Never silently expose all people because the selection has not been made yet.
        // The caller should display a warning and keep the conservative all-people policy.
        require(mode != PersonTargetMode.MASK_SELECTED_ONLY || selectedTrackIds.isNotEmpty()) {
            "Select at least one person first or choose to blur all people."
        }

        val availableIds = instances.asSequence().map { it.trackId }.toSet()
        val hasSelectedInstanceInFrame = selectedTrackIds.any { it in availableIds }
        val effectiveMode = if (
            mode == PersonTargetMode.MASK_SELECTED_ONLY && !hasSelectedInstanceInFrame
        ) {
            // Fail closed if selected tracks vanish from the current detections.
            PersonTargetMode.MASK_ALL
        } else {
            mode
        }

        val output = BooleanArray(pixelCount.toInt())
        for (instance in instances) {
            val shouldBlur = when (effectiveMode) {
                PersonTargetMode.MASK_ALL -> true
                PersonTargetMode.MASK_SELECTED_ONLY -> instance.trackId in selectedTrackIds
                PersonTargetMode.MASK_ALL_EXCEPT_SELECTED -> instance.trackId !in selectedTrackIds
                PersonTargetMode.SHOW_MALE_PRESENTING_ONLY -> {
                    val label = instance.manualPresentation ?: instance.presentation.takeIf {
                        instance.presentationConfidence >= MIN_PRESENTATION_CONFIDENCE
                    } ?: PersonPresentation.UNKNOWN
                    label != PersonPresentation.MALE_PRESENTING
                }
                PersonTargetMode.SHOW_FEMALE_PRESENTING_ONLY -> {
                    val label = instance.manualPresentation ?: instance.presentation.takeIf {
                        instance.presentationConfidence >= MIN_PRESENTATION_CONFIDENCE
                    } ?: PersonPresentation.UNKNOWN
                    label != PersonPresentation.FEMALE_PRESENTING
                }
            }
            if (!shouldBlur) continue
            for (index in instance.pixels.indices) {
                if (instance.pixels[index]) output[index] = true
            }
        }
        return output
    }
}


/** Returns whether a target mode needs a face-appearance label in addition to person masks. */
internal fun PersonTargetMode.requiresGenderPresentation(): Boolean =
    this == PersonTargetMode.SHOW_MALE_PRESENTING_ONLY ||
        this == PersonTargetMode.SHOW_FEMALE_PRESENTING_ONLY
