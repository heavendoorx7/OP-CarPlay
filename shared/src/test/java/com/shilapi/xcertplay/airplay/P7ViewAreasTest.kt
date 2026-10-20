package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class P7ViewAreasTest {
    private val base = AirPlayDisplayConfig(2400, 1200, 400, 200)

    @Test fun actualDefaultSafeAreaConversionAllowsDynamicViewsAtEveryScale() {
        for ((width, height) in listOf(2400 to 1200, 1440 to 990)) {
            for (scale in 3..10) {
                val display = CarPlayDisplayScale.apply(base.copy(widthPixels = width, heightPixels = height), scale)
                val insets = AirPlaySafeArea.toInsets(null, width, height, display.widthPixels, display.heightPixels)
                assertNotNull(insets)
                assertTrue("Default insets must not disable dynamic views: $insets", P7ViewAreas.supportsSafeArea(insets))
            }
        }
    }

    @Test fun realCustomSafeAreaIsStillProtectedFromDynamicViewportOverride() {
        val insets = AirPlaySafeArea.toInsets(SafeAreaRect(0, 90, 1440, 990), 1440, 990, 1440, 990)
        assertFalse(P7ViewAreas.supportsSafeArea(insets))
        assertTrue(P7ViewAreas.supportsSafeArea(null))
    }

    @Test fun splitStartupAdvertisesBothSizesWithIdenticalPixelDensity() {
        val main = P7ViewAreas.configure(base, 10, 1)
        assertEquals(2400, main.widthPixels)
        assertEquals(1200, main.heightPixels)
        assertEquals(1, main.initialViewArea)
        assertEquals(AirPlayViewArea(1440, 990), main.viewAreas[1])
        assertEquals(400, main.widthPhysicalMm)
        assertEquals(200, main.heightPhysicalMm)
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "950.7.1", main)
        val display = (AirPlayInfoPlist.build(config)["displays"] as List<*>).single() as Map<*, *>
        assertEquals(1, display["initialViewArea"])
        val split = (display["viewAreas"] as List<*>)[1] as Map<*, *>
        assertEquals(1440, split["widthPixels"])
        assertEquals(990, (split["safeArea"] as Map<*, *>)["heightPixels"])
        assertEquals(false, split["viewAreaTransitionControl"])
    }

    @Test fun scaleProducesEvenCanvasAndViewportsAtEverySupportedSetting() {
        for (scale in 3..10) {
            val main = P7ViewAreas.configure(base, scale, 0)
            assertEquals(0, main.widthPixels % 2)
            assertEquals(0, main.heightPixels % 2)
            main.viewAreas.forEach {
                assertEquals(0, it.width % 2)
                assertEquals(0, it.height % 2)
                assertTrue(it.width <= main.widthPixels && it.height <= main.heightPixels)
            }
        }
    }

    @Test fun onePixelVendorOriginDifferenceDoesNotCreateAnotherMode() {
        assertEquals(1, P7ViewAreas.mode(1440, 989))
        assertEquals(1, P7ViewAreas.mode(1440, 990))
        assertEquals(0, P7ViewAreas.mode(2400, 1200))
        assertNull(P7ViewAreas.mode(2400, 990))
        assertNull(P7ViewAreas.mode(960, 1200))
    }

    @Test fun fullCanvasCropExactlyFillsSplitWithoutStretchingPixels() {
        val main = P7ViewAreas.configure(base, 10, 1)
        val crop = requireNotNull(P7ViewAreas.crop(main, main.viewAreas[1], 2400, 1200))
        // TextureView first fits the whole canvas to its size; this restores the
        // target viewport, instead of stretching a 2:1 canvas into a 1.45:1 box.
        assertEquals(1f, 1440f / 2400f * crop.scaleX, 0.00001f)
        assertEquals(1f, 990f / 1200f * crop.scaleY, 0.00001f)
        assertEquals(0f, crop.offsetX, 0f)
    }

    @Test fun alreadyCroppedVideoMustNotBeCroppedTwice() {
        val main = P7ViewAreas.configure(base, 10, 1)
        assertEquals(P7ViewAreas.Crop(1f, 1f, 0f, 0f), P7ViewAreas.crop(main, main.viewAreas[1], 1440, 990))
        assertNull(P7ViewAreas.crop(main, main.viewAreas[1], 1280, 720))
    }

    @Test fun touchesUseAdvertisedCanvasCoordinatesIncludingViewportOffset() {
        val view = AirPlayViewArea(1440, 990, 100, 50)
        assertEquals(820.0 to 545.0, P7ViewAreas.touch(view, 0.5, 0.5))
        assertEquals(100.0 to 1040.0, P7ViewAreas.touch(view, -1.0, 2.0))
    }

    @Test fun commandSurvivesBinaryPlistRoundTripWithMainDisplayUuidAndIntegers() {
        val command = BplistCodec.decode(BplistCodec.encode(P7ViewAreas.command(1))) as Map<*, *>
        assertEquals("updateViewArea", command["type"])
        val params = command["params"] as Map<*, *>
        assertEquals(AirPlayInfoPlist.MAIN_UUID, params["uuid"])
        assertEquals(1L, params["viewAreaIndex"])
        assertEquals(0L, params["animationDurationMillis"])
    }
}
