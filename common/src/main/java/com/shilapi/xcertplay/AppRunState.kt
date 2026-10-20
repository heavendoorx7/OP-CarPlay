package com.shilapi.xcertplay

/** Invalidates callbacks from every old window, including windows hidden in the task stack. */
internal class AppRunState {
    private var generation = 0L
    private var closing = false

    @Synchronized fun enter(): Long? = if (closing) null else generation

    @Synchronized fun isActive(token: Long?): Boolean = !closing && token == generation

    @Synchronized fun beginExit(token: Long?): Boolean {
        if (!isActive(token)) return false
        closing = true
        generation++
        return true
    }

    @Synchronized fun finishExit() { closing = false }
}
