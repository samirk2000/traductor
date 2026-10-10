package com.arnold.voicetranslator.writing.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import com.arnold.voicetranslator.writing.CourseTrack
import com.arnold.voicetranslator.writing.LessonAccess
import com.arnold.voicetranslator.writing.LessonListState
import com.arnold.voicetranslator.writing.LessonRow
import com.arnold.voicetranslator.writing.RecognizePrompt
import com.arnold.voicetranslator.writing.RecognizeUi
import com.arnold.voicetranslator.writing.StageCard
import com.arnold.voicetranslator.writing.StrokeTolerance
import com.arnold.voicetranslator.writing.StudyMode
import com.arnold.voicetranslator.writing.WritingNotice
import com.arnold.voicetranslator.writing.WritingScreenKind
import com.arnold.voicetranslator.writing.WritingUiState
import com.arnold.voicetranslator.writing.WritingViewModel

data class WritingActions(
    val onStage: (String) -> Unit,
    val onCloseLessons: () -> Unit,
    val onLesson: (String) -> Unit,
    val onDue: () -> Unit,
    val onReinforce: () -> Unit,
    val onOpenPaywall: () -> Unit,
    val onClosePaywall: () -> Unit,
    val onBuy: () -> Unit,
    val onRestore: () -> Unit,
    val onTolerance: (StrokeTolerance) -> Unit,
    val onDebugUnlock: (Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onCloseSettings: () -> Unit,
    val onToggleLessons: (Boolean) -> Unit,
    val onDismissIntro: () -> Unit,
    val onStroke: (List<com.arnold.voicetranslator.writing.Vec>) -> Unit,
    val onHint: () -> Unit,
    val onGiveUp: () -> Unit,
    val onSpeak: () -> Unit,
    val onBeginnerMode: (Boolean) -> Unit,
    val onAutoPronounce: (Boolean) -> Unit,
    val onClosePractice: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onStudyMode: (StudyMode) -> Unit,
    val onRecognizeAnswer: (Int) -> Unit,
    val onRecognizeAdvance: () -> Unit,
)

@Composable
fun WritingScreen(
    viewModel: WritingViewModel,
    uiLanguage: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(uiLanguage) {
        viewModel.setLanguage(uiLanguage)
    }
    WritingCourse(
        state = state,
        uiLanguage = uiLanguage,
        modifier = modifier,
        actions = WritingActions(
            onStage = viewModel::openStage,
            onCloseLessons = viewModel::closeLessons,
            onLesson = viewModel::openLesson,
            onDue = viewModel::startDueReview,
            onReinforce = viewModel::startReinforce,
            onOpenPaywall = viewModel::openPaywall,
            onClosePaywall = viewModel::closePaywall,
            onBuy = { activity?.let(viewModel::purchase) },
            onRestore = viewModel::restorePurchases,
            onTolerance = viewModel::setTolerance,
            onDebugUnlock = viewModel::setDebugUnlock,
            onOpenSettings = viewModel::openSettings,
            onCloseSettings = viewModel::closeSettings,
            onToggleLessons = viewModel::setWritingLessonsEnabled,
            onSpeak = viewModel::replayPronunciation,
            onBeginnerMode = viewModel::setBeginnerMode,
            onAutoPronounce = viewModel::setAutoPronounce,
            onDismissIntro = viewModel::dismissIntro,
            onStroke = viewModel::onStrokeFinished,
            onHint = viewModel::showHint,
            onGiveUp = viewModel::giveUp,
            onClosePractice = viewModel::closePractice,
            onDismissNotice = viewModel::dismissNotice,
            onStudyMode = viewModel::setStudyMode,
            onRecognizeAnswer = viewModel::answerRecognition,
            onRecognizeAdvance = viewModel::advanceRecognition,
        ),
    )
}

@Composable
internal fun WritingCourse(
    state: WritingUiState,
    uiLanguage: UiLanguage,
    actions: WritingActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WritingPalette.background),
    ) {
        when (state.screen) {
            WritingScreenKind.Path -> CoursePath(
                state = state,
                uiLanguage = uiLanguage,
                onStage = actions.onStage,
                onDue = actions.onDue,
                onReinforce = actions.onReinforce,
                onOpenPaywall = actions.onOpenPaywall,
                onOpenSettings = actions.onOpenSettings,
                onStudyMode = actions.onStudyMode,
            )
            WritingScreenKind.Lessons -> LessonBrowser(
                lessons = state.lessons,
                uiLanguage = uiLanguage,
                onClose = actions.onCloseLessons,
                onLesson = actions.onLesson,
                onOpenSettings = actions.onOpenSettings,
            )
            WritingScreenKind.Practice -> state.practice?.let { practice ->
                WritingPracticePage(
                    state = practice,
                    uiLanguage = uiLanguage,
                    onClose = actions.onClosePractice,
                    onSettings = actions.onOpenSettings,
                    onStroke = actions.onStroke,
                    onHint = actions.onHint,
                    onGiveUp = actions.onGiveUp,
                    onSpeak = actions.onSpeak,
                    onContinue = actions.onClosePractice,
                )
            }
            WritingScreenKind.Recognize -> state.recognize?.let { card ->
                RecognizePage(
                    card = card,
                    uiLanguage = uiLanguage,
                    onClose = actions.onClosePractice,
                    onAnswer = actions.onRecognizeAnswer,
                    onAdvance = actions.onRecognizeAdvance,
                    onSpeak = actions.onSpeak,
                )
            }
        }
        if (state.showPaywall) {
            PaywallPage(
                uiLanguage = uiLanguage,
                priceLabel = state.priceLabel,
                owned = state.premium,
                onBuy = actions.onBuy,
                onRestore = actions.onRestore,
                onClose = actions.onClosePaywall,
            )
        }
        if (state.showSettings) {
            SettingsPage(
                state = state,
                uiLanguage = uiLanguage,
                onClose = actions.onCloseSettings,
                onToggleLessons = actions.onToggleLessons,
                onBeginnerMode = actions.onBeginnerMode,
                onAutoPronounce = actions.onAutoPronounce,
                onTolerance = actions.onTolerance,
                onRestore = actions.onRestore,
                onDebugUnlock = actions.onDebugUnlock,
            )
        }
        if (state.showIntro) {
            WritingIntroDialog(
                strokes = state.introStrokes,
                title = localized(uiLanguage, R.string.writing_intro_title),
                eachCharacter = localized(uiLanguage, R.string.writing_intro_each),
                drawSentence = localized(uiLanguage, R.string.writing_intro_draw),
                greenWord = localized(uiLanguage, R.string.writing_green_word),
                redWord = localized(uiLanguage, R.string.writing_red_word),
                optional = localized(uiLanguage, R.string.writing_intro_optional),
                confirm = localized(uiLanguage, R.string.writing_ok),
                onConfirm = actions.onDismissIntro,
            )
        }
        state.notice?.let { notice ->
            WritingMessageDialog(
                title = localized(uiLanguage, R.string.writing_settings),
                body = notice.message(uiLanguage),
                confirm = localized(uiLanguage, R.string.writing_ok),
                onConfirm = actions.onDismissNotice,
            )
        }
    }
}

