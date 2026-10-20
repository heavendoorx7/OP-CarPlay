package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class P7MeasuredViewportTest {
    private val full = P7PixelBounds(0, 0, 2400, 1200)
    private fun measure(root: P7PixelBounds, video: P7PixelBounds, padding: P7ContentInsets,
                        bars: Boolean = true, pending: Boolean = false, left: Int = 0) =
        p7MeasuredViewport(root, video, padding, 1200, if (bars) 90 else 0,
            if (bars) 120 else 0, pending, left)

    @Test fun oldFullSizeSurfaceIsRejectedWhenBarsAreRequested() {
        assertNull(measure(full, full, P7ContentInsets(0, 0)))
        assertNull(measure(full, full, P7ContentInsets(90, 120)))
    }

    @Test fun fullWithBarsNegotiatesTheNewContentHeight() {
        assertEquals(DisplaySize(2400, 990), measure(full,
            P7PixelBounds(0, 90, 2400, 990), P7ContentInsets(90, 120)))
    }

    @Test fun nativeLeftPaddingReducesWidthAsWellAsHeight() {
        assertEquals(DisplaySize(2304, 990), measure(full,
            P7PixelBounds(96, 90, 2304, 990), P7ContentInsets(90, 120), left = 96))
        assertNull(measure(full, P7PixelBounds(0, 90, 2400, 990), P7ContentInsets(90, 120), left = 96))
    }

    @Test fun aNarrowedNativeWindowUsesItsOwnMeasuredWidth() {
        val narrowed = P7PixelBounds(96, 0, 2304, 1200)
        assertEquals(DisplaySize(2304, 990), measure(narrowed,
            P7PixelBounds(96, 90, 2304, 990), P7ContentInsets(90, 120)))
    }

    @Test fun splitKeepsItsExistingWidthAndDoesNotReserveDockTwice() {
        assertEquals(DisplaySize(1440, 990), measure(P7PixelBounds(960, 0, 1440, 1080),
            P7PixelBounds(960, 90, 1440, 990), P7ContentInsets(90, 0)))
    }

    @Test fun windowAlreadyInsideBarsDoesNotSubtractAgain() {
        val inset = P7PixelBounds(96, 90, 2304, 990)
        assertEquals(DisplaySize(2304, 990), measure(inset, inset, P7ContentInsets(0, 0)))
    }

    @Test fun hidingBarsRequiresFullContentLayoutBeforeReconnect() {
        assertNull(measure(full, P7PixelBounds(0, 90, 2400, 990), P7ContentInsets(90, 120), bars = false))
        assertEquals(DisplaySize(2400, 1200), measure(full, full, P7ContentInsets(0, 0), bars = false))
    }

    @Test fun layoutInProgressNeverCommitsEvenWhenNumbersLookCorrect() {
        assertNull(measure(full, P7PixelBounds(0, 90, 2400, 990), P7ContentInsets(90, 120), pending = true))
    }

    @Test fun nativeSideDockMustBeReservedBeforeAcceptingNewSize() {
        assertNull(p7MeasuredViewport(full, P7PixelBounds(0, 90, 2400, 990),
            P7ContentInsets(90, 120), 1200, 90, 120, false, sideBarWidth = 120))
        assertEquals(DisplaySize(2280, 990), p7MeasuredViewport(full,
            P7PixelBounds(120, 90, 2280, 990), P7ContentInsets(90, 120), 1200, 90, 120,
            false, paddingLeft = 120, sideBarWidth = 120))
    }

    @Test fun sideDockIntersectsOnlyTheOverlappingWindowPixels() {
        assertEquals(121, p7SideBarInset(-1, 2400, 120))
        assertEquals(0, p7SideBarInset(960, 1440, 120))
        assertEquals(0, p7SideBarInset(-1, 2400, 0))
        assertEquals(40, p7SideBarInset(0, 40, 120))
    }

    @Test fun oddWidthIsAlignedWithoutMovingTheLeftEdge() {
        val right = p7AlignedRightPadding(2400, 121, 0, true)
        assertEquals(1, right)
        assertEquals(DisplaySize(2278, 990), p7MeasuredViewport(
            P7PixelBounds(-1, 0, 2400, 1200), P7PixelBounds(120, 90, 2278, 990),
            P7ContentInsets(90, 120), 1200, 90, 120, false, 121, right, 120))
        assertNull(p7MeasuredViewport(P7PixelBounds(-1, 0, 2400, 1200),
            P7PixelBounds(120, 90, 2279, 990), P7ContentInsets(90, 120),
            1200, 90, 120, false, 121, right, 120))
    }
    @Test fun alignmentPreservesEvenNativeBoundsAndSplit() {
        assertEquals(0, p7AlignedRightPadding(2400, 120, 0, true))
        assertEquals(0, p7AlignedRightPadding(1440, 0, 0, false))
        assertEquals(0, p7AlignedRightPadding(1441, 0, 0, false))
        assertEquals(0, p7AlignedRightPadding(2400, 0, 0, false))
        assertEquals(3, p7AlignedRightPadding(2400, 121, 2, true))
    }
}
