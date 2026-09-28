package com.arnold.voicetranslator.writing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import com.arnold.voicetranslator.writing.LessonRow
import com.arnold.voicetranslator.writing.LessonStatus
import com.arnold.voicetranslator.writing.LessonViewMode
import com.arnold.voicetranslator.writing.LevelCard
import com.arnold.voicetranslator.writing.WritingNotice
import com.arnold.voicetranslator.writing.WritingScreenKind
import com.arnold.voicetranslator.writing.WritingScript
import com.arnold.voicetranslator.writing.WritingUiState
import com.arnold.voicetranslator.writing.WritingViewModel
import kotlinx.coroutines.delay

@Composable
fun WritingScreen(
    viewModel: WritingViewModel,
    uiLanguage: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WritingCourse(
        state = state,
        uiLanguage = uiLanguage,
        modifier = modifier,
        actions = WritingActions(
            onLevel = viewModel::openLevel,
            onCloseLessons = viewModel::closeLessons,
            onLesson = viewModel::openLesson,
            onSmartReview = viewModel::startSmartReview,
            onReviewAll = viewModel::startReviewAll,
            onViewMode = viewModel::setViewMode,
            onOpenSettings = viewModel::openSettings,
            onCloseSettings = viewModel::closeSettings,
            onToggleLessons = viewModel::setWritingLessonsEnabled,
            onDismissIntro = viewModel::dismissIntro,
            onStroke = viewModel::onStrokeFinished,
            onHint = viewModel::showHint,
            onClosePractice = viewModel::closePractice,
            onDismissNotice = viewModel::dismissNotice,
        ),
    )
}

/**
 * The course UI. Kept separate from [WritingScreen] so it can be drawn from a
 * plain state snapshot (screenshots, previews) without a ViewModel.
 */
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
            WritingScreenKind.Levels -> LevelPicker(
                levels = state.levels,
                uiLanguage = uiLanguage,
                onLevel = actions.onLevel,
            )
            WritingScreenKind.Lessons -> state.lessons?.let { lessons ->
                LessonBrowser(
                    rows = lessons.rows,
                    levelNumber = lessons.levelNumber,
                    script = lessons.script,
                    viewMode = state.viewMode,
                    uiLanguage = uiLanguage,
                    onClose = actions.onCloseLessons,
                    onLesson = actions.onLesson,
                    onSmartReview = actions.onSmartReview,
                    onReviewAll = actions.onReviewAll,
                    onViewMode = actions.onViewMode,
                    onSettings = actions.onOpenSettings,
                )
            }
            WritingScreenKind.Practice -> state.practice?.let { practice ->
                WritingPracticePage(
                    state = practice,
                    uiLanguage = uiLanguage,
                    onClose = actions.onClosePractice,
                    onSettings = actions.onOpenSettings,
                    onStroke = actions.onStroke,
                    onHint = actions.onHint,
                    onContinue = actions.onClosePractice,
                )
            }
        }
        state.notice?.let { notice ->
            LaunchedEffect(notice) {
                delay(2400)
                actions.onDismissNotice()
            }
            Text(
                text = when (notice) {
                    WritingNotice.LessonLocked -> localized(uiLanguage, R.string.writing_locked_toast)
                    WritingNotice.MissingGlyph -> localized(uiLanguage, R.string.writing_missing_glyph)
                },
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                    .background(Color(0xFF1B2230), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
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
    if (state.showSettings) {
        WritingSettingsDialog(
            lessonsEnabled = state.writingLessonsEnabled,
            title = localized(uiLanguage, R.string.writing_settings),
            toggleTitle = localized(uiLanguage, R.string.writing_lessons_toggle),
            toggleDescription = localized(uiLanguage, R.string.writing_lessons_toggle_desc),
            attribution = localized(uiLanguage, R.string.writing_attribution),
            closeLabel = localized(uiLanguage, R.string.close),
            onToggle = actions.onToggleLessons,
            onClose = actions.onCloseSettings,
        )
    }
}

internal class WritingActions(
    val onLevel: (String) -> Unit,
    val onCloseLessons: () -> Unit,
    val onLesson: (String) -> Unit,
    val onSmartReview: () -> Unit,
    val onReviewAll: () -> Unit,
    val onViewMode: (LessonViewMode) -> Unit,
    val onOpenSettings: () -> Unit,
    val onCloseSettings: () -> Unit,
    val onToggleLessons: (Boolean) -> Unit,
    val onDismissIntro: () -> Unit,
    val onStroke: (List<com.arnold.voicetranslator.writing.Vec>) -> Unit,
    val onHint: () -> Unit,
    val onClosePractice: () -> Unit,
    val onDismissNotice: () -> Unit,
)

@Composable
private fun LevelPicker(
    levels: List<LevelCard>,
    uiLanguage: UiLanguage,
    onLevel: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        levels.forEach { level ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .widthIn(max = 280.dp)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(scriptColor(level.script))
                        .clickable { onLevel(level.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = level.symbol,
                        color = Color.White,
                        fontSize = if (level.symbol.length > 1) 64.sp else 108.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = localized(uiLanguage, R.string.writing_level, level.number),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = level.script.title(uiLanguage),
                    color = WritingPalette.muted,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun LessonBrowser(
    rows: List<LessonRow>,
    levelNumber: Int,
    script: WritingScript,
    viewMode: LessonViewMode,
    uiLanguage: UiLanguage,
    onClose: () -> Unit,
    onLesson: (String) -> Unit,
    onSmartReview: () -> Unit,
    onReviewAll: () -> Unit,
    onViewMode: (LessonViewMode) -> Unit,
    onSettings: () -> Unit,
) {
    val accent = scriptColor(script)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LessonTopBar(
                viewMode = viewMode,
                uiLanguage = uiLanguage,
                levelLabel = localized(uiLanguage, R.string.writing_level, levelNumber),
                onClose = onClose,
                onViewMode = onViewMode,
                onSettings = onSettings,
            )
            if (viewMode == LessonViewMode.List) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 168.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.id }) { row ->
                        LessonListRow(row = row, accent = accent, uiLanguage = uiLanguage, onLesson = onLesson)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(148.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 168.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.id }) { row ->
                        LessonGridCell(row = row, accent = accent, uiLanguage = uiLanguage, onLesson = onLesson)
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(WritingPalette.background)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CourseButton(
                label = localized(uiLanguage, R.string.writing_smart_review),
                color = WritingPalette.katakana,
                onClick = onSmartReview,
            )
            CourseButton(
                label = localized(uiLanguage, R.string.writing_review_all),
                color = WritingPalette.reviewAll,
                onClick = onReviewAll,
            )
        }
    }
}

@Composable
private fun LessonTopBar(
    viewMode: LessonViewMode,
    uiLanguage: UiLanguage,
    levelLabel: String,
    onClose: () -> Unit,
    onViewMode: (LessonViewMode) -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = localized(uiLanguage, R.string.close), tint = Color.White)
        }
        Text(
            text = levelLabel,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        ViewToggle(
            selected = viewMode == LessonViewMode.List,
            description = localized(uiLanguage, R.string.writing_list_view),
            onClick = { onViewMode(LessonViewMode.List) },
        ) {
            Icon(Icons.Filled.Menu, contentDescription = null, tint = Color.White)
        }
        ViewToggle(
            selected = viewMode == LessonViewMode.Grid,
            description = localized(uiLanguage, R.string.writing_grid_view),
            onClick = { onViewMode(LessonViewMode.Grid) },
        ) {
            Icon(Icons.Filled.GridView, contentDescription = null, tint = Color.White)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = localized(uiLanguage, R.string.settings_cd), tint = Color.White)
        }
    }
}

