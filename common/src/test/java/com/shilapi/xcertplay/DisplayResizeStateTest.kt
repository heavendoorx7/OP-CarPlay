package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class DisplayResizeStateTest {
    private val full = DisplaySize(2400, 1200)
    private val split = DisplaySize(1440, 990)

    @Test
    fun returningToOriginalBoundsCancelsTheIntermediateHandshake() {
        val state = DisplayResizeState().apply { restore(full) }
        assertEquals(DisplayResizeState.TimerAction.REPLACE, state.observe(split))
        assertEquals(DisplayResizeState.TimerAction.CANCEL, state.observe(full))
        assertNull(state.settle())
        assertEquals(full, state.current)
        assertTrue(state.readyToStart)
    }

    @Test
    fun animationOnlyCommitsItsLastBounds() {
        val state = DisplayResizeState().apply { restore(full) }
        state.observe(DisplaySize(1800, 1080))
        state.observe(DisplaySize(1600, 1080))
        state.observe(split)
        assertFalse(state.readyToStart)
        assertEquals(DisplayResizeState.Change(full, split), state.settle())
        assertNull(state.settle())
    }

    @Test
    fun duplicateSurfaceAndLayoutEventsKeepTheOriginalDeadline() {
        val state = DisplayResizeState()
        assertEquals(DisplayResizeState.TimerAction.REPLACE, state.observe(split))
        assertEquals(DisplayResizeState.TimerAction.KEEP, state.observe(split))
        assertEquals(DisplayResizeState.Change(null, split), state.settle())
    }

    @Test
    fun teardownCannotStartASecondControllerAndFinishesAtLatestSize() {
        val state = DisplayResizeState().apply { restore(full) }
        state.observe(DisplaySize(1800, 1080))
        state.settle()
        assertTrue(state.beginRestart())
        state.observe(split)
        state.settle()
        assertFalse(state.beginRestart())
        assertFalse(state.readyToStart)
        state.finishRestart()
        assertTrue(state.readyToStart)
        assertEquals(split, state.current)
    }

    @Test
    fun teardownCompletionWaitsForAnUnsettledAnimation() {
        val state = DisplayResizeState().apply { restore(full) }
        state.beginRestart()
        state.observe(split)
        state.finishRestart()
        assertFalse(state.readyToStart)
        state.settle()
        assertTrue(state.readyToStart)
    }
}
