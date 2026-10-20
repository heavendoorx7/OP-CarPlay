package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class P7ContentInsetsTest {
    @Test fun immersiveFullScreenUsesWholePhysicalDisplay() {
        assertEquals(P7ContentInsets(0, 0), p7ContentInsets(0, 1200, 1200, 0, 0))
        assertEquals(P7ContentInsets(0, 0), p7ContentInsets(-1, 1200, 1200, 0, 0))
        assertEquals(P7ContentInsets(0, 0), p7ContentInsets(1, 1200, 1200, 0, 0))
    }
    @Test fun hidingDockUsesItsFormerSpace() {
        assertEquals(P7ContentInsets(90, 0), p7ContentInsets(0, 1200, 1200, 90, 0))
    }
    @Test fun fullScreenKeepsBothNativeBarsOutsideVideo() {
        assertEquals(P7ContentInsets(90, 120), p7ContentInsets(0, 1200, 1200, 90, 120))
    }

    @Test fun splitDoesNotReserveDockTwice() {
        assertEquals(P7ContentInsets(90, 0), p7ContentInsets(0, 1080, 1200, 90, 120))
    }

    @Test fun windowAlreadyInsideSystemBarsGetsNoExtraPadding() {
        assertEquals(P7ContentInsets(0, 0), p7ContentInsets(90, 990, 1200, 90, 120))
    }

    @Test fun tinyTransientWindowDoesNotGetNegativeContentHeight() {
        assertEquals(P7ContentInsets(40, 0), p7ContentInsets(0, 40, 1200, 90, 120))
    }

    @Test fun transitionFrameOnePixelOffscreenRetainsPhysicalBarBoundaries() {
        assertEquals(P7ContentInsets(91, 119), p7ContentInsets(-1, 1200, 1200, 90, 120))
    }
}
