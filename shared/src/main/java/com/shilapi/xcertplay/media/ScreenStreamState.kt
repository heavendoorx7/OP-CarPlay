package com.shilapi.xcertplay.media

/** Geometry belongs to the media session, not to an Activity or Surface. */
class ScreenStreamState {
    data class Snapshot(val activeTypes: Set<Int>, val decodedSizes: Map<Int, Pair<Int, Int>>)
    private val active = mutableSetOf<Int>()
    private val decoded = mutableMapOf<Int, Pair<Int, Int>>()

    @Synchronized fun setActive(type: Int, enabled: Boolean) {
        if (enabled) active.add(type) else {
            active.remove(type)
            decoded.remove(type)
        }
    }

    @Synchronized fun decoded(type: Int, width: Int, height: Int) {
        if (type in active && width > 0 && height > 0) decoded[type] = width to height
    }

    @Synchronized fun snapshot() = Snapshot(active.toSet(), decoded.toMap())

    @Synchronized fun clear() { active.clear(); decoded.clear() }
}
