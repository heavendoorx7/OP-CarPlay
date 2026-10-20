package com.shilapi.xcertplay

internal data class P7PixelBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Accept actual video bounds only after native-bar padding and child layout agree. */
internal fun p7MeasuredViewport(
    root: P7PixelBounds,
    video: P7PixelBounds,
    padding: P7ContentInsets,
    displayHeight: Int,
    statusHeight: Int,
    dockHeight: Int,
    layoutPending: Boolean,
    paddingLeft: Int = 0,
    paddingRight: Int = 0,
    sideBarWidth: Int = 0,
): DisplaySize? {
    if (layoutPending || root.width <= 0 || root.height <= 0 || video.width <= 0 || video.height <= 0) return null
    val expected = p7ContentInsets(root.y, root.height, displayHeight, statusHeight, dockHeight)
    if (paddingLeft < p7SideBarInset(root.x, root.width, sideBarWidth)) return null
    if (padding != expected || video.x != root.x + paddingLeft || video.y != root.y + expected.top ||
        video.width != root.width - paddingLeft - paddingRight ||
        video.height != root.height - expected.top - expected.bottom
    ) return null
    return DisplaySize(video.width, video.height)
}

internal fun p7SideBarInset(windowLeft: Int, windowWidth: Int, sideBarWidth: Int): Int =
    if (sideBarWidth > 0) (sideBarWidth - windowLeft).coerceIn(0, windowWidth.coerceAtLeast(0)) else 0

/** Only the bar-visible fullscreen mode needs the trailing-pixel correction. */
internal fun p7AlignedRightPadding(width: Int, left: Int, baseRight: Int, align: Boolean): Int {
    val available = width - left - baseRight
    return baseRight + if (align && available > 1) available % 2 else 0
}