@Composable
private fun CoursePath(
    state: WritingUiState,
    uiLanguage: UiLanguage,
    onStage: (String) -> Unit,
    onDue: () -> Unit,
    onReinforce: () -> Unit,
    onOpenPaywall: () -> Unit,
    onOpenSettings: () -> Unit,
    onStudyMode: (StudyMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = localized(uiLanguage, R.string.writing_path_title),
                    color = WritingPalette.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = localized(
                        uiLanguage,
                        if (state.studyMode == StudyMode.Recognize) {
                            R.string.writing_recognize_subtitle
                        } else {
                            R.string.writing_path_subtitle
                        },
                    ),
                    color = WritingPalette.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = localized(uiLanguage, R.string.settings_cd),
                    tint = WritingPalette.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        StudyModeToggle(
            selected = state.studyMode,
            uiLanguage = uiLanguage,
            onSelect = onStudyMode,
        )
        Spacer(Modifier.height(16.dp))
        if (state.studyMode == StudyMode.Strokes) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionCard(
                    title = localized(uiLanguage, R.string.writing_due_review),
                    detail = state.dueCount.toString(),
                    container = WritingPalette.primaryContainer,
                    accent = WritingPalette.primary,
                    enabled = state.dueCount > 0,
                    onClick = onDue,
                    modifier = Modifier.weight(1f),
                )
                ActionCard(
                    title = localized(uiLanguage, R.string.writing_reinforce),
                    detail = "",
                    container = WritingPalette.secondaryContainer,
                    accent = WritingPalette.secondary,
                    enabled = state.stages.any { it.learnedCount > 0 && !it.locked },
                    onClick = onReinforce,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!state.premium) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WritingPalette.surfaceVariant)
                    .clickable(onClick = onOpenPaywall)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = WritingPalette.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localized(uiLanguage, R.string.writing_premium_cta),
                        color = WritingPalette.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = localized(uiLanguage, R.string.writing_paywall_includes),
                        color = WritingPalette.muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        CourseTrack.entries.forEach { track ->
            val stages = state.stages.filter { it.track == track }
            if (stages.isEmpty()) return@forEach
            Spacer(Modifier.height(22.dp))
            Text(
                text = track.label(uiLanguage),
                color = WritingPalette.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            stages.forEachIndexed { index, stage ->
                StagePathCard(
                    stage = stage,
                    uiLanguage = uiLanguage,
                    showRail = index < stages.lastIndex,
                    onClick = { onStage(stage.id) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionCard(
    title: String,
    detail: String,
    container: Color,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
    ) {
        Text(text = title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (detail.isNotBlank()) {
            Text(text = detail, color = WritingPalette.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun StudyModeToggle(
    selected: StudyMode,
    uiLanguage: UiLanguage,
    onSelect: (StudyMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WritingPalette.surfaceVariant)
            .padding(4.dp),
    ) {
        StudyMode.entries.forEach { mode ->
            val on = mode == selected
            val label = localized(
                uiLanguage,
                if (mode == StudyMode.Strokes) R.string.writing_mode_strokes else R.string.writing_mode_recognize,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) WritingPalette.primary else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (on) Color(0xFF0F1115) else WritingPalette.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun RecognizePage(
    card: RecognizeUi,
    uiLanguage: UiLanguage,
    onClose: () -> Unit,
    onAnswer: (Int) -> Unit,
    onAdvance: () -> Unit,
    onSpeak: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = localized(uiLanguage, R.string.close),
                    tint = WritingPalette.onSurface,
                )
            }
            Text(
                text = localized(uiLanguage, R.string.writing_mode_recognize),
                color = WritingPalette.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        if (card.complete) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = localized(uiLanguage, R.string.writing_recognize_score_title),
                    color = WritingPalette.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = localized(uiLanguage, R.string.writing_recognize_score_body, card.firstTryCorrect, card.total),
                    color = WritingPalette.secondary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(WritingPalette.primary)
                        .clickable(onClick = onClose)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = localized(uiLanguage, R.string.writing_continue),
                        color = Color(0xFF0F1115),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
            return
        }
        Text(
            text = localized(uiLanguage, R.string.writing_recognize_progress, card.index, card.total),
            color = WritingPalette.muted,
            fontSize = 13.sp,
        )
        LinearProgressIndicator(
            progress = { card.fraction },
            modifier = Modifier
                .padding(top = 8.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = WritingPalette.primary,
            trackColor = WritingPalette.surfaceVariant,
        )
        Text(
            text = localized(
                uiLanguage,
                if (card.kind == RecognizePrompt.GlyphToReading) {
                    R.string.writing_recognize_glyph_prompt
                } else {
                    R.string.writing_recognize_reading_prompt
                },
            ),
            color = WritingPalette.muted,
            fontSize = 14.sp,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = card.promptText,
                color = WritingPalette.onSurface,
                fontSize = if (card.kind == RecognizePrompt.GlyphToReading) 64.sp else 36.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (card.showSpeaker) {
                IconButton(onClick = onSpeak) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = localized(uiLanguage, R.string.writing_speak),
                        tint = WritingPalette.secondary,
                    )
                }
            }
        }
        if (card.hint.isNotBlank()) {
            Text(text = card.hint, color = WritingPalette.muted, fontSize = 16.sp)
        }
        if (card.retry && card.pickedIndex == null) {
            Text(
                text = localized(uiLanguage, R.string.writing_recognize_again),
                color = WritingPalette.primary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        card.options.forEachIndexed { index, option ->
            val revealed = card.pickedIndex != null
            val isAnswer = index == card.answerIndex
            val isPicked = index == card.pickedIndex
            val border = when {
                revealed && isAnswer -> WritingPalette.ok
                revealed && isPicked -> WritingPalette.error
                else -> WritingPalette.rail
            }
            val background = when {
                revealed && isAnswer -> WritingPalette.secondaryContainer
                revealed && isPicked -> Color(0xFF3A2224)
                else -> WritingPalette.surface
            }
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(background)
                    .border(1.dp, border, RoundedCornerShape(14.dp))
                    .clickable(enabled = !revealed) { onAnswer(index) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = option,
                    color = WritingPalette.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (revealed && isAnswer) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = WritingPalette.ok)
                }
            }
        }
        if (card.pickedIndex != null) {
            Text(
                text = if (card.pickedIndex == card.answerIndex) {
                    localized(uiLanguage, R.string.writing_recognize_correct)
                } else {
                    localized(uiLanguage, R.string.writing_recognize_wrong, card.options[card.answerIndex])
                },
                color = if (card.pickedIndex == card.answerIndex) WritingPalette.ok else WritingPalette.error,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WritingPalette.primary)
                    .clickable(onClick = onAdvance)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = localized(uiLanguage, R.string.writing_recognize_next),
                    color = Color(0xFF0F1115),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun StagePathCard(
    stage: StageCard,
    uiLanguage: UiLanguage,
    showRail: Boolean,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Box(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .size(12.dp)
                    .background(
                        if (stage.locked) WritingPalette.locked else WritingPalette.secondary,
                        CircleShape,
                    ),
            )
            if (showRail) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(72.dp)
                        .background(WritingPalette.rail),
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(bottom = 10.dp)
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(WritingPalette.surface)
                .border(1.dp, WritingPalette.rail, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stage.title,
                    color = WritingPalette.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (stage.locked) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = localized(uiLanguage, R.string.writing_premium_badge),
                        tint = WritingPalette.muted,
                        modifier = Modifier.size(18.dp),
                    )
                } else if (stage.learnedCount == stage.lessonCount && stage.lessonCount > 0) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = localized(uiLanguage, R.string.writing_learned),
                        tint = WritingPalette.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = stage.blurb,
                color = WritingPalette.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            val fraction = if (stage.lessonCount == 0) 0f else stage.learnedCount.toFloat() / stage.lessonCount
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = WritingPalette.primary,
                trackColor = WritingPalette.surfaceVariant,
            )
            Text(
                text = localized(uiLanguage, R.string.writing_stage_progress, stage.learnedCount, stage.lessonCount),
                color = WritingPalette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LessonBrowser(
    lessons: LessonListState?,
    uiLanguage: UiLanguage,
    onClose: () -> Unit,
    onLesson: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = localized(uiLanguage, R.string.close),
                    tint = WritingPalette.onSurface,
                )
            }
            Text(
                text = lessons?.title.orEmpty(),
                color = WritingPalette.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = localized(uiLanguage, R.string.settings_cd), tint = WritingPalette.primary)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lessons?.rows?.forEach { row ->
                LessonCard(row = row, uiLanguage = uiLanguage, onClick = { onLesson(row.id) })
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LessonCard(
    row: LessonRow,
    uiLanguage: UiLanguage,
    onClick: () -> Unit,
) {
    val locked = row.access == LessonAccess.Sequential || row.access == LessonAccess.Premium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WritingPalette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, color = if (locked) WritingPalette.muted else WritingPalette.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = row.preview, color = WritingPalette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            text = row.access.label(uiLanguage),
            color = row.access.color(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun PaywallPage(
    uiLanguage: UiLanguage,
    priceLabel: String?,
    owned: Boolean,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WritingPalette.background)
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = localized(uiLanguage, R.string.writing_paywall_title),
                color = WritingPalette.onSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localized(uiLanguage, R.string.close), tint = WritingPalette.onSurface)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = localized(uiLanguage, R.string.writing_paywall_body),
            color = WritingPalette.onSurface,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = localized(uiLanguage, R.string.writing_paywall_includes),
            color = WritingPalette.muted,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = priceLabel ?: localized(uiLanguage, R.string.writing_paywall_price_unknown),
            color = WritingPalette.secondary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (owned) WritingPalette.surfaceVariant else WritingPalette.primary)
                .clickable(enabled = !owned, onClick = onBuy)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = localized(uiLanguage, if (owned) R.string.writing_owned else R.string.writing_buy),
                color = if (owned) WritingPalette.muted else Color(0xFF0F1115),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, WritingPalette.primary, RoundedCornerShape(14.dp))
                .clickable(onClick = onRestore)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = localized(uiLanguage, R.string.writing_restore),
                color = WritingPalette.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
internal fun SettingsPage(
    state: WritingUiState,
    uiLanguage: UiLanguage,
    onClose: () -> Unit,
    onToggleLessons: (Boolean) -> Unit,
    onBeginnerMode: (Boolean) -> Unit,
    onAutoPronounce: (Boolean) -> Unit,
    onTolerance: (StrokeTolerance) -> Unit,
    onRestore: () -> Unit,
    onDebugUnlock: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WritingPalette.background)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localized(uiLanguage, R.string.close), tint = WritingPalette.onSurface)
            }
            Text(
                text = localized(uiLanguage, R.string.writing_settings),
                color = WritingPalette.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = localized(uiLanguage, R.string.writing_tolerance), color = WritingPalette.onSurface, fontWeight = FontWeight.Medium)
        Text(
            text = localized(uiLanguage, R.string.writing_tolerance_desc),
            color = WritingPalette.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrokeTolerance.entries.forEach { tolerance ->
                val selected = tolerance == state.tolerance
                Text(
                    text = tolerance.label(uiLanguage),
                    color = if (selected) Color(0xFF0F1115) else WritingPalette.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) WritingPalette.primary else WritingPalette.surfaceVariant)
                        .clickable { onTolerance(tolerance) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = localized(uiLanguage, R.string.writing_beginner), color = WritingPalette.onSurface, fontWeight = FontWeight.Medium)
                Text(
                    text = localized(uiLanguage, R.string.writing_beginner_desc),
                    color = WritingPalette.muted,
                    fontSize = 13.sp,
                )
            }
            androidx.compose.material3.Switch(
                checked = state.beginnerMode,
                onCheckedChange = onBeginnerMode,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = localized(uiLanguage, R.string.writing_auto_pronounce), color = WritingPalette.onSurface, fontWeight = FontWeight.Medium)
                Text(
                    text = localized(uiLanguage, R.string.writing_auto_pronounce_desc),
                    color = WritingPalette.muted,
                    fontSize = 13.sp,
                )
            }
            androidx.compose.material3.Switch(
                checked = state.autoPronounce,
                onCheckedChange = onAutoPronounce,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = localized(uiLanguage, R.string.writing_lessons_toggle), color = WritingPalette.onSurface, fontWeight = FontWeight.Medium)
                Text(
                    text = localized(uiLanguage, R.string.writing_lessons_toggle_desc),
                    color = WritingPalette.muted,
                    fontSize = 13.sp,
                )
            }
            androidx.compose.material3.Switch(
                checked = state.writingLessonsEnabled,
                onCheckedChange = onToggleLessons,
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, WritingPalette.rail, RoundedCornerShape(12.dp))
                .clickable(onClick = onRestore)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(text = localized(uiLanguage, R.string.writing_restore), color = WritingPalette.primary)
        }
        DebugUnlockSetting(
            state = state,
            uiLanguage = uiLanguage,
            onDebugUnlock = onDebugUnlock,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = localized(uiLanguage, R.string.writing_attribution),
            color = WritingPalette.muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun CourseTrack.label(uiLanguage: UiLanguage): String = when (this) {
    CourseTrack.Kana -> localized(uiLanguage, R.string.writing_track_kana)
    CourseTrack.Words -> localized(uiLanguage, R.string.writing_track_words)
    CourseTrack.Kanji -> localized(uiLanguage, R.string.writing_track_kanji)
    CourseTrack.Phrases -> localized(uiLanguage, R.string.writing_track_phrases)
}

@Composable
private fun LessonAccess.label(uiLanguage: UiLanguage): String = when (this) {
    LessonAccess.Learned -> localized(uiLanguage, R.string.writing_learned)
    LessonAccess.Open -> localized(uiLanguage, R.string.writing_available)
    LessonAccess.Sequential -> localized(uiLanguage, R.string.writing_locked)
    LessonAccess.Premium -> localized(uiLanguage, R.string.writing_premium_badge)
}

private fun LessonAccess.color(): Color = when (this) {
    LessonAccess.Learned -> WritingPalette.secondary
    LessonAccess.Open -> WritingPalette.primary
    LessonAccess.Sequential -> WritingPalette.locked
    LessonAccess.Premium -> WritingPalette.primary
}

@Composable
private fun StrokeTolerance.label(uiLanguage: UiLanguage): String = when (this) {
    StrokeTolerance.Relaxed -> localized(uiLanguage, R.string.writing_tolerance_relaxed)
    StrokeTolerance.Normal -> localized(uiLanguage, R.string.writing_tolerance_normal)
    StrokeTolerance.Strict -> localized(uiLanguage, R.string.writing_tolerance_strict)
}

@Composable
private fun WritingNotice.message(uiLanguage: UiLanguage): String = when (this) {
    WritingNotice.LessonLocked -> localized(uiLanguage, R.string.writing_locked_toast)
    WritingNotice.MissingGlyph -> localized(uiLanguage, R.string.writing_missing_glyph)
    WritingNotice.PremiumRequired -> localized(uiLanguage, R.string.writing_locked_body)
    WritingNotice.BillingUnavailable -> localized(uiLanguage, R.string.writing_billing_unavailable)
    WritingNotice.BillingPending -> localized(uiLanguage, R.string.writing_billing_pending)
    WritingNotice.BillingError -> localized(uiLanguage, R.string.writing_billing_error)
    WritingNotice.Restored -> localized(uiLanguage, R.string.writing_restored)
    WritingNotice.AlreadyOwned -> localized(uiLanguage, R.string.writing_owned)
    WritingNotice.NothingToRestore -> localized(uiLanguage, R.string.writing_nothing_to_restore)
}
