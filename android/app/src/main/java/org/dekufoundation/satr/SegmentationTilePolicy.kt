package org.dekufoundation.satr

import kotlin.math.roundToInt

data class SegmentationTile(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Splits very tall/wide frames into overlapping near-square crops so the fixed square model
 * spends more input pixels on people instead of letterboxing most of a portrait screen.
 */
object SegmentationTilePolicy {
    private const val TILING_ASPECT_RATIO = 1.45f
    private const val MAX_TILE_ASPECT_RATIO = 1.25f
    private const val TILE_OVERLAP_RATIO = 0.20f

    fun plan(width: Int, height: Int, enabled: Boolean = true): List<SegmentationTile> {
        require(width > 0 && height > 0) { "Frame dimensions must be positive." }
        if (!enabled || maxOf(width, height).toFloat() / minOf(width, height) <= TILING_ASPECT_RATIO) {
            return listOf(SegmentationTile(0, 0, width, height))
        }

        return if (height > width) {
            val tileHeight = minOf(height, (width * MAX_TILE_ASPECT_RATIO).roundToInt().coerceAtLeast(1))
            positions(height, tileHeight).map { top ->
                SegmentationTile(0, top, width, tileHeight)
            }
        } else {
            val tileWidth = minOf(width, (height * MAX_TILE_ASPECT_RATIO).roundToInt().coerceAtLeast(1))
            positions(width, tileWidth).map { left ->
                SegmentationTile(left, 0, tileWidth, height)
            }
        }
    }

    private fun positions(total: Int, tileSize: Int): List<Int> {
        if (tileSize >= total) return listOf(0)
        val step = (tileSize * (1f - TILE_OVERLAP_RATIO)).roundToInt().coerceAtLeast(1)
        val result = mutableListOf(0)
        var next = step
        val last = total - tileSize
        while (next < last) {
            if (next > result.last()) result += next
            next += step
        }
        if (last > result.last()) result += last
        return result
    }
}
