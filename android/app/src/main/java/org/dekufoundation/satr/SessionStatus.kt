package org.dekufoundation.satr

import java.util.concurrent.CopyOnWriteArraySet

enum class SatrPhase {
    STOPPED,
    WAITING_PERMISSION,
    STARTING,
    FILTERING,
    NO_PERSON_DETECTED,
    FAIL_CLOSED,
    ERROR,
}

data class SatrSessionSnapshot(
    val phase: SatrPhase = SatrPhase.STOPPED,
    val detail: String = "الجلسة متوقفة. لا توجد فلترة فعّالة.",
    val processedFrames: Long = 0,
    val lastInferenceMs: Long? = null,
    val maskCoveragePercent: Int? = null,
    val modelReady: Boolean = false,
    val detectedPeople: List<TrackedPersonBox> = emptyList(),
    val personModelProfile: PersonModelProfile = PersonModelProfile.FAST_SEMANTIC,
    val personTargetMode: PersonTargetMode = PersonTargetMode.MASK_ALL,
    val selectedTrackIds: Set<Long> = emptySet(),
)

object SessionStatus {
    @Volatile
    private var snapshot = SatrSessionSnapshot()

    private val listeners = CopyOnWriteArraySet<(SatrSessionSnapshot) -> Unit>()

    fun current(): SatrSessionSnapshot = snapshot

    fun subscribe(listener: (SatrSessionSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(snapshot)
        return { listeners.remove(listener) }
    }

    fun publish(value: SatrSessionSnapshot) {
        snapshot = value
        listeners.forEach { listener ->
            runCatching { listener(value) }
        }
    }

    fun update(transform: (SatrSessionSnapshot) -> SatrSessionSnapshot) {
        val next = synchronized(this) {
            transform(snapshot).also { snapshot = it }
        }
        listeners.forEach { listener ->
            runCatching { listener(next) }
        }
    }
}