@Composable
private fun ViewToggle(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
private fun LessonListRow(
    row: LessonRow,
    accent: Color,
    uiLanguage: UiLanguage,
    onLesson: (String) -> Unit,
) {
    val locked = row.status == LessonStatus.Locked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onLesson(row.id) }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KanaBubble(symbol = row.symbol, color = if (locked) WritingPalette.locked else accent, size = 64.dp)
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = localized(uiLanguage, R.string.writing_lesson, row.number),
                color = if (locked) WritingPalette.locked else Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = row.preview, color = WritingPalette.muted, fontSize = 15.sp)
            Text(
                text = row.status.label(uiLanguage),
                color = row.status.color(),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun LessonGridCell(
    row: LessonRow,
    accent: Color,
    uiLanguage: UiLanguage,
    onLesson: (String) -> Unit,
) {
    val locked = row.status == LessonStatus.Locked
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onLesson(row.id) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KanaBubble(symbol = row.symbol, color = if (locked) WritingPalette.locked else accent, size = 84.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = localized(uiLanguage, R.string.writing_lesson, row.number),
            color = if (locked) WritingPalette.locked else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(text = row.status.label(uiLanguage), color = row.status.color(), fontSize = 12.sp)
    }
}

@Composable
private fun KanaBubble(symbol: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = symbol, color = Color.White, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CourseButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text = label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WritingScript.title(uiLanguage: UiLanguage): String = when (this) {
    WritingScript.Hiragana -> localized(uiLanguage, R.string.writing_hiragana)
    WritingScript.Katakana -> localized(uiLanguage, R.string.writing_katakana)
    WritingScript.Mixed -> localized(uiLanguage, R.string.writing_mixed)
}

@Composable
private fun LessonStatus.label(uiLanguage: UiLanguage): String = when (this) {
    LessonStatus.Learned -> localized(uiLanguage, R.string.writing_learned)
    LessonStatus.Locked -> localized(uiLanguage, R.string.writing_locked)
    LessonStatus.Available -> localized(uiLanguage, R.string.writing_available)
}

private fun LessonStatus.color(): Color = when (this) {
    LessonStatus.Learned -> Color.White.copy(alpha = 0.85f)
    LessonStatus.Available -> Color(0xFF8FD0F5)
    LessonStatus.Locked -> WritingPalette.locked
}
