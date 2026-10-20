package com.shilapi.xcertplay.airplay

/**
 * Independent iPhone map experiment. The protocol/content URLs are documented by DiPlay at
 * 6d3b8a8ce927cb4b115b8c3881f17619433e93b1 (CarPlayClusterDisplay.kt / BYD_NAVIGATION.md).
 * No BYD display IDs, vehicle bindings or measured safe areas are used here.
 */
object ClusterLabDisplay {
    const val TYPE = 111
    enum class Content(val url: String, val label: String) {
        MAP("maps:/car/instrumentcluster/map", "独立地图"),
        TURN("maps:/car/instrumentcluster/instructioncard", "转向卡片"),
        COMBINED("maps:/car/instrumentcluster", "地图与转向卡片"),
    }
    enum class Profile(val width: Int, val height: Int, val label: String) {
        WIDE(1600, 600, "宽屏 1600×600（先验证协议）"),
        SQUARE(664, 664, "方形 664×664（研究 P7 布局）"),
        P7_CENTERED(1328, 664, "P7 地图居中画布"),
    }

    fun config(profile: Profile, content: Content): AirPlayDisplayConfig {
        // iPhone input dimensions, NOT the P7 instrument's live negotiated parameters.
        // Eight-percent margins are a neutral test layout, not vehicle calibration.
        val x = (profile.width * 0.08).toInt() / 2 * 2
        val y = (profile.height * 0.08).toInt() / 2 * 2
        // Keep the square profile's pixel density while providing real map pixels
        // on both sides of AMap's vehicle anchor; calibrate after phone relayout.
        val physicalWidth = if (profile == Profile.P7_CENTERED) 584 else 292
        return AirPlayDisplayConfig(
            widthPixels = profile.width, heightPixels = profile.height,
            widthPhysicalMm = physicalWidth,
            heightPhysicalMm = (physicalWidth.toDouble() * profile.height / profile.width).toInt(),
            fps = 30, primaryInputDevice = 0, features = 0,
            initialUrl = content.url,
            safeArea = AirPlayInsets(y, y, x, x), safeAreaDrawOutside = true,
        )
    }

    /** Accessory command documented and tested by DiPlay for Apple's independent map. */
    fun zoomCommand(zoomIn: Boolean): Map<String, Any?> = linkedMapOf(
        "type" to "changeMapZoomLevel",
        "params" to linkedMapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "zoomDirection" to if (zoomIn) 0 else 1),
    )

    fun keyFrameCommand(): Map<String, Any?> = linkedMapOf(
        "type" to "forceKeyFrame", "params" to linkedMapOf("uuid" to AirPlayInfoPlist.ALT_UUID),
    )

    /** Allowlisted capability summary only: no names, IDs, arbitrary URLs or raw plist. */
    fun offeredContent(info: Any?): Set<Content> {
        val offered = ((info as? Map<*, *>)?.get("altScreenURLs") as? List<*>)
            ?.filterIsInstance<String>()?.toSet() ?: emptySet()
        return Content.entries.filterTo(linkedSetOf()) { it.url in offered }
    }
}
