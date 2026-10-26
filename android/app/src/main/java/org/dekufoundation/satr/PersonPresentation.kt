package org.dekufoundation.satr

/**
 * A visual presentation estimate from a face crop, not a statement of a person's identity.
 * Unknown or low-confidence results must never make a person visible in a gender-targeted mode.
 */
enum class PersonPresentation {
    UNKNOWN,
    MALE_PRESENTING,
    FEMALE_PRESENTING,
}

data class FacePresentationPrediction(
    val presentation: PersonPresentation,
    val confidence: Float,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class StablePersonPresentation(
    val presentation: PersonPresentation = PersonPresentation.UNKNOWN,
    val confidence: Float = 0f,
)

/**
 * Requires repeated high-confidence predictions before applying a presentation label.
 * A recent label is retained briefly to bridge one missed face frame, then returns to UNKNOWN.
 */
internal class PersonPresentationStabilizer(
    private val minimumConfidence: Float = MINIMUM_CONFIDENCE,
    private val requiredMatches: Int = REQUIRED_MATCHES,
    private val maxHoldMs: Long = MAX_HOLD_MS,
) {
    private data class State(
        var candidate: PersonPresentation = PersonPresentation.UNKNOWN,
        var candidateCount: Int = 0,
        var stable: PersonPresentation = PersonPresentation.UNKNOWN,
        var confidence: Float = 0f,
        var lastConfidentAtMs: Long = -1L,
    )

    private val states = mutableMapOf<Long, State>()

    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        require(requiredMatches >= 1)
        require(maxHoldMs >= 0L)
    }

    @Synchronized
    fun update(
        trackId: Long,
        prediction: PersonPresentation,
        confidence: Float,
        timestampMs: Long,
    ): StablePersonPresentation {
        require(trackId >= 0L)
        require(timestampMs >= 0L)
        require(confidence.isFinite() && confidence in 0f..1f)
        val state = states.getOrPut(trackId) { State() }
        val usable = prediction != PersonPresentation.UNKNOWN && confidence >= minimumConfidence
        if (!usable) {
            state.candidate = PersonPresentation.UNKNOWN
            state.candidateCount = 0
            if (state.lastConfidentAtMs < 0L || timestampMs - state.lastConfidentAtMs > maxHoldMs) {
                state.stable = PersonPresentation.UNKNOWN
                state.confidence = 0f
            }
            return StablePersonPresentation(state.stable, state.confidence)
        }

        if (state.candidate == prediction) {
            state.candidateCount = (state.candidateCount + 1).coerceAtMost(requiredMatches)
        } else {
            state.candidate = prediction
            state.candidateCount = 1
        }
        if (state.candidateCount >= requiredMatches) {
            state.stable = prediction
            state.confidence = confidence
            state.lastConfidentAtMs = timestampMs
        } else if (state.stable != prediction &&
            (state.lastConfidentAtMs < 0L || timestampMs - state.lastConfidentAtMs > maxHoldMs)
        ) {
            state.stable = PersonPresentation.UNKNOWN
            state.confidence = 0f
        }
        return StablePersonPresentation(state.stable, state.confidence)
    }

    @Synchronized
    fun retainTracks(activeTrackIds: Set<Long>, timestampMs: Long) {
        require(timestampMs >= 0L)
        states.entries.removeAll { (id, state) ->
            id !in activeTrackIds &&
                (state.lastConfidentAtMs < 0L || timestampMs - state.lastConfidentAtMs > maxHoldMs)
        }
    }

    @Synchronized
    fun reset() = states.clear()

    private companion object {
        const val MINIMUM_CONFIDENCE = 0.88f
        const val REQUIRED_MATCHES = 2
        const val MAX_HOLD_MS = 1_400L
    }
}
