package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class AppRunStateTest {
    @Test fun windowHandoffKeepsSessionBeforeExit() {
        val state = AppRunState()
        val immersive = state.enter()
        val bars = state.enter()
        assertTrue(state.isActive(immersive))
        assertTrue(state.isActive(bars))
    }

    @Test fun exitInvalidatesEveryWindowAndTheirQueuedRetries() {
        val state = AppRunState()
        val hiddenWindow = state.enter()
        val topWindow = state.enter()
        assertTrue(state.beginExit(topWindow))
        assertFalse(state.isActive(topWindow))
        assertFalse(state.isActive(hiddenWindow))
        assertFalse(state.beginExit(hiddenWindow))
    }

    @Test fun rapidManualReopenCannotRaceOldServiceCleanup() {
        val state = AppRunState()
        state.beginExit(state.enter())
        assertNull(state.enter())
        state.finishExit()
        assertTrue(state.isActive(state.enter()))
    }

    @Test fun oldCallbacksStayInvalidAfterManualReopen() {
        val state = AppRunState()
        val old = state.enter()
        state.beginExit(old)
        state.finishExit()
        val reopened = state.enter()
        assertFalse(state.isActive(old))
        assertFalse(state.beginExit(old))
        assertTrue(state.isActive(reopened))
    }

    @Test fun repeatedExitAndReopenCyclesStayIndependent() {
        val state = AppRunState()
        val previous = mutableListOf<Long?>()
        repeat(10) {
            val token = state.enter()
            assertTrue(state.isActive(token))
            previous.forEach { assertFalse(state.isActive(it)) }
            assertTrue(state.beginExit(token))
            assertFalse(state.beginExit(token))
            state.finishExit()
            previous += token
        }
    }
}
