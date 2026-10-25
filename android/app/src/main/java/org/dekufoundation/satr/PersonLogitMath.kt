package org.dekufoundation.satr

/**
 * Safe early-exit bound for semantic-mask softmax. With a minimum threshold of 0.25,
 * a person logit at least ln(4) below the maximum has probability at most 0.2.
 */
internal object PersonLogitMath {
    private const val PRUNE_MARGIN = 1.3862944f

    fun canReachMinimumThreshold(personLogit: Float, maximumLogit: Float): Boolean {
        require(personLogit.isFinite() && maximumLogit.isFinite()) { "Logits must be finite." }
        require(maximumLogit >= personLogit) { "Maximum logit cannot be below the person logit." }
        return maximumLogit - personLogit <= PRUNE_MARGIN
    }
}
