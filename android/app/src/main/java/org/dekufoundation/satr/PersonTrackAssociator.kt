package org.dekufoundation.satr

import kotlin.math.max
import kotlin.math.sqrt

/** Normalized person bounding box in the original screen-frame coordinate space. */
data class NormalizedPersonBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) {
            "Box coordinates must be finite and normalized."
        }
        require(right > left && bottom > top) { "Person box must have positive area." }
    }

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersectionOverUnion(other: NormalizedPersonBox): Float {
        val interWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val interHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val intersection = interWidth * interHeight
        val union = area() + other.area() - intersection
        return if (union > 0f) (intersection / union).coerceIn(0f, 1f) else 0f
    }

    fun centerDistance(other: NormalizedPersonBox): Float {
        val dx = centerX - other.centerX
        val dy = centerY - other.centerY
        return sqrt(dx * dx + dy * dy)
    }

    private fun area(): Float = (right - left) * (bottom - top)
}

data class PersonBoxDetection(
    val box: NormalizedPersonBox,
    val confidence: Float,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f) {
            "Detection confidence must be a finite probability."
        }
    }
}

data class TrackedPersonBox(
    val trackId: Long,
    val box: NormalizedPersonBox,
    val confidence: Float,
    val presentation: PersonPresentation = PersonPresentation.UNKNOWN,
    val presentationConfidence: Float = 0f,
    val presentationOverridden: Boolean = false,
)

/**
 * Lightweight deterministic box association for retaining user-selected instance IDs between
 * frames. It is not identity recognition and does not assign gender or other attributes.
 *
 * Tracking is intentionally short-lived: if a detection disappears for longer than maxGapMs,
 * it receives a new ID rather than keeping a stale selection attached to a different person.
 */
internal class PersonTrackAssociator(
    private val minimumIou: Float = DEFAULT_MINIMUM_IOU,
    private val maximumCenterDistance: Float = DEFAULT_MAXIMUM_CENTER_DISTANCE,
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    private data class Track(
        val id: Long,
        var box: NormalizedPersonBox,
        var confidence: Float,
        var lastSeenMs: Long,
    )

    private data class Candidate(
        val detectionIndex: Int,
        val trackId: Long,
        val score: Float,
    )

    private var nextTrackId = 1L
    private var lastTimestampMs = -1L
    private val tracks = mutableListOf<Track>()

    init {
        require(minimumIou.isFinite() && minimumIou in 0f..1f)
        require(maximumCenterDistance.isFinite() && maximumCenterDistance in 0f..sqrt(2f))
        require(maxGapMs >= 0L)
    }

    @Synchronized
    fun update(detections: List<PersonBoxDetection>, timestampMs: Long): List<TrackedPersonBox> {
        require(timestampMs >= 0L) { "Timestamp must be non-negative." }
        require(lastTimestampMs < 0L || timestampMs >= lastTimestampMs) {
            "Frame timestamps must be monotonic."
        }
        lastTimestampMs = timestampMs
        tracks.removeAll { timestampMs - it.lastSeenMs > maxGapMs }

        val candidates = buildList {
            detections.forEachIndexed { index, detection ->
                tracks.forEach { track ->
                    val iou = detection.box.intersectionOverUnion(track.box)
                    val distance = detection.box.centerDistance(track.box)
                    if (iou >= minimumIou || distance <= maximumCenterDistance) {
                        val proximity = (1f - distance / maximumCenterDistance.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                        add(Candidate(index, track.id, max(iou, proximity * 0.5f)))
                    }
                }
            }
        }.sortedByDescending { it.score }

        val assignedDetectionIndexes = mutableSetOf<Int>()
        val assignedTrackIds = mutableSetOf<Long>()
        val assignment = mutableMapOf<Int, Track>()
        candidates.forEach { candidate ->
            if (candidate.detectionIndex in assignedDetectionIndexes || candidate.trackId in assignedTrackIds) {
                return@forEach
            }
            val track = tracks.firstOrNull { it.id == candidate.trackId } ?: return@forEach
            assignedDetectionIndexes += candidate.detectionIndex
            assignedTrackIds += candidate.trackId
            assignment[candidate.detectionIndex] = track
        }

        val result = detections.mapIndexed { index, detection ->
            val track = assignment[index] ?: Track(
                id = nextTrackId++,
                box = detection.box,
                confidence = detection.confidence,
                lastSeenMs = timestampMs,
            ).also { tracks += it }
            track.box = detection.box
            track.confidence = detection.confidence
            track.lastSeenMs = timestampMs
            TrackedPersonBox(track.id, detection.box, detection.confidence)
        }

        // Do not retain stale tracks beyond the short association grace period.
        tracks.removeAll { timestampMs - it.lastSeenMs > maxGapMs }
        return result
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        nextTrackId = 1L
        lastTimestampMs = -1L
    }

    private companion object {
        const val DEFAULT_MINIMUM_IOU = 0.15f
        const val DEFAULT_MAXIMUM_CENTER_DISTANCE = 0.12f
        const val DEFAULT_MAX_GAP_MS = 450L
    }
}
