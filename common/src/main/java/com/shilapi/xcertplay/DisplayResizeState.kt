package com.shilapi.xcertplay

internal data class DisplaySize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

/** Main-thread state for animated window changes and asynchronous controller teardown. */
internal class DisplayResizeState {
    enum class TimerAction { KEEP, CANCEL, REPLACE }
    data class Change(val previous: DisplaySize?, val current: DisplaySize)

    var current: DisplaySize? = null
        private set
    var pending: DisplaySize? = null
        private set
    var restarting: Boolean = false
        private set

    val readyToStart: Boolean
        get() = current != null && pending == null && !restarting

    fun observe(size: DisplaySize): TimerAction {
        if (size == current) {
            if (pending == null) return TimerAction.KEEP
            pending = null
            return TimerAction.CANCEL
        }
        if (size == pending) return TimerAction.KEEP
        pending = size
        return TimerAction.REPLACE
    }

    fun settle(): Change? {
        val size = pending ?: return null
        pending = null
        return Change(current, size).also { current = size }
    }

    fun restore(size: DisplaySize) {
        current = size
        pending = null
    }

    fun beginRestart(): Boolean {
        if (restarting) return false
        restarting = true
        return true
    }

    fun finishRestart() {
        restarting = false
    }
}
