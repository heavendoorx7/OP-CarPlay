package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class P7DisplayProfileTest {
    private fun size(width: Int, height: Int) = AirPlayDisplaySettings.resolvePhysicalSizeMm(
        currentWidthPixels = width,
        currentHeightPixels = height,
        maximumWidthPixels = P7DisplayProfile.REFERENCE_WIDTH_PIXELS,
        maximumHeightPixels = P7DisplayProfile.REFERENCE_HEIGHT_PIXELS,
        referenceMillimeters = P7DisplayProfile.REFERENCE_LENGTH_MM,
        basis = P7DisplayProfile.PHYSICAL_SIZE_BASIS,
    )

    @Test
    fun measuredP7WindowKeepsItsAspectRatioAndUsesHeightBasedLayoutHints() {
        assertEquals(AirPlayPhysicalSizeMm(400, 200), size(2400, 1200))
        assertEquals(AirPlayPhysicalSizeMm(240, 165), size(1440, 990))
    }

    @Test
    fun narrowerWindowDoesNotChangeVerticalDensityAtTheSameHeight() {
        assertEquals(size(2400, 990).heightMm, size(1440, 990).heightMm)
        assertEquals(size(1440, 990).heightMm, size(960, 990).heightMm)
    }
}
