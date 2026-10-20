package com.shilapi.xcertplay

/** UI-thread deadline ownership. A hidden surface cannot prove video progress. */
internal class ViewAreaTransition {
    var visible = false
        private set
    var ticket = 0L
        private set
    var target: Int? = null
        private set
    var awaitingFrame = false
        private set
    var needsResync = false
        private set
    val pending: Boolean get() = target != null

    fun resume() { visible = true }

    fun requireResync() { needsResync = true }

    fun suspend() {
        visible = false
        // A command may have reached the phone before its reply reaches us.
        // Reassert the current window on return, even if cached geometry matches.
        needsResync = needsResync || pending
        cancel()
    }

    fun begin(index: Int): Long? {
        if (!visible || target == index) return null
        target = index
        awaitingFrame = false
        needsResync = false
        return ++ticket
    }

    fun isCurrent(expected: Long): Boolean = visible && pending && ticket == expected

    fun acknowledge(expected: Long): Boolean {
        if (!isCurrent(expected)) return false
        awaitingFrame = true
        return true
    }

    fun frame(): Boolean {
        if (!visible || !awaitingFrame) return false
        cancel()
        return true
    }

    fun cancel() {
        ++ticket
        target = null
        awaitingFrame = false
    }
}
