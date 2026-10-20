package com.shilapi.xcertplay

/** Startup default and temporary in-session mode are deliberately independent. */
internal class FullscreenModeState {
    var currentBars: Boolean? = null
        private set

    fun enter(defaultBars: Boolean, restoredBars: Boolean?, switchedBars: Boolean?): Boolean {
        // An old Intent can survive process death. Only a live process may hand off a mode.
        val liveSwitch = if (currentBars != null) switchedBars else null
        return (liveSwitch ?: restoredBars ?: currentBars ?: defaultBars).also { currentBars = it }
    }

    fun select(showBars: Boolean) { currentBars = showBars }

    fun reset() { currentBars = null }
}
