package org.dekufoundation.satr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceAlignmentMathTest {

    @Test
    fun reordersYuNetLandmarksToFastFaceCanonicalOrder() {
        val yuNet = floatArrayOf(
            10f, 11f, // right eye
            20f, 21f, // left eye
            30f, 31f, // nose
            40f, 41f, // right mouth
            50f, 51f, // left mouth
        )
        assertArrayEquals(
            floatArrayOf(20f, 21f, 10f, 11f, 30f, 31f, 50f, 51f, 40f, 41f),
            FaceAlignmentMath.reorderYuNetToFastFace(yuNet),
            0f,
        )
    }

    @Test
    fun identicalLandmarksProduceIdentityMatrix() {
        val points = floatArrayOf(10f, 10f, 30f, 10f, 20f, 20f, 12f, 30f, 28f, 30f)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            FaceAlignmentMath.similarityTransform(points, points),
            0.0001f,
        )
    }

    @Test
    fun translatedLandmarksProduceTranslatedMatrix() {
        val source = floatArrayOf(10f, 10f, 30f, 10f, 20f, 20f, 12f, 30f, 28f, 30f)
        val target = FloatArray(source.size) { index ->
            if (index % 2 == 0) source[index] + 7f else source[index] - 4f
        }
        assertArrayEquals(
            floatArrayOf(1f, 0f, 7f, 0f, 1f, -4f, 0f, 0f, 1f),
            FaceAlignmentMath.similarityTransform(source, target),
            0.0001f,
        )
    }

    @Test
    fun rejectsDegenerateAndNonFiniteLandmarks() {
        assertNull(FaceAlignmentMath.similarityTransform(FloatArray(10), FloatArray(10)))
        val invalid = floatArrayOf(1f, 1f, Float.NaN, 1f, 2f, 2f, 3f, 3f, 4f, 4f)
        assertNull(FaceAlignmentMath.similarityTransform(invalid, invalid))
    }
}
