package com.arnold.voicetranslator.writing.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.arnold.voicetranslator.writing.StrokeMatcher
import com.arnold.voicetranslator.writing.Vec
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal object WritingPalette {
    val background = Color(0xFF0F1115)
    val surface = Color(0xFF171A21)
    val surfaceVariant = Color(0xFF21252E)
    val primary = Color(0xFF8AB4F8)
    val primaryContainer = Color(0xFF1B3A5A)
    val secondary = Color(0xFF7EE0DE)
    val secondaryContainer = Color(0xFF14494A)
    val onSurface = Color(0xFFE2E6EC)
    val muted = Color(0xFFA4A9B3)
    val ink = Color(0xFFE8EEF6)
    val guide = Color(0xFF5C6573)
    val reference = Color(0xFFB7BEC8)
    val startDot = Color(0xFF7EE0DE)
    val endDot = Color(0xFFFF8A7A)
    val canvas = Color(0xFF171A21)
    val ok = Color(0xFF7EE0DE)
    val error = Color(0xFFFF6B6B)
    val hint = Color(0xFF8AB4F8)
    val locked = Color(0xFF6B7280)
    val rail = Color(0xFF2C3544)
}

internal fun kanaOffset(point: Vec, size: Size, padFraction: Float = 0.08f): Offset {
    val pad = min(size.width, size.height) * padFraction
    val innerW = size.width - pad * 2f
    val innerH = size.height - pad * 2f
    return Offset(
        x = pad + point.x / StrokeMatcher.VIEW_BOX * innerW,
        y = pad + point.y / StrokeMatcher.VIEW_BOX * innerH,
    )
}

internal fun viewBoxPoint(offset: Offset, size: Size, padFraction: Float = 0.08f): Vec {
    val pad = min(size.width, size.height) * padFraction
    val innerW = (size.width - pad * 2f).coerceAtLeast(1f)
    val innerH = (size.height - pad * 2f).coerceAtLeast(1f)
    return Vec(
        x = (offset.x - pad) / innerW * StrokeMatcher.VIEW_BOX,
        y = (offset.y - pad) / innerH * StrokeMatcher.VIEW_BOX,
    )
}

internal fun DrawScope.drawKanaStroke(
    points: List<Vec>,
    color: Color,
    widthPx: Float,
    size: Size,
) {
    if (points.size < 2) return
    val path = Path()
    val first = kanaOffset(points.first(), size)
    path.moveTo(first.x, first.y)
    for (index in 1 until points.size) {
        val offset = kanaOffset(points[index], size)
        path.lineTo(offset.x, offset.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

internal fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color, widthPx: Float, headPx: Float) {
    drawLine(color = color, start = from, end = to, strokeWidth = widthPx, cap = StrokeCap.Round)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val spread = 0.5f
    val left = Offset(
        x = to.x - headPx * cos(angle - spread),
        y = to.y - headPx * sin(angle - spread),
    )
    val right = Offset(
        x = to.x - headPx * cos(angle + spread),
        y = to.y - headPx * sin(angle + spread),
    )
    val head = Path().apply {
        moveTo(to.x, to.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(head, color)
}
