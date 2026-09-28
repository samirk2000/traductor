package com.arnold.voicetranslator.writing

import kotlin.math.hypot

/** A point in KanjiVG's 109×109 coordinate space (origin top-left, y grows downward). */
data class Vec(val x: Float, val y: Float)

enum class StrokeVerdict {
    Accepted,
    TooShort,
    WrongDirection,
    WrongShape,
}

/**
 * Compares a freehand stroke with the KanjiVG centerline of the expected stroke.
 *
 * Long strokes must start near the green dot, end near the red dot, and follow
 * the centerline. Very short strokes (dakuten ticks) use a tighter window so
 * the two neighbouring ticks are not accepted for each other. Closed strokes
 * (the handakuten circle) are matched by shape and position, because the start
 * and end are the same point.
 */
object StrokeMatcher {

    const val VIEW_BOX = 109f

    fun evaluate(user: List<Vec>, expected: List<Vec>): StrokeVerdict {
        if (user.size < 2 || expected.size < 2) return StrokeVerdict.TooShort
        val expectedLength = length(expected)
        val userLength = length(user)
        if (expectedLength < 1f) return StrokeVerdict.TooShort

        val closed = dist(expected.first(), expected.last()) < 12f && expectedLength >= 24f
        if (closed) return evaluateClosed(user, expected, expectedLength, userLength)

        val short = expectedLength < 18f
        val minLength = if (short) expectedLength * 0.25f else expectedLength * 0.45f
        if (userLength < minLength) return StrokeVerdict.TooShort

        val samples = 16
        val userSamples = resample(user, samples)
        val expectedSamples = resample(expected, samples)
        val start = dist(userSamples.first(), expectedSamples.first())
        val end = dist(userSamples.last(), expectedSamples.last())
        val reverseStart = dist(userSamples.first(), expectedSamples.last())
        val reverseEnd = dist(userSamples.last(), expectedSamples.first())
        if (!short && expectedLength >= 18f && reverseStart + reverseEnd + 12f < start + end) {
            return StrokeVerdict.WrongDirection
        }

        val startLimit = if (short) 7.4f else 18f
        val endLimit = if (short) 7.4f else 20f
        if (start > startLimit || end > endLimit) return StrokeVerdict.WrongShape

        val mean = (0 until samples).sumOf { i ->
            dist(userSamples[i], expectedSamples[i]).toDouble()
        }.toFloat() / samples
        val meanLimit = if (short) 6.5f else 15f
        if (mean > meanLimit) return StrokeVerdict.WrongShape

        val coverLimit = if (short) 8f else 18f
        val cover = expectedSamples.maxOf { minDistanceToPolyline(it, user) }
        if (cover > coverLimit) return StrokeVerdict.WrongShape
        return StrokeVerdict.Accepted
    }

    private fun evaluateClosed(
        user: List<Vec>,
        expected: List<Vec>,
        expectedLength: Float,
        userLength: Float,
    ): StrokeVerdict {
        if (userLength < expectedLength * 0.55f) return StrokeVerdict.TooShort
        if (userLength > expectedLength * 2.4f) return StrokeVerdict.WrongShape
        if (dist(centroid(user), centroid(expected)) > 12f) return StrokeVerdict.WrongShape
        val samples = 24
        val userSamples = resample(user, samples)
        val expectedSamples = resample(expected, samples)
        val coversExpected = expectedSamples.maxOf { minDistanceToPolyline(it, user) }
        val coversUser = userSamples.maxOf { minDistanceToPolyline(it, expected) }
        if (coversExpected > 12f || coversUser > 12f) return StrokeVerdict.WrongShape
        return StrokeVerdict.Accepted
    }

    fun length(points: List<Vec>): Float {
        var total = 0f
        for (i in 1 until points.size) total += dist(points[i - 1], points[i])
        return total
    }

    fun dist(a: Vec, b: Vec): Float = hypot(a.x - b.x, a.y - b.y)

    /** Prefix of [points] covering [fraction] of its arc length. Used by the stroke animation. */
    fun portion(points: List<Vec>, fraction: Float): List<Vec> {
        if (points.size < 2) return points
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0f) return listOf(points.first())
        if (clamped >= 1f) return points
        val target = length(points) * clamped
        var walked = 0f
        val out = ArrayList<Vec>(points.size)
        out.add(points.first())
        for (i in 1 until points.size) {
            val segment = dist(points[i - 1], points[i])
            if (walked + segment >= target) {
                val t = if (segment == 0f) 0f else (target - walked) / segment
                val previous = points[i - 1]
                val next = points[i]
                out.add(Vec(previous.x + (next.x - previous.x) * t, previous.y + (next.y - previous.y) * t))
                return out
            }
            walked += segment
            out.add(points[i])
        }
        return points
    }

    fun pointAt(points: List<Vec>, fraction: Float): Vec = portion(points, fraction).last()

    private fun resample(points: List<Vec>, count: Int): List<Vec> {
        if (points.size < 2 || count < 2) return points
        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + dist(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (total < 1e-3f) return List(count) { points.first() }
        val out = ArrayList<Vec>(count)
        var segment = 1
        for (i in 0 until count) {
            val target = total * (i / (count - 1f))
            while (segment < cumulative.lastIndex && cumulative[segment] < target) segment++
            val span = cumulative[segment] - cumulative[segment - 1]
            val t = if (span == 0f) 0f else (target - cumulative[segment - 1]) / span
            val a = points[segment - 1]
            val b = points[segment]
            out.add(Vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        return out
    }

    private fun minDistanceToPolyline(point: Vec, polyline: List<Vec>): Float {
        var best = Float.MAX_VALUE
        for (i in 1 until polyline.size) {
            best = minOf(best, distanceToSegment(point, polyline[i - 1], polyline[i]))
        }
        return best
    }

    private fun distanceToSegment(point: Vec, a: Vec, b: Vec): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val denom = dx * dx + dy * dy
        if (denom == 0f) return dist(point, a)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / denom).coerceIn(0f, 1f)
        return dist(point, Vec(a.x + t * dx, a.y + t * dy))
    }

    private fun centroid(points: List<Vec>): Vec {
        var x = 0f
        var y = 0f
        for (point in points) {
            x += point.x
            y += point.y
        }
        val n = points.size.coerceAtLeast(1)
        return Vec(x / n, y / n)
    }
}
