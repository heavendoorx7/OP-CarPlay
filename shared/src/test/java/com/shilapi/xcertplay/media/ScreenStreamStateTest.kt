package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class ScreenStreamStateTest {
    @Test fun newUiCanRestoreGeometryWithoutWaitingForAnotherFirstFrame() {
        val state = ScreenStreamState()
        state.setActive(110, true)
        state.decoded(110, 2400, 1200)
        val restored = state.snapshot()
        assertEquals(setOf(110), restored.activeTypes)
        assertEquals(2400 to 1200, restored.decodedSizes[110])
        state.clear()
        assertEquals(2400 to 1200, restored.decodedSizes[110]) // Detached copy.
        assertTrue(state.snapshot().activeTypes.isEmpty())
    }

    @Test fun closedStreamCannotRestoreStaleGeometry() {
        val state = ScreenStreamState()
        state.setActive(110, true)
        state.decoded(110, 2400, 1200)
        state.setActive(110, false)
        state.decoded(110, 2400, 1200) // Already queued old decoder callback.
        assertTrue(state.snapshot().decodedSizes.isEmpty())
        state.setActive(110, true)
        state.decoded(110, 1440, 990)
        assertEquals(1440 to 990, state.snapshot().decodedSizes[110])
    }
}
