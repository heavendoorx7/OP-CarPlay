package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class ClusterLabDisplayTest {
    private val main = AirPlayDisplayConfig(2400, 1200, 400, 200, fps = 60)
    private val config = AirPlayConfig("lab", "02:00:00:00:00:12", "02:00:00:00:00:11", "950.7.1", main)

    @Test fun centeredCanvasPreservesSquareProfilePixelDensity() {
        val square = ClusterLabDisplay.config(ClusterLabDisplay.Profile.SQUARE, ClusterLabDisplay.Content.MAP)
        val centered = ClusterLabDisplay.config(ClusterLabDisplay.Profile.P7_CENTERED, ClusterLabDisplay.Content.MAP)
        assertEquals(1328, centered.widthPixels)
        assertEquals(square.heightPixels, centered.heightPixels)
        assertEquals(square.heightPhysicalMm, centered.heightPhysicalMm)
        assertEquals(square.widthPixels.toDouble() / requireNotNull(square.widthPhysicalMm),
            centered.widthPixels.toDouble() / requireNotNull(centered.widthPhysicalMm), 0.000001)
    }

    @Test fun primaryDisplayAndTouchAreByteForByteUnchangedWhenClusterIsAdded() {
        val before = AirPlayInfoPlist.build(config)
        for (profile in ClusterLabDisplay.Profile.entries) {
            val after = AirPlayInfoPlist.build(config.copy(cluster = ClusterLabDisplay.config(
                profile, ClusterLabDisplay.Content.MAP)))
            assertArrayEquals(BplistCodec.encode(before["hidDevices"]), BplistCodec.encode(after["hidDevices"]))
            assertArrayEquals(BplistCodec.encode((before["displays"] as List<*>)[0]),
                BplistCodec.encode((after["displays"] as List<*>)[0]))
            assertEquals(2, (after["displays"] as List<*>).size)
        }
    }

    @Test fun allProfilesAdvertiseIndependentNonInteractiveMapAndValidSafeArea() {
        for (profile in ClusterLabDisplay.Profile.entries) {
            for (content in ClusterLabDisplay.Content.entries) {
                val display = ClusterLabDisplay.config(profile, content)
                val info = AirPlayInfoPlist.build(config.copy(cluster = display))
                val alt = (info["displays"] as List<*>)[1] as Map<*, *>
                assertEquals(111, alt["type"])
                assertEquals(AirPlayInfoPlist.ALT_UUID, alt["uuid"])
                assertEquals(content.url, alt["initialURL"])
                assertEquals(0, alt["features"])
                assertEquals(0, alt["primaryInputDevice"])
                assertEquals(30, alt["maxFPS"])
                assertEquals(0, profile.width % 8)
                assertEquals(0, profile.height % 8)
                val safe = ((alt["viewAreas"] as List<*>).single() as Map<*, *>)["safeArea"] as Map<*, *>
                val x = safe["originXPixels"] as Int
                val y = safe["originYPixels"] as Int
                assertTrue(x > 0 && y > 0)
                assertTrue(x + (safe["widthPixels"] as Int) <= profile.width)
                assertTrue(y + (safe["heightPixels"] as Int) <= profile.height)
            }
        }
    }

    @Test fun phoneCapabilitiesAreAnExactAllowlist() {
        val offered = ClusterLabDisplay.offeredContent(mapOf("altScreenURLs" to listOf(
            ClusterLabDisplay.Content.MAP.url, "https://private.example/location",
            ClusterLabDisplay.Content.TURN.url, 42, ClusterLabDisplay.Content.MAP.url,
        ), "name" to "private phone"))
        assertEquals(setOf(ClusterLabDisplay.Content.MAP, ClusterLabDisplay.Content.TURN), offered)
        assertTrue(ClusterLabDisplay.offeredContent(mapOf("altScreenURLs" to "bad")).isEmpty())
        assertTrue(ClusterLabDisplay.offeredContent(null).isEmpty())
    }

    @Test fun alternateRecoveryCannotTargetMainDisplay() {
        val command = ClusterLabDisplay.keyFrameCommand()
        assertEquals("forceKeyFrame", command["type"])
        assertEquals(mapOf("uuid" to AirPlayInfoPlist.ALT_UUID), command["params"])
        assertNotEquals(mainScreenKeyFrameCommand(), command)
    }

    @Test fun clusterGeometryDoesNotFollowMainWindowResize() {
        val alt = ClusterLabDisplay.config(ClusterLabDisplay.Profile.SQUARE, ClusterLabDisplay.Content.MAP)
        fun advertised(main: AirPlayDisplayConfig) =
            (AirPlayInfoPlist.build(config.copy(main = main, cluster = alt))["displays"] as List<*>)[1]
        assertArrayEquals(BplistCodec.encode(advertised(main)), BplistCodec.encode(advertised(main.copy(widthPixels = 1440, heightPixels = 990))))
    }
    @Test fun mapZoomTargetsOnlyTheIndependentMap() {
        val zoom=ClusterLabDisplay.zoomCommand(true)
        assertEquals("changeMapZoomLevel",zoom["type"])
        assertEquals(mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,"zoomDirection" to 0),zoom["params"])
        assertEquals(mapOf("uuid" to AirPlayInfoPlist.ALT_UUID,"zoomDirection" to 1),ClusterLabDisplay.zoomCommand(false)["params"])
    }
}
