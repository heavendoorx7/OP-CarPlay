package com.shilapi.xcertplay

internal data class P7ContentInsets(val top: Int, val bottom: Int)

/** Pixel intersections with E28's native bars, independent of the app's forced DPI. */
internal fun p7ContentInsets(
    windowTop: Int,
    windowHeight: Int,
    displayHeight: Int,
    statusBarHeight: Int,
    dockHeight: Int,
): P7ContentInsets {
    val height = windowHeight.coerceAtLeast(0)
    val top = if (statusBarHeight > 0) (statusBarHeight - windowTop).coerceIn(0, height) else 0
    val bottom = if (dockHeight > 0) {
        (windowTop + height - (displayHeight - dockHeight)).coerceIn(0, height - top)
    } else 0
    return P7ContentInsets(top, bottom)
}
