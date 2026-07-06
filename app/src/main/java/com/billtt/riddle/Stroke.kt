package com.billtt.riddle

import android.graphics.RectF

/** A single sampled pen point (screen coordinates + pressure 0..1). */
data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

/** One stroke (pen-down to pen-up), sampled points kept in time order. */
class Stroke(val points: List<StrokePoint>) {

    val bounds: RectF = RectF().apply {
        if (points.isNotEmpty()) {
            set(points[0].x, points[0].y, points[0].x, points[0].y)
            for (p in points) union(p.x, p.y)
        }
    }

    fun intersectsCircle(cx: Float, cy: Float, radius: Float): Boolean {
        val r2 = radius * radius
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }
}
