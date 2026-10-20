package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class FullscreenModeStateTest {
    @Test fun freshStartUsesDefaultAndIgnoresStaleHandoffIntent() {
        assertFalse(FullscreenModeState().enter(false, null, true))
        assertTrue(FullscreenModeState().enter(true, null, false))
    }
    @Test fun temporarySwitchDoesNotChangeNextFreshStart() {
        val state = FullscreenModeState()
        assertFalse(state.enter(false, null, null))
        state.select(true)
        assertTrue(state.enter(false, null, true))
        assertFalse(FullscreenModeState().enter(false, null, null))
    }
    @Test fun restoredOrBackgroundWindowRetainsMode() {
        val state = FullscreenModeState()
        assertTrue(state.enter(false, true, null))
        assertTrue(state.enter(false, null, null))
        assertFalse(state.enter(true, null, false))
    }
    @Test fun explicitExitMakesNextLaunchUseUserDefault() {
        val state = FullscreenModeState()
        state.enter(false, null, null)
        state.select(true)
        state.reset()
        assertFalse(state.enter(false, null, true))
        state.select(false)
        state.reset()
        assertTrue(state.enter(true, null, false))
    }
}
