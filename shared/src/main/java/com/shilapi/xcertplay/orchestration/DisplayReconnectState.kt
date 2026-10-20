package com.shilapi.xcertplay.orchestration

/** Latest-size wins; a failed soft reconnect gets one bounded transport fallback. */
internal class DisplayReconnectState {
    enum class TimeoutAction { NONE, RESTART_TRANSPORT, FAILED }
    private enum class Phase { IDLE, SOFT, FALLBACK }
    private var phase = Phase.IDLE
    private var generation = 0L
    private var width = 0
    private var height = 0

    @Synchronized fun begin(width: Int, height: Int, directBootstrap: Boolean = false): Long {
        require(width > 0 && height > 0)
        this.width = width
        this.height = height
        phase = if (directBootstrap) Phase.FALLBACK else Phase.SOFT
        return ++generation
    }

    @Synchronized fun isActive(): Boolean = phase != Phase.IDLE
    @Synchronized fun isSoft(ticket: Long): Boolean = ticket == generation && phase == Phase.SOFT
    @Synchronized fun isFallback(ticket: Long): Boolean = ticket == generation && phase == Phase.FALLBACK

    @Synchronized fun confirmFrame(width: Int, height: Int): Boolean {
        if (phase == Phase.IDLE || width != this.width || height != this.height) return false
        phase = Phase.IDLE
        return true
    }

    @Synchronized fun timeout(ticket: Long): TimeoutAction {
        if (ticket != generation) return TimeoutAction.NONE
        return when (phase) {
            Phase.IDLE -> TimeoutAction.NONE
            Phase.SOFT -> {
                phase = Phase.FALLBACK
                TimeoutAction.RESTART_TRANSPORT
            }
            Phase.FALLBACK -> {
                phase = Phase.IDLE
                TimeoutAction.FAILED
            }
        }
    }

    @Synchronized fun cancel() {
        ++generation
        phase = Phase.IDLE
    }
}
