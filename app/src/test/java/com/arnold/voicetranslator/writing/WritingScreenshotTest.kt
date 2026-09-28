package com.arnold.voicetranslator.writing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.writing.ui.WritingActions
import com.arnold.voicetranslator.writing.ui.WritingCourse
import com.arnold.voicetranslator.writing.ui.WritingIntroCard
import com.arnold.voicetranslator.writing.ui.WritingPalette
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the four course screens. Run with `recordPaparazziDebug` to refresh
 * the images under src/test/snapshots.
 */
class WritingScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "es"),
        showSystemUi = false,
    )

    private val glyphs: Map<String, List<List<Vec>>> by lazy {
        StrokeRepository.parse(
            listOf(
                File("src/main/assets/writing/strokes.json"),
                File("app/src/main/assets/writing/strokes.json"),
            ).first { it.exists() }.readText(),
        )
    }

    @Test
    fun levelPicker() {
        snap("level-picker") {
            WritingCourse(
                state = WritingUiState(
                    screen = WritingScreenKind.Levels,
                    levels = KanaCatalog.levels.map { level ->
                        LevelCard(
                            id = level.id,
                            number = level.number,
                            script = level.script,
                            symbol = level.symbol,
                            learnedCount = 0,
                            lessonCount = level.lessons.size,
                        )
                    },
                ),
                uiLanguage = UiLanguage.ES,
                actions = noopActions(),
            )
        }
    }

    @Test
    fun lessonList() {
        val level = KanaCatalog.level("hiragana")
        val rows = level.lessons.take(8).mapIndexed { index, lesson ->
            LessonRow(
                id = lesson.id,
                number = lesson.number,
                symbol = lesson.characters.first(),
                preview = KanaCatalog.preview(lesson, level.script),
                status = when (index) {
                    0, 1 -> LessonStatus.Learned
                    2 -> LessonStatus.Available
                    else -> LessonStatus.Locked
                },
            )
        }
        snap("lesson-list") {
            WritingCourse(
                state = WritingUiState(
                    screen = WritingScreenKind.Lessons,
                    viewMode = LessonViewMode.List,
                    lessons = LessonListState(
                        levelId = level.id,
                        script = level.script,
                        levelNumber = level.number,
                        rows = rows,
                    ),
                ),
                uiLanguage = UiLanguage.ES,
                actions = noopActions(),
            )
        }
    }

    @Test
    fun practice() {
        snap("practice") {
            WritingCourse(
                state = WritingUiState(
                    screen = WritingScreenKind.Practice,
                    writingLessonsEnabled = true,
                    practice = PracticeState(
                        mode = PracticeMode.Lesson,
                        character = "あ",
                        romaji = "a",
                        strokes = glyphs.getValue("あ"),
                        strokeIndex = 0,
                        totalCharacters = 5,
                        characterIndex = 0,
                        showGuide = true,
                        showHint = false,
                        error = null,
                        justCleared = false,
                        sessionComplete = false,
                        unlockedNext = false,
                    ),
                ),
                uiLanguage = UiLanguage.ES,
                actions = noopActions(),
            )
        }
    }

    @Test
    fun writingIntro() {
        snap("writing-intro") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WritingPalette.background)
                    .padding(12.dp),
            ) {
                WritingIntroCard(
                    strokes = glyphs.getValue("あ"),
                    title = "Lecciones de escritura",
                    eachCharacter = "Cada carácter nuevo tiene una lección de escritura.",
                    drawSentence = "Traza cada trazo desde el punto verde hasta el punto rojo.",
                    greenWord = "verde",
                    redWord = "rojo",
                    optional = "Las lecciones de escritura son opcionales y se pueden desactivar en el menú de ajustes.",
                    confirm = "Aceptar",
                    onConfirm = {},
                )
            }
        }
    }

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot(name = name) {
            MaterialTheme { content() }
        }
    }

    private fun noopActions() = WritingActions(
        onLevel = {},
        onCloseLessons = {},
        onLesson = {},
        onSmartReview = {},
        onReviewAll = {},
        onViewMode = {},
        onOpenSettings = {},
        onCloseSettings = {},
        onToggleLessons = {},
        onDismissIntro = {},
        onStroke = {},
        onHint = {},
        onClosePractice = {},
        onDismissNotice = {},
    )
}
