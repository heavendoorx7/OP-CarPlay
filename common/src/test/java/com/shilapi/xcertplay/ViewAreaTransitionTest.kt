package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ViewAreaTransitionTest {
    @Test fun recreatedUiCanReassertTheViewportWithNoCachedPendingRequest() {
        val state = ViewAreaTransition()
        state.requireResync()
        state.resume()
        assertTrue(state.needsResync)
        val ticket = state.begin(0)!!
        assertFalse(state.needsResync)
        assertTrue(state.isCurrent(ticket))
    }

    @Test fun backgroundCannotStartOrTimeoutARequest() {
        val state = ViewAreaTransition()
        assertNull(state.begin(1))
        state.resume()
        val ticket = state.begin(1)!!
        state.suspend()
        assertFalse(state.isCurrent(ticket))
        assertFalse(state.acknowledge(ticket))
        assertFalse(state.pending)
        assertTrue(state.needsResync)
    }

    @Test fun resumeReassertsGeometryAndIgnoresLateReplies() {
        val state = ViewAreaTransition().apply { resume() }
        val old = state.begin(1)!!
        state.suspend()
        state.resume()
        val current = state.begin(0)!!
        assertFalse(state.acknowledge(old))
        assertFalse(state.needsResync)
        assertTrue(state.acknowledge(current))
        assertTrue(state.frame())
        assertFalse(state.isCurrent(current))
    }

    @Test fun duplicateLayoutCallbacksDoNotExtendDeadline() {
        val state = ViewAreaTransition().apply { resume() }
        val ticket = state.begin(1)!!
        repeat(20) { assertNull(state.begin(1)) }
        assertTrue(state.isCurrent(ticket))
        assertFalse(state.frame()) // A frame alone is not an acknowledgement.
        assertTrue(state.acknowledge(ticket))
        assertTrue(state.frame())
        assertFalse(state.pending)
    }

    @Test fun rapidToggleOnlyLatestCommandMayComplete() {
        val state = ViewAreaTransition().apply { resume() }
        val split = state.begin(1)!!
        val full = state.begin(0)!!
        assertFalse(state.acknowledge(split))
        assertTrue(state.acknowledge(full))
        state.suspend()
        assertFalse(state.frame())
        assertFalse(state.isCurrent(full))
    }
}
