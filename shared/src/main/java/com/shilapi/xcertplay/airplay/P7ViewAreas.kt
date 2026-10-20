package com.shilapi.xcertplay.airplay

/** Experimental main-screen ViewArea path; physical DPI is shared by both modes. */
object P7ViewAreas {
    /** Safe-area conversion returns zero insets for an uncustomized full window. */
    fun supportsSafeArea(insets: AirPlayInsets?): Boolean =
        insets == null || insets == AirPlayInsets()

    fun mode(width: Int, height: Int): Int? = when {
        width == 2400 && height in 1199..1200 -> 0
        width == 1440 && height in 989..990 -> 1
        else -> null
    }

    fun configure(canvas: AirPlayDisplayConfig, scaleTenths: Int, initial: Int): AirPlayDisplayConfig {
        require(initial in 0..1)
        val full = CarPlayDisplayScale.apply(canvas.copy(widthPixels = 2400, heightPixels = 1200), scaleTenths)
        val split = CarPlayDisplayScale.apply(canvas.copy(widthPixels = 1440, heightPixels = 990), scaleTenths)
        return full.copy(
            viewArea = null, safeArea = null,
            viewAreas = listOf(AirPlayViewArea(full.widthPixels, full.heightPixels),
                AirPlayViewArea(split.widthPixels, split.heightPixels)),
            initialViewArea = initial,
        )
    }

    fun command(index: Int, includeDisplayUuid: Boolean = true): Map<String, Any?> {
        require(index in 0..1)
        val params = linkedMapOf<String, Any?>("viewAreaIndex" to index.toLong(),
            "animationDurationMillis" to 0L,
        )
        if (includeDisplayUuid) params["uuid"] = AirPlayInfoPlist.MAIN_UUID
        return linkedMapOf("type" to "updateViewArea", "params" to params)
    }

    data class Crop(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float)

    /** Accept a full canvas or an already cropped stream; never guess unknown SPS sizes. */
    fun crop(display: AirPlayDisplayConfig, area: AirPlayViewArea, width: Int, height: Int): Crop? = when {
        width == area.width && height == area.height -> Crop(1f, 1f, 0f, 0f)
        width == display.widthPixels && height == display.heightPixels ->
            Crop(width.toFloat() / area.width, height.toFloat() / area.height,
                -area.x.toFloat() / area.width, -area.y.toFloat() / area.height)
        else -> null
    }

    fun touch(area: AirPlayViewArea, x: Double, y: Double): Pair<Double, Double> =
        (area.x + x.coerceIn(0.0, 1.0) * area.width) to (area.y + y.coerceIn(0.0, 1.0) * area.height)
}
