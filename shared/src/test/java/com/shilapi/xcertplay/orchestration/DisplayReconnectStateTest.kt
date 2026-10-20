package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class DisplayReconnectStateTest {
    @Test fun directBootstrapHasNoPassiveWaitOrSecondTransportRestart() {
        val state = DisplayReconnectState()
        val token = state.begin(1440, 990, directBootstrap = true)
        assertFalse(state.isSoft(token))
        assertTrue(state.isFallback(token))
        assertEquals(DisplayReconnectState.TimeoutAction.FAILED, state.timeout(token))
        assertFalse(state.isActive())
    }

    @Test fun rapidDirectBootstrapOnlyLatestQueuedTicketMayStart() {
        val state = DisplayReconnectState()
        val first = state.begin(1440, 990, directBootstrap = true)
        val next = state.begin(2400, 1200, directBootstrap = true)
        assertFalse(state.isFallback(first))
        assertTrue(state.isFallback(next))
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(first))
        assertTrue(state.confirmFrame(2400, 1200))
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(next))
    }

    @Test fun firstFrameOfRequestedSizeCompletesWithoutTransportRestart() {
        val state = DisplayReconnectState()
        val token = state.begin(1440, 990)
        assertTrue(state.confirmFrame(1440, 990))
        assertFalse(state.isActive())
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(token))
    }

    @Test fun oldSizeDoesNotCountAsSuccessfulResize() {
        val state = DisplayReconnectState()
        val token = state.begin(1440, 990)
        assertFalse(state.confirmFrame(2400, 1200))
        assertTrue(state.isActive())
        assertEquals(DisplayReconnectState.TimeoutAction.RESTART_TRANSPORT, state.timeout(token))
    }

    @Test fun fallbackIsBoundedAndCannotRestartForever() {
        val state = DisplayReconnectState()
        val token = state.begin(2400, 1200)
        assertEquals(DisplayReconnectState.TimeoutAction.RESTART_TRANSPORT, state.timeout(token))
        assertFalse(state.isSoft(token))
        assertEquals(DisplayReconnectState.TimeoutAction.FAILED, state.timeout(token))
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(token))
    }

    @Test fun delayedTimeoutFromEarlierWindowCannotInterruptLatestWindow() {
        val state = DisplayReconnectState()
        val full = state.begin(2400, 1200)
        val split = state.begin(1440, 990)
        assertFalse(state.isSoft(full))
        assertTrue(state.isSoft(split))
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(full))
        assertFalse(state.confirmFrame(2400, 1200))
        assertTrue(state.confirmFrame(1440, 990))
    }

    @Test fun cancellationMakesQueuedWorkAndWatchdogsStale() {
        val state = DisplayReconnectState()
        val token = state.begin(1440, 990)
        state.cancel()
        assertFalse(state.isSoft(token))
        assertFalse(state.confirmFrame(1440, 990))
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(token))
    }

    @Test fun correctFrameDuringFallbackCancelsRemainingTimeout() {
        val state = DisplayReconnectState()
        val token = state.begin(2400, 990)
        state.timeout(token)
        assertTrue(state.confirmFrame(2400, 990))
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(token))
    }

    @Test fun newResizeSupersedesPreviousFallback() {
        val state = DisplayReconnectState()
        val first = state.begin(1440, 990)
        state.timeout(first)
        val next = state.begin(2400, 1200)
        assertEquals(DisplayReconnectState.TimeoutAction.NONE, state.timeout(first))
        assertTrue(state.isSoft(next))
    }
}
