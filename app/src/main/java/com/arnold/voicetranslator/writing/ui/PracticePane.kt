package com.arnold.voicetranslator.writing.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import com.arnold.voicetranslator.writing.GuideStyle
import com.arnold.voicetranslator.writing.PracticeMode
import com.arnold.voicetranslator.writing.PracticePhase
import com.arnold.voicetranslator.writing.PracticeState
import com.arnold.voicetranslator.writing.RecallAssist
import com.arnold.voicetranslator.writing.StrokeMatcher
import com.arnold.voicetranslator.writing.StrokeVerdict
import com.arnold.voicetranslator.writing.Vec
import kotlinx.coroutines.delay

@Composable
internal fun WritingPracticePage(
    state: PracticeState,
    uiLanguage: UiLanguage,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onStroke: (List<Vec>) -> Unit,
    onHint: () -> Unit,
    onGiveUp: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strokeCount = state.strokes.size.coerceAtLeast(1)
    val fraction = if (state.sessionComplete) {
        1f
    } else {
        val glyphFraction = (state.glyphIndex + state.strokeIndex.toFloat() / strokeCount) /
            state.glyphCount.coerceAtLeast(1)
        (state.itemIndex + glyphFraction) / state.itemCount.coerceAtLeast(1)
    }
    val characterKey = listOf(state.character, state.itemIndex, state.glyphIndex, state.phase)
    var playToken by remember(characterKey) { mutableIntStateOf(0) }
    var playProgress by remember(characterKey) { mutableFloatStateOf(-1f) }
    LaunchedEffect(playToken, characterKey) {
        if (playToken == 0) {
            playProgress = -1f
            return@LaunchedEffect
        }
        val count = state.strokes.size.coerceAtLeast(1)
        val anim = Animatable(0f)
        playProgress = 0f
        anim.animateTo(
            targetValue = count.toFloat(),
            animationSpec = tween(durationMillis = count * 680, easing = LinearEasing),
        ) {
            playProgress = value
        }
        delay(280)
        playProgress = -1f
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        PracticeTopBar(
            progress = fraction.coerceIn(0f, 1f),
            uiLanguage = uiLanguage,
            onClose = onClose,
            onSettings = onSettings,
        )
        Text(
            text = state.phase.label(uiLanguage),
            color = WritingPalette.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp, start = 8.dp),
        )
        if (state.showAnswer) {
            Text(
                text = localized(uiLanguage, R.string.writing_answer_shown),
                color = WritingPalette.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
            )
        }
        Text(
            text = state.headline,
            color = WritingPalette.onSurface,
            fontSize = if (state.headline.length <= 4) 48.sp else 28.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Text(
            text = state.subtitle,
            color = WritingPalette.secondary,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
        )
        if (state.glyphCount > 1 && state.phase != PracticePhase.Recall) {
            Text(
                text = localized(uiLanguage, R.string.writing_glyph_progress, state.glyphIndex + 1, state.glyphCount),
                color = WritingPalette.muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val side = minOf(maxWidth, maxHeight)
                KanaDrawingCanvas(
                    strokes = state.strokes,
                    userStrokes = state.userStrokes,
                    strokeIndex = state.strokeIndex,
                    guideStyle = state.guideStyle,
                    recallAssist = state.recallAssist,
                    showAnswer = state.showAnswer,
                    showReference = state.showReference,
                    justCleared = state.justCleared,
                    showHint = state.showHint,
                    error = state.error != null,
                    enabled = !state.justCleared && !state.sessionComplete && playProgress < 0f,
                    playProgress = playProgress,
                    canvasDescription = localized(uiLanguage, R.string.writing_canvas_cd),
                    onStroke = onStroke,
                    modifier = Modifier.size(side),
                )
            }
            if (state.justCleared) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(44.dp)
                        .background(WritingPalette.ok, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = WritingPalette.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        state.error?.let { verdict ->
            Text(
                text = verdict.message(uiLanguage),
                color = WritingPalette.error,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.phase != PracticePhase.Recall) {
                Button(
                    onClick = { playToken += 1 },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WritingPalette.surfaceVariant,
                        contentColor = WritingPalette.onSurface,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(text = localized(uiLanguage, R.string.writing_watch_order), fontSize = 14.sp)
                }
            }
            if (state.phase == PracticePhase.Fade) {
                Button(
                    onClick = onHint,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WritingPalette.primary,
                        contentColor = androidx.compose.ui.graphics.Color(0xFF0F1115),
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text(
                        text = localized(uiLanguage, R.string.writing_teach_stroke),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (state.phase == PracticePhase.Recall) {
                Button(
                    onClick = onGiveUp,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WritingPalette.surfaceVariant,
                        contentColor = WritingPalette.onSurface,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text(text = localized(uiLanguage, R.string.writing_give_up), fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (state.sessionComplete) {
        val lesson = state.mode == PracticeMode.Lesson
        WritingMessageDialog(
            title = localized(
                uiLanguage,
                if (lesson) R.string.writing_lesson_done else R.string.writing_review_done,
            ),
            body = localized(
                uiLanguage,
                when {
                    lesson && state.unlockedNext -> R.string.writing_lesson_done_body
                    lesson -> R.string.writing_level_done_body
                    else -> R.string.writing_review_done_body
                },
            ),
            confirm = localized(uiLanguage, R.string.writing_continue),
            onConfirm = onContinue,
        )
    }
}

@Composable
private fun PracticeTopBar(
    progress: Float,
    uiLanguage: UiLanguage,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = localized(uiLanguage, R.string.close),
                tint = WritingPalette.onSurface,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(CircleShape),
            color = WritingPalette.primary,
            trackColor = WritingPalette.surfaceVariant,
        )
        IconButton(onClick = onSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = localized(uiLanguage, R.string.settings_cd),
                tint = WritingPalette.onSurface,
            )
        }
    }
}

@Composable
private fun KanaDrawingCanvas(
    strokes: List<List<Vec>>,
    userStrokes: List<List<Vec>>,
    strokeIndex: Int,
    guideStyle: GuideStyle,
    recallAssist: RecallAssist,
    showAnswer: Boolean,
    showReference: Boolean,
    justCleared: Boolean,
    showHint: Boolean,
    error: Boolean,
    enabled: Boolean,
    playProgress: Float,
    canvasDescription: String,
    onStroke: (List<Vec>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var live by remember(strokeIndex) { mutableStateOf<List<Offset>>(emptyList()) }
    val shape = RoundedCornerShape(18.dp)
    Canvas(
        modifier = modifier
            .clip(shape)
            .background(WritingPalette.canvas)
            .border(
                width = if (error) 3.dp else 1.dp,
                color = if (error) WritingPalette.error else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f),
                shape = shape,
            )
            .semantics { contentDescription = canvasDescription }
            .pointerInput(enabled, strokeIndex) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val collected = ArrayList<Offset>()
                    collected.add(down.position)
                    live = collected.toList()
                    val canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            val points = collected.map { viewBoxPoint(it, canvasSize) }
                            live = emptyList()
                            if (points.size >= 2) onStroke(points)
                            break
                        }
                        collected.add(change.position)
                        change.consume()
                        live = collected.toList()
                    }
                }
            },
    ) {
        val strokeWidth = size.minDimension * 0.07f
        if (showReference) {
            strokes.forEach { stroke ->
                drawKanaStroke(stroke, WritingPalette.reference, strokeWidth * 0.92f, size)
            }
        } else if (guideStyle == GuideStyle.Full) {
            strokes.forEach { stroke ->
                drawKanaStroke(stroke, WritingPalette.guide, strokeWidth, size)
            }
        } else if (guideStyle == GuideStyle.Faint) {
            strokes.forEach { stroke ->
                drawKanaStroke(stroke, WritingPalette.guide.copy(alpha = 0.35f), strokeWidth * 0.55f, size)
            }
        } else if (recallAssist != RecallAssist.None && strokeIndex in strokes.indices) {
            val stroke = strokes[strokeIndex]
            if (recallAssist == RecallAssist.Outline) {
                drawKanaStroke(stroke, WritingPalette.guide.copy(alpha = 0.35f), strokeWidth * 0.55f, size)
            } else {
                drawKanaStroke(stroke, WritingPalette.guide, strokeWidth, size)
            }
        }
        userStrokes.forEach { stroke ->
            drawKanaStroke(stroke, WritingPalette.ink, strokeWidth, size)
        }
        if (playProgress >= 0f) {
            val whole = playProgress.toInt().coerceIn(0, strokes.size)
            for (index in 0 until whole) {
                drawKanaStroke(strokes[index], WritingPalette.ink, strokeWidth, size)
            }
            if (whole < strokes.size) {
                val partial = StrokeMatcher.portion(strokes[whole], playProgress - whole)
                drawKanaStroke(partial, WritingPalette.ink, strokeWidth, size)
            }
        }
        if (showHint && strokeIndex in strokes.indices) {
            drawKanaStroke(strokes[strokeIndex], WritingPalette.hint, strokeWidth * 0.72f, size)
        }
        if (live.size >= 2) {
            val path = Path()
            path.moveTo(live.first().x, live.first().y)
            for (index in 1 until live.size) path.lineTo(live[index].x, live[index].y)
            drawPath(
                path = path,
                color = WritingPalette.ink,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        val showStart = playProgress < 0f && !justCleared && strokeIndex in strokes.indices &&
            (showAnswer || guideStyle != GuideStyle.None || recallAssist == RecallAssist.Outline)
        if (showStart) {
            val stroke = strokes[strokeIndex]
            val start = kanaOffset(stroke.first(), size)
            drawCircle(WritingPalette.startDot, radius = size.minDimension * 0.028f, center = start)
            if (showAnswer || guideStyle == GuideStyle.Full) {
                val closed = StrokeMatcher.dist(stroke.first(), stroke.last()) < 12f
                val endPoint = if (closed) {
                    kanaOffset(StrokeMatcher.pointAt(stroke, 0.92f), size)
                } else {
                    kanaOffset(stroke.last(), size)
                }
                drawCircle(WritingPalette.endDot, radius = size.minDimension * 0.028f, center = endPoint)
            }
        }
    }
}

@Composable
private fun PracticePhase.label(uiLanguage: UiLanguage): String = when (this) {
    PracticePhase.Teach -> localized(uiLanguage, R.string.writing_phase_teach)
    PracticePhase.Fade -> localized(uiLanguage, R.string.writing_phase_fade)
    PracticePhase.Recall -> localized(uiLanguage, R.string.writing_phase_recall)
}

@Composable
private fun StrokeVerdict.message(uiLanguage: UiLanguage): String = when (this) {
    StrokeVerdict.TooShort -> localized(uiLanguage, R.string.writing_stroke_short)
    StrokeVerdict.WrongDirection -> localized(uiLanguage, R.string.writing_stroke_direction)
    StrokeVerdict.WrongShape -> localized(uiLanguage, R.string.writing_stroke_wrong)
    StrokeVerdict.Accepted -> ""
}
