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
import com.arnold.voicetranslator.writing.WritingScript
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal object WritingPalette {
    val background = Color(0xFF2B3344)
    val hiragana = Color(0xFF5EB3EA)
    val katakana = Color(0xFFF5A623)
    val mixed = Color(0xFF3DCC73)
    val locked = Color(0xFF8E949E)
    val reviewAll = Color(0xFF7EC8EA)
    val ink = Color(0xFFF7F8FA)
    val guide = Color(0xFF9AA3B5)
    val startDot = Color(0xFF22C55E)
    val endDot = Color(0xFFEF4444)
    val canvas = Color(0xFF3A4458)
    val muted = Color(0xFFC5CAD3)
    val introHeader = Color(0xFF7EBEF0)
    val ok = Color(0xFF34C759)
    val error = Color(0xFFFF6B6B)
    val hint = Color(0xFFFFD56A)
}

internal fun scriptColor(script: WritingScript): Color = when (script) {
    WritingScript.Hiragana -> WritingPalette.hiragana
    WritingScript.Katakana -> WritingPalette.katakana
    WritingScript.Mixed -> WritingPalette.mixed
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
