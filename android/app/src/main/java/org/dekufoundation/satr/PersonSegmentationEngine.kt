package org.dekufoundation.satr

import android.graphics.Bitmap

/** A person mask is expressed in original-frame coordinates. */
data class SegmentedPersonInstance(
    val box: NormalizedPersonBox,
    val confidence: Float,
    val pixels: BooleanArray,
)

/** The union mask renders the default experience; instances enable explicit user targeting. */
data class PersonSegmentationMask(
    val width: Int,
    val height: Int,
    val confidence: FloatArray,
    val instances: List<SegmentedPersonInstance> = emptyList(),
)

interface PersonSegmentationEngine : AutoCloseable {
    fun process(source: Bitmap, tileWideFrames: Boolean = true): PersonSegmentationMask
}
