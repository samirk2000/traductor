package com.arnold.voicetranslator.writing.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arnold.voicetranslator.writing.StrokeMatcher
import com.arnold.voicetranslator.writing.Vec
import kotlin.math.hypot

@Composable
internal fun WritingIntroDialog(
    strokes: List<List<Vec>>,
    title: String,
    eachCharacter: String,
    drawSentence: String,
    greenWord: String,
    redWord: String,
    optional: String,
    confirm: String,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onConfirm,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        WritingIntroCard(
            strokes = strokes,
            title = title,
            eachCharacter = eachCharacter,
            drawSentence = drawSentence,
            greenWord = greenWord,
            redWord = redWord,
            optional = optional,
            confirm = confirm,
            onConfirm = onConfirm,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
internal fun WritingIntroCard(
    strokes: List<List<Vec>>,
    title: String,
    eachCharacter: String,
    drawSentence: String,
    greenWord: String,
    redWord: String,
    optional: String,
    confirm: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WritingPalette.introHeader)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 28.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(Color(0xFFE6E8EC), RoundedCornerShape(8.dp)),
            ) {
                IntroDiagram(strokes = strokes, modifier = Modifier.fillMaxSize().padding(8.dp))
            }
            Column(modifier = Modifier.padding(horizontal = 22.dp)) {
                Text(text = eachCharacter, color = Color(0xFF1C1C1E), fontSize = 18.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = highlightDots(drawSentence, greenWord, redWord),
                    color = Color(0xFF1C1C1E),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(text = optional, color = Color(0xFF1C1C1E), fontSize = 18.sp, lineHeight = 24.sp)
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WritingPalette.ok,
                    contentColor = Color.White,
                ),
            ) {
                Text(text = confirm, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun WritingSettingsDialog(
    lessonsEnabled: Boolean,
    title: String,
    toggleTitle: String,
    toggleDescription: String,
    attribution: String,
    closeLabel: String,
    onToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF343C4E),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = toggleTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(text = toggleDescription, color = WritingPalette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    Switch(
                        checked = lessonsEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = WritingPalette.hiragana,
                            checkedThumbColor = Color.White,
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(text = attribution, color = WritingPalette.muted, fontSize = 12.sp, lineHeight = 16.sp)
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text(text = closeLabel, color = WritingPalette.hiragana)
                }
            }
        }
    }
}

@Composable
internal fun WritingMessageDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onConfirm) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF343C4E)) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(text = title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(text = body, color = WritingPalette.muted, fontSize = 16.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WritingPalette.ok,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(text = confirm, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun IntroDiagram(strokes: List<List<Vec>>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.minDimension * 0.075f
        strokes.forEach { stroke ->
            drawKanaStroke(stroke, Color(0xFF6E7580), width, size)
        }
        val labelPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#F5A623")
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            textSize = size.minDimension * 0.09f
        }
        strokes.forEachIndexed { index, stroke ->
            if (stroke.size < 2) return@forEachIndexed
            val from = kanaOffset(StrokeMatcher.pointAt(stroke, 0.08f), size, padFraction = 0.12f)
            val to = kanaOffset(StrokeMatcher.pointAt(stroke, 0.34f), size, padFraction = 0.12f)
            drawArrow(from, to, Color(0xFFF5A623), widthPx = size.minDimension * 0.012f, headPx = size.minDimension * 0.045f)
            val dx = to.x - from.x
            val dy = to.y - from.y
            val length = hypot(dx, dy).coerceAtLeast(1f)
            val label = androidx.compose.ui.geometry.Offset(
                x = from.x + (-dy / length) * size.minDimension * 0.08f,
                y = from.y + (dx / length) * size.minDimension * 0.08f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                (index + 1).toString(),
                label.x,
                label.y + labelPaint.textSize * 0.35f,
                labelPaint,
            )
        }
    }
}

private fun highlightDots(sentence: String, greenWord: String, redWord: String) = buildAnnotatedString {
    data class Mark(val start: Int, val end: Int, val color: Color)
    val marks = buildList {
        val greenAt = sentence.indexOf(greenWord)
        if (greenAt >= 0) add(Mark(greenAt, greenAt + greenWord.length, Color(0xFF1F9D4D)))
        val redAt = sentence.indexOf(redWord)
        if (redAt >= 0) add(Mark(redAt, redAt + redWord.length, Color(0xFFE23B3B)))
    }.sortedBy { it.start }
    var cursor = 0
    for (mark in marks) {
        if (mark.start < cursor) continue
        if (mark.start > cursor) append(sentence.substring(cursor, mark.start))
        withStyle(SpanStyle(color = mark.color, fontWeight = FontWeight.SemiBold)) {
            append(sentence.substring(mark.start, mark.end))
        }
        cursor = mark.end
    }
    if (cursor < sentence.length) append(sentence.substring(cursor))
}
