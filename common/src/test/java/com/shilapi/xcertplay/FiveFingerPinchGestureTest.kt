package com.shilapi.xcertplay

import com.shilapi.xcertplay.FiveFingerPinchGesture.Action.*
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class FiveFingerPinchGestureTest {
    private fun points(scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) = (0..4).map {
        val angle = it * Math.PI * 2 / 5
        FiveFingerPinchGesture.Point(it, 600f + dx + 160f * scale * cos(angle).toFloat(),
            400f + dy + 160f * scale * sin(angle).toFloat())
    }

    @Test fun fiveFingerContractionFiresOnceUntilAllFingersLift() {
        val g = FiveFingerPinchGesture()
        val start = g.update(POINTER_DOWN, points(), 0, 1f)
        assertTrue(start.started && start.consumed)
        assertTrue(g.update(MOVE, points(.65f), 200, 1f).openSettings)
        val held = g.update(MOVE, points(.5f), 300, 1f)
        assertTrue(held.consumed)
        assertFalse(held.openSettings)
        assertTrue(g.update(UP, points().take(1), 400, 1f).consumed)
        assertFalse(g.update(DOWN, points().take(1), 500, 1f).consumed)
    }

    @Test fun ordinaryTouchAndThreeFingerSwipeRemainAvailable() {
        val g = FiveFingerPinchGesture()
        for (count in 1..4) {
            assertFalse(g.update(if (count == 1) DOWN else POINTER_DOWN, points().take(count), count * 10L, 1f).consumed)
        }
        assertFalse(g.update(MOVE, points(dy = 150f).take(3), 100, 1f).openSettings)
    }

    @Test fun translationAndSpreadingAndSmallJitterDoNotOpenSettings() {
        for (next in listOf(points(dx = 180f, dy = 300f), points(1.5f), points(.9f))) {
            val g = FiveFingerPinchGesture()
            g.update(POINTER_DOWN, points(), 0, 1f)
            assertFalse(g.update(MOVE, next, 300, 1f).openSettings)
        }
    }

    @Test fun pointerIndicesMayReorderButIdsMustStayTheSame() {
        val g = FiveFingerPinchGesture()
        g.update(POINTER_DOWN, points(), 0, 1f)
        assertTrue(g.update(MOVE, points(.6f, dx = 30f).reversed(), 300, 1f).openSettings)
        g.reset()
        g.update(POINTER_DOWN, points(), 400, 1f)
        val replaced = points(.6f).map { if (it.id == 4) it.copy(id = 5) else it }
        assertFalse(g.update(MOVE, replaced, 600, 1f).openSettings)
        assertFalse(g.update(MOVE, points(.5f), 700, 1f).openSettings)
    }

    @Test fun LiftingFingersNeverCountsAsPinching() {
        val g = FiveFingerPinchGesture()
        g.update(POINTER_DOWN, points(), 0, 1f)
        assertFalse(g.update(POINTER_UP, points(.6f), 100, 1f).openSettings)
        assertFalse(g.update(MOVE, points(.5f).take(4), 200, 1f).openSettings)
        assertFalse(g.update(POINTER_DOWN, points(.5f), 300, 1f).started)
        assertFalse(g.update(MOVE, points(.3f), 400, 1f).openSettings)
    }

    @Test fun sixthFingerAndTimeoutAndCancellationInvalidateTracking() {
        val extra = points() + FiveFingerPinchGesture.Point(6, 400f, 300f)
        for ((action, next, time) in listOf(Triple(POINTER_DOWN, extra, 100L),
            Triple(MOVE, points(.6f), 1900L), Triple(CANCEL, points(), 100L))) {
            val g = FiveFingerPinchGesture()
            g.update(POINTER_DOWN, points(), 0, 1f)
            assertFalse(g.update(action, next, time, 1f).openSettings)
            assertFalse(g.update(MOVE, points(.5f), time + 100, 1f).openSettings)
        }
    }

    @Test fun smallClusterAndSingleMovingContactDoNotTrigger() {
        val g = FiveFingerPinchGesture()
        g.update(POINTER_DOWN, points(.2f), 0, 1f)
        assertFalse(g.update(MOVE, points(.01f), 100, 1f).openSettings)
        g.reset()
        g.update(POINTER_DOWN, points(), 200, 1f)
        val oneMoved = points().map { if (it.id == 0) it.copy(x = 600f, y = 400f) else it }
        assertFalse(g.update(MOVE, oneMoved, 300, 1f).openSettings)
    }

    @Test fun gestureThresholdsScaleWithScreenDensityAndResetWithLifecycle() {
        val g = FiveFingerPinchGesture()
        g.update(POINTER_DOWN, points(2f), 0, 2f)
        assertTrue(g.update(MOVE, points(1.3f), 200, 2f).openSettings)
        g.reset()
        assertFalse(g.update(MOVE, points(.5f), 300, 2f).consumed)
        g.update(POINTER_DOWN, points(), 400, 1f)
        assertTrue(g.update(MOVE, points(.6f), 500, 1f).openSettings)
    }
}
