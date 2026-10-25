package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationTilePolicyTest {
    @Test
    fun tallPhoneFrameUsesOverlappingTiles() {
        val tiles = SegmentationTilePolicy.plan(360, 720)
        assertEquals(2, tiles.size)
        assertEquals(SegmentationTile(0, 0, 360, 450), tiles[0])
        assertEquals(SegmentationTile(0, 270, 360, 450), tiles[1])
        assertTrue(tiles.zipWithNext().all { (a, b) -> a.top + a.height > b.top })
    }

    @Test
    fun wideFrameUsesOverlappingHorizontalTiles() {
        val tiles = SegmentationTilePolicy.plan(720, 360)
        assertEquals(2, tiles.size)
        assertEquals(SegmentationTile(0, 0, 450, 360), tiles[0])
        assertEquals(SegmentationTile(270, 0, 450, 360), tiles[1])
    }

    @Test
    fun nearSquareFrameUsesSingleInferenceTile() {
        assertEquals(listOf(SegmentationTile(0, 0, 400, 500)), SegmentationTilePolicy.plan(400, 500))
    }

    @Test
    fun powerSaverCanDisableTiling() {
        assertEquals(listOf(SegmentationTile(0, 0, 360, 720)), SegmentationTilePolicy.plan(360, 720, enabled = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        SegmentationTilePolicy.plan(0, 720)
    }
}
