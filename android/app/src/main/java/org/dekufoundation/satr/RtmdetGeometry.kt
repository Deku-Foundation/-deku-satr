package org.dekufoundation.satr

import kotlin.math.roundToInt

data class RtmdetResizePlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Float,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val inputSize: Int = 640,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && inputSize > 0)
        require(scale.isFinite() && scale > 0f)
        require(resizedWidth in 1..inputSize && resizedHeight in 1..inputSize)
    }

    fun toNormalizedBox(x1: Float, y1: Float, x2: Float, y2: Float): NormalizedPersonBox? {
        if (!listOf(x1, y1, x2, y2).all { it.isFinite() }) return null
        val left = (x1 / scale).coerceIn(0f, sourceWidth.toFloat())
        val top = (y1 / scale).coerceIn(0f, sourceHeight.toFloat())
        val right = (x2 / scale).coerceIn(0f, sourceWidth.toFloat())
        val bottom = (y2 / scale).coerceIn(0f, sourceHeight.toFloat())
        if (right <= left || bottom <= top) return null
        return runCatching {
            NormalizedPersonBox(
                (left / sourceWidth).coerceIn(0f, 1f),
                (top / sourceHeight).coerceIn(0f, 1f),
                (right / sourceWidth).coerceIn(0f, 1f),
                (bottom / sourceHeight).coerceIn(0f, 1f),
            )
        }.getOrNull()
    }
}

object RtmdetGeometry {
    /** Matches RTMDet's aspect-preserving resize with padding at the right and bottom. */
    fun plan(width: Int, height: Int, inputSize: Int = 640): RtmdetResizePlan {
        require(width > 0 && height > 0 && inputSize > 0)
        val scale = minOf(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val resizedWidth = (width * scale).roundToInt().coerceIn(1, inputSize)
        val resizedHeight = (height * scale).roundToInt().coerceIn(1, inputSize)
        return RtmdetResizePlan(width, height, scale, resizedWidth, resizedHeight, inputSize)
    }

    fun sampleMask(
        mask: FloatArray,
        plan: RtmdetResizePlan,
        threshold: Float = 0.5f,
    ): BooleanArray {
        val size = plan.inputSize
        require(mask.size.toLong() == size.toLong() * size)
        require(threshold.isFinite() && threshold in 0f..1f)
        val output = BooleanArray(plan.sourceWidth * plan.sourceHeight)
        for (y in 0 until plan.sourceHeight) {
            val sourceY = (((y + 0.5f) * plan.scale) - 0.5f).coerceIn(0f, (plan.resizedHeight - 1).toFloat())
            val y0 = sourceY.toInt()
            val y1 = (y0 + 1).coerceAtMost(plan.resizedHeight - 1)
            val fy = sourceY - y0
            for (x in 0 until plan.sourceWidth) {
                val sourceX = (((x + 0.5f) * plan.scale) - 0.5f).coerceIn(0f, (plan.resizedWidth - 1).toFloat())
                val x0 = sourceX.toInt()
                val x1 = (x0 + 1).coerceAtMost(plan.resizedWidth - 1)
                val fx = sourceX - x0
                fun at(px: Int, py: Int) = mask[py * size + px]
                val top = at(x0, y0) * (1f - fx) + at(x1, y0) * fx
                val bottom = at(x0, y1) * (1f - fx) + at(x1, y1) * fx
                output[y * plan.sourceWidth + x] = top * (1f - fy) + bottom * fy > threshold
            }
        }
        return output
    }
}
