package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureSizePolicyTest {
    @Test fun portraitDisplayIsDownsampled() {
        assertEquals(CaptureSize(432, 960), CaptureSizePolicy.downsample(1080, 2400, 960))
    }

    @Test fun landscapeDisplayIsDownsampled() {
        assertEquals(CaptureSize(960, 432), CaptureSizePolicy.downsample(2400, 1080, 960))
    }

    @Test fun defaultPolicyCapsLongestEdgeAt640Pixels() {
        assertEquals(CaptureSize(288, 640), CaptureSizePolicy.downsample(1080, 2400))
    }

    @Test fun detailProfileAllows720PixelLongEdge() {
        assertEquals(CaptureSize(324, 720), CaptureSizePolicy.downsample(1080, 2400, ProcessingProfile.DETAIL.maxEdge))
    }

    @Test fun powerSaverProfileUses480PixelLongEdge() {
        assertEquals(CaptureSize(216, 480), CaptureSizePolicy.downsample(1080, 2400, ProcessingProfile.POWER_SAVER.maxEdge))
    }

    @Test fun smallDisplayIsNotUpscaled() {
        assertEquals(CaptureSize(320, 500), CaptureSizePolicy.downsample(320, 500))
    }

    @Test fun dimensionsMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureSizePolicy.downsample(0, 640)
        }
    }
}
