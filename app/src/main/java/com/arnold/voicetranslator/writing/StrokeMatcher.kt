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
 * [Relaxed] is the default. A little wobble still passes. Direction, shape,
 * and where the stroke sits in the cell have to match.
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
        if (reverseStart + reverseEnd + limits.directionBias < start + end) {
            return StrokeVerdict.WrongDirection
        }

        if (start > limits.start || end > limits.end) return StrokeVerdict.WrongShape

        var mean = 0f
        var frechet = 0f
        for (i in 0 until samples) {
            val delta = dist(userSamples[i], expectedSamples[i])
            mean += delta
            if (delta > frechet) frechet = delta
        }
        mean /= samples
        if (mean > limits.mean || frechet > limits.frechet) return StrokeVerdict.WrongShape

        val cover = expectedSamples.maxOf { minDistanceToPolyline(it, user) }
        if (cover > limits.cover) return StrokeVerdict.WrongShape
        if (!withinProportion(user, expected, limits)) return StrokeVerdict.WrongShape
        return StrokeVerdict.Accepted
    }

    private data class Limits(
        val minLengthRatio: Float,
        val start: Float,
        val end: Float,
        val mean: Float,
        val frechet: Float,
        val cover: Float,
        val directionBias: Float,
        val center: Float,
        val minSpan: Float,
        val maxSpan: Float,
        val spanSlack: Float,
    )

    private fun limits(short: Boolean, tolerance: StrokeTolerance): Limits = when (tolerance) {
        StrokeTolerance.Relaxed -> if (short) {
            // Short marks (dakuten ticks) stay tight so neighbours are not interchangeable.
            Limits(0.30f, 6.0f, 6.0f, 4.8f, 8.0f, 6.0f, 5f, 5.2f, 0.50f, 1.70f, 4.5f)
        } else {
            Limits(0.50f, 16f, 18f, 11f, 20f, 14f, 6f, 13f, 0.68f, 1.45f, 14f)
        }
        StrokeTolerance.Normal -> if (short) {
            Limits(0.38f, 5.0f, 5.0f, 4.0f, 6.5f, 5.0f, 4f, 4.2f, 0.58f, 1.50f, 3.5f)
        } else {
            Limits(0.58f, 12f, 13f, 8f, 15f, 11f, 5f, 9f, 0.75f, 1.32f, 10f)
        }
        StrokeTolerance.Strict -> if (short) {
            Limits(0.48f, 3.6f, 3.6f, 3.0f, 5.0f, 3.8f, 3f, 3.2f, 0.68f, 1.35f, 2.4f)
        } else {
            Limits(0.68f, 8f, 9f, 6f, 11f, 8f, 4f, 6.5f, 0.82f, 1.20f, 6f)
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
            StrokeTolerance.Relaxed -> ClosedLimits(0.55f, 2.2f, 12f, 12f)
            StrokeTolerance.Normal -> ClosedLimits(0.62f, 1.9f, 9f, 9f)
            StrokeTolerance.Strict -> ClosedLimits(0.72f, 1.65f, 6.5f, 6.5f)
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

    private data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
        val center: Vec get() = Vec((minX + maxX) / 2f, (minY + maxY) / 2f)
    }

    /** The stroke has to occupy about the same part of the 109×109 cell. */
    private fun withinProportion(user: List<Vec>, expected: List<Vec>, limits: Limits): Boolean {
        val userBox = bounds(user)
        val expectedBox = bounds(expected)
        val expectedSpan = maxOf(expectedBox.width, expectedBox.height).coerceAtLeast(1f)
        val userSpan = maxOf(userBox.width, userBox.height)
        val allowedShrink = maxOf(expectedSpan * (1f - limits.minSpan), limits.spanSlack)
        val allowedGrow = maxOf(expectedSpan * (limits.maxSpan - 1f), limits.spanSlack)
        if (expectedSpan - userSpan > allowedShrink || userSpan - expectedSpan > allowedGrow) return false
        return dist(userBox.center, expectedBox.center) <= limits.center
    }

    private fun bounds(points: List<Vec>): Bounds {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in points) {
            if (point.x < minX) minX = point.x
            if (point.y < minY) minY = point.y
            if (point.x > maxX) maxX = point.x
            if (point.y > maxY) maxY = point.y
        }
        return Bounds(minX, minY, maxX, maxY)
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
