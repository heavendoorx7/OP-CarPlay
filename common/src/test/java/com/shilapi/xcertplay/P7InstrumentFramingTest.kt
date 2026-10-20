package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class P7InstrumentFramingTest {
    @Test fun vehicleAndRoadNameAnchorMovesToCenterWithoutBlankPixels() {
        val crop = P7InstrumentFraming.crop(1328, 664)
        assertEquals(0.5, (876.0 - crop.left) / crop.width, 0.00001)
        assertTrue(crop.left >= 0 && crop.left + crop.width <= 1328)
        assertEquals(0, crop.top)
        assertEquals(664, crop.height)
        assertEquals(crop.width, crop.height)
    }

    @Test fun uvMappingCoversMeasuredAnchorAndPreservesVerticalField() {
        val matrix = FloatArray(16)
        P7InstrumentFraming.crop(1328, 664).writeUvMatrix(1328, 664, matrix)
        assertEquals(544f / 1328, matrix[12], 0.00001f)
        assertEquals(876f / 1328, matrix[0] * 0.5f + matrix[12], 0.00001f)
        assertEquals(1208f / 1328, matrix[0] + matrix[12], 0.00001f)
        assertEquals(0f, matrix[13], 0.00001f)
        assertEquals(1f, matrix[5] + matrix[13], 0.00001f)
    }

    @Test fun unexpectedGeometryUsesWholeFrame() {
        for ((w,h) in listOf(664 to 664, 660 to 660, 1600 to 600, 664 to 1328)) {
            val crop = P7InstrumentFraming.crop(w,h)
            assertEquals(P7InstrumentFraming.Crop(0,0,w,h), crop)
            val matrix = FloatArray(16)
            crop.writeUvMatrix(w,h,matrix)
            assertEquals(1f, matrix[0], 0f)
            assertEquals(1f, matrix[5], 0f)
            assertEquals(0f, matrix[12], 0f)
        }
    }
}
