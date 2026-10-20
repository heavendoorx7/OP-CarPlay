package com.shilapi.xcertplay

import kotlin.math.hypot
import kotlin.math.sqrt

/** Tracks the same five contacts; lifting fingers cannot masquerade as contraction. */
internal class FiveFingerPinchGesture {
    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
    data class Point(val id: Int, val x: Float, val y: Float)
    data class Result(val consumed: Boolean = false, val started: Boolean = false, val openSettings: Boolean = false)

    private var captured = false
    private var tracking = false
    private var startTime = 0L
    private var startRadius = 0f
    private var startDistances = emptyMap<Int, Float>()

    fun reset() {
        captured = false
        tracking = false
        startDistances = emptyMap()
    }

    fun update(action: Action, points: List<Point>, timeMs: Long, density: Float): Result {
        if (action == Action.DOWN) reset()
        if (action == Action.UP || action == Action.CANCEL) {
            val consumed = captured
            reset()
            return Result(consumed = consumed)
        }
        if (!captured && action == Action.POINTER_DOWN && points.size >= 5) {
            captured = true
            startTime = timeMs
            startDistances = distances(points)
            startRadius = radius(startDistances)
            tracking = points.size == 5 && startDistances.size == 5 && startRadius >= 48f * density
            return Result(consumed = true, started = true)
        }
        if (!captured) return Result()
        if (action == Action.POINTER_UP || points.size != 5 ||
            points.map { it.id }.toSet() != startDistances.keys ||
            timeMs - startTime !in 0L..1800L) tracking = false

        if (tracking && action == Action.MOVE) {
            val now = distances(points)
            val currentRadius = radius(now)
            val contractedFingers = now.count { (id, distance) -> distance <= startDistances.getValue(id) * 0.85f }
            if (currentRadius <= startRadius * 0.70f &&
                startRadius - currentRadius >= 24f * density && contractedFingers >= 4) {
                tracking = false // Consume until all fingers lift; fire only once.
                return Result(consumed = true, openSettings = true)
            }
        }
        return Result(consumed = true)
    }

    private fun distances(points: List<Point>): Map<Int, Float> {
        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        return points.associate { it.id to hypot(it.x - centerX, it.y - centerY) }
    }

    private fun radius(distances: Map<Int, Float>): Float =
        sqrt(distances.values.sumOf { (it * it).toDouble() } / distances.size).toFloat()
}
