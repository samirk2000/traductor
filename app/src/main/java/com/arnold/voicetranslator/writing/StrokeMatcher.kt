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
 * How far a stroke may drift from the KanjiVG centerline.
 * [Relaxed] is the default: direction, order and the rough shape still have to
 * be right, but the start does not have to sit on the guide dot.
 */
enum class StrokeTolerance {
    Relaxed,
    Normal,
    Strict,
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

    fun evaluate(
        user: List<Vec>,
        expected: List<Vec>,
        tolerance: StrokeTolerance = StrokeTolerance.Relaxed,
    ): StrokeVerdict {
        if (user.size < 2 || expected.size < 2) return StrokeVerdict.TooShort
        val expectedLength = length(expected)
        val userLength = length(user)
        if (expectedLength < 1f) return StrokeVerdict.TooShort

        val closed = dist(expected.first(), expected.last()) < 12f && expectedLength >= 24f
        if (closed) return evaluateClosed(user, expected, expectedLength, userLength, tolerance)

        val short = expectedLength < 18f
        val limits = limits(short, tolerance)
        if (userLength < expectedLength * limits.minLengthRatio) return StrokeVerdict.TooShort

        val samples = 16
        val userSamples = resample(user, samples)
        val expectedSamples = resample(expected, samples)
        val start = dist(userSamples.first(), expectedSamples.first())
        val end = dist(userSamples.last(), expectedSamples.last())
        val reverseStart = dist(userSamples.first(), expectedSamples.last())
        val reverseEnd = dist(userSamples.last(), expectedSamples.first())
        if (!short && reverseStart + reverseEnd + limits.directionBias < start + end) {
            return StrokeVerdict.WrongDirection
        }

        if (start > limits.start || end > limits.end) return StrokeVerdict.WrongShape

        val mean = (0 until samples).sumOf { i ->
            dist(userSamples[i], expectedSamples[i]).toDouble()
        }.toFloat() / samples
        if (mean > limits.mean) return StrokeVerdict.WrongShape

        val cover = expectedSamples.maxOf { minDistanceToPolyline(it, user) }
        if (cover > limits.cover) return StrokeVerdict.WrongShape
        return StrokeVerdict.Accepted
    }

    private data class Limits(
        val minLengthRatio: Float,
        val start: Float,
        val end: Float,
        val mean: Float,
        val cover: Float,
        val directionBias: Float,
    )

    private fun limits(short: Boolean, tolerance: StrokeTolerance): Limits = when (tolerance) {
        StrokeTolerance.Relaxed -> if (short) {
            // Short marks (dakuten ticks) stay tight so neighbours are not interchangeable.
            Limits(0.18f, 7.2f, 7.2f, 6.6f, 8f, 18f)
        } else {
            Limits(0.28f, 32f, 36f, 26f, 30f, 22f)
        }
        StrokeTolerance.Normal -> if (short) {
            Limits(0.25f, 8f, 8f, 7f, 9f, 12f)
        } else {
            Limits(0.40f, 22f, 26f, 18f, 22f, 14f)
        }
        StrokeTolerance.Strict -> if (short) {
            Limits(0.35f, 6f, 6f, 5.2f, 6.5f, 8f)
        } else {
            Limits(0.55f, 12f, 14f, 10f, 12f, 8f)
        }
    }

    private fun evaluateClosed(
        user: List<Vec>,
        expected: List<Vec>,
        expectedLength: Float,
        userLength: Float,
        tolerance: StrokeTolerance,
    ): StrokeVerdict {
        val (minRatio, maxRatio, center, coverLimit) = when (tolerance) {
            StrokeTolerance.Relaxed -> ClosedLimits(0.40f, 3.0f, 18f, 18f)
            StrokeTolerance.Normal -> ClosedLimits(0.50f, 2.6f, 14f, 14f)
            StrokeTolerance.Strict -> ClosedLimits(0.60f, 2.2f, 10f, 10f)
        }
        if (userLength < expectedLength * minRatio) return StrokeVerdict.TooShort
        if (userLength > expectedLength * maxRatio) return StrokeVerdict.WrongShape
        if (dist(centroid(user), centroid(expected)) > center) return StrokeVerdict.WrongShape
        val samples = 24
        val userSamples = resample(user, samples)
        val expectedSamples = resample(expected, samples)
        val coversExpected = expectedSamples.maxOf { minDistanceToPolyline(it, user) }
        val coversUser = userSamples.maxOf { minDistanceToPolyline(it, expected) }
        if (coversExpected > coverLimit || coversUser > coverLimit) return StrokeVerdict.WrongShape
        return StrokeVerdict.Accepted
    }

    private data class ClosedLimits(
        val minRatio: Float,
        val maxRatio: Float,
        val center: Float,
        val cover: Float,
    )

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
