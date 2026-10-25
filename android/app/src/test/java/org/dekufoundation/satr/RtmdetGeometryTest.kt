package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class RtmdetGeometryTest {
    @Test
    fun portraitPlanUsesAspectPreservingResize() {
        val plan = RtmdetGeometry.plan(360, 800)
        assertEquals(0.8f, plan.scale, 0.0001f)
        assertEquals(288, plan.resizedWidth)
        assertEquals(640, plan.resizedHeight)
    }

    @Test
    fun landscapePlanPadsRightAndBottom() {
        val plan = RtmdetGeometry.plan(1920, 1080)
        assertEquals(640, plan.resizedWidth)
        assertEquals(360, plan.resizedHeight)
        assertEquals(640f / 1920f, plan.scale, 0.0001f)
    }

    @Test
    fun boxesMapBackToNormalizedSourceCoordinates() {
        val box = RtmdetGeometry.plan(640, 640).toNormalizedBox(64f, 128f, 320f, 512f)
        assertNotNull(box)
        assertEquals(0.1f, box!!.left, 0.0001f)
        assertEquals(0.2f, box.top, 0.0001f)
        assertEquals(0.5f, box.right, 0.0001f)
        assertEquals(0.8f, box.bottom, 0.0001f)
    }

    @Test
    fun invalidBoxesAreDiscarded() {
        assertNull(RtmdetGeometry.plan(640, 640).toNormalizedBox(400f, 100f, 300f, 200f))
    }

    @Test
    fun directBufferMaskSamplingMatchesArraySampling() {
        val plan = RtmdetGeometry.plan(160, 320)
        val values = FloatArray(640 * 640) { if (it % 3 == 0) 1f else 0f }
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values)
        buffer.position(0)
        val fromArray = RtmdetGeometry.sampleMask(values, plan)
        val fromBuffer = RtmdetGeometry.sampleMask(buffer, 0, plan)
        assertArrayEquals(fromArray, fromBuffer)
    }

    @Test
    fun maskSamplingExcludesRightAndBottomPadding() {
        val plan = RtmdetGeometry.plan(320, 640)
        val raw = FloatArray(640 * 640)
        for (y in 0 until plan.resizedHeight) {
            for (x in 0 until plan.resizedWidth) raw[y * 640 + x] = 1f
        }
        val sampled = RtmdetGeometry.sampleMask(raw, plan)
        assertEquals(true, sampled.all { it })
    }
}
