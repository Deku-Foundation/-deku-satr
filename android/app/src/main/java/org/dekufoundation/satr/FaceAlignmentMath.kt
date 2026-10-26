package org.dekufoundation.satr

/**
 * Least-squares 2D similarity transform for the five detector landmarks.
 * Returns a 3x3 Android Matrix value array mapping source landmarks to target points.
 */
internal object FaceAlignmentMath {
    fun similarityTransform(source: FloatArray, target: FloatArray): FloatArray? {
        if (source.size != 10 || target.size != 10) return null
        if (!source.all { it.isFinite() } || !target.all { it.isFinite() }) return null
        var sourceX = 0.0
        var sourceY = 0.0
        var targetX = 0.0
        var targetY = 0.0
        for (point in 0 until 5) {
            sourceX += source[point * 2]
            sourceY += source[point * 2 + 1]
            targetX += target[point * 2]
            targetY += target[point * 2 + 1]
        }
        sourceX /= 5.0
        sourceY /= 5.0
        targetX /= 5.0
        targetY /= 5.0

        var denominator = 0.0
        var numeratorA = 0.0
        var numeratorB = 0.0
        for (point in 0 until 5) {
            val sx = source[point * 2] - sourceX
            val sy = source[point * 2 + 1] - sourceY
            val tx = target[point * 2] - targetX
            val ty = target[point * 2 + 1] - targetY
            denominator += sx * sx + sy * sy
            numeratorA += sx * tx + sy * ty
            numeratorB += sx * ty - sy * tx
        }
        if (!denominator.isFinite() || denominator <= 1e-8) return null
        val a = numeratorA / denominator
        val b = numeratorB / denominator
        val translateX = targetX - a * sourceX + b * sourceY
        val translateY = targetY - b * sourceX - a * sourceY
        val values = floatArrayOf(
            a.toFloat(), (-b).toFloat(), translateX.toFloat(),
            b.toFloat(), a.toFloat(), translateY.toFloat(),
            0f, 0f, 1f,
        )
        return values.takeIf { it.all(Float::isFinite) }
    }
}
