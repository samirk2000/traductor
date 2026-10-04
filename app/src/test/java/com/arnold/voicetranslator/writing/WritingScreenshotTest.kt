package com.arnold.voicetranslator.writing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.writing.ui.PaywallPage
import com.arnold.voicetranslator.writing.ui.SettingsPage
import com.arnold.voicetranslator.writing.ui.WritingActions
import com.arnold.voicetranslator.writing.ui.WritingCourse
import com.arnold.voicetranslator.writing.ui.WritingPalette
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the course path, practice, paywall and settings. */
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
    fun courseMap() {
        snap("course-map") {
            WritingCourse(
                state = WritingUiState(
                    screen = WritingScreenKind.Path,
                    dueCount = 4,
                    stages = listOf(
                        stage("hira-basic", "Hiragana básico", "Las 46 sílabas, sin dakuten.", CourseTrack.Kana, premium = false, learned = 3, total = 10),
                        stage("kata-basic", "Katakana básico", "Las mismas sílabas en katakana.", CourseTrack.Kana, premium = false, learned = 0, total = 10),
                        stage("hira-dakuten", "Hiragana con marca", "Dakuten y handakuten.", CourseTrack.Kana, premium = true, learned = 0, total = 5, locked = true),
                        stage("vocab", "Vocabulario", "Escribe la palabra a partir del significado.", CourseTrack.Words, premium = true, learned = 0, total = 5, locked = true),
                        stage("kanji-n5", "Kanji N5", "Unos 80 kanji de inicio.", CourseTrack.Kanji, premium = true, learned = 0, total = 16, locked = true),
                        stage("phrases", "Frases", "Escribe saludos y frases cortas.", CourseTrack.Phrases, premium = true, learned = 0, total = 3, locked = true),
                    ),
                ),
                uiLanguage = UiLanguage.ES,
                actions = noopActions(),
            )
        }
    }

    @Test
    fun teach() {
        snap("teach") {
            practiceSnap(
                headline = "あ",
                subtitle = "a",
                character = "あ",
                phase = PracticePhase.Teach,
                guide = GuideStyle.Full,
            )
        }
    }

    @Test
    fun fading() {
        snap("fading") {
            practiceSnap(
                headline = "あ",
                subtitle = "a",
                character = "あ",
                phase = PracticePhase.Fade,
                guide = GuideStyle.Faint,
            )
        }
    }

    @Test
    fun recall() {
        snap("recall") {
            practiceSnap(
                headline = "a",
                subtitle = "",
                character = "あ",
                phase = PracticePhase.Recall,
                guide = GuideStyle.None,
            )
        }
    }

    @Test
    fun compare() {
        val model = glyphs.getValue("あ")
        val ink = model.map { stroke -> stroke.map { Vec(it.x + 6f, it.y + 4f) } }
        snap("compare") {
            practiceSnap(
                headline = "a",
                subtitle = "",
                character = "あ",
                phase = PracticePhase.Recall,
                guide = GuideStyle.None,
                userInk = ink,
                reference = true,
            )
        }
    }

    @Test
    fun mistake() {
        snap("mistake") {
            practiceSnap(
                headline = "a",
                subtitle = "",
                character = "あ",
                phase = PracticePhase.Recall,
                guide = GuideStyle.None,
                assist = RecallAssist.Stroke,
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun practiceSnap(
        headline: String,
        subtitle: String,
        character: String,
        phase: PracticePhase,
        guide: GuideStyle,
        assist: RecallAssist = RecallAssist.None,
        userInk: List<List<Vec>> = emptyList(),
        reference: Boolean = false,
    ) {
        WritingCourse(
            state = WritingUiState(
                screen = WritingScreenKind.Practice,
                writingLessonsEnabled = true,
                practice = PracticeState(
                    mode = PracticeMode.Lesson,
                    headline = headline,
                    subtitle = subtitle,
                    character = character,
                    strokes = glyphs.getValue(character),
                    strokeIndex = 0,
                    glyphIndex = 0,
                    glyphCount = 1,
                    itemIndex = 0,
                    itemCount = 5,
                    phase = phase,
                    guideStyle = guide,
                    showHint = false,
                    error = null,
                    justCleared = false,
                    sessionComplete = false,
                    unlockedNext = false,
                    recallAssist = assist,
                    showAnswer = false,
                    userStrokes = userInk,
                    showReference = reference,
                ),
            ),
            uiLanguage = UiLanguage.ES,
            actions = noopActions(),
        )
    }

    @Test
    fun paywall() {
        snap("paywall") {
            Box(Modifier.fillMaxSize().background(WritingPalette.background)) {
                PaywallPage(
                    uiLanguage = UiLanguage.ES,
                    priceLabel = "4,99 €",
                    owned = false,
                    onBuy = {},
                    onRestore = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun settings() {
        snap("settings") {
            Box(Modifier.fillMaxSize().background(WritingPalette.background).padding(0.dp)) {
                SettingsPage(
                    state = WritingUiState(
                        tolerance = StrokeTolerance.Relaxed,
                        writingLessonsEnabled = true,
                        debugBuild = true,
                        debugUnlock = false,
                    ),
                    uiLanguage = UiLanguage.ES,
                    onClose = {},
                    onToggleLessons = {},
                    onBeginnerMode = {},
                    onTolerance = {},
                    onRestore = {},
                    onDebugUnlock = {},
                )
            }
        }
    }

    private fun stage(
        id: String,
        title: String,
        blurb: String,
        track: CourseTrack,
        premium: Boolean,
        learned: Int,
        total: Int,
        locked: Boolean = false,
    ) = StageCard(id, title, blurb, track, premium, learned, total, locked)

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot(name = name) {
            MaterialTheme { content() }
        }
    }

    private fun noopActions() = WritingActions(
        onStage = {},
        onCloseLessons = {},
        onLesson = {},
        onDue = {},
        onReinforce = {},
        onOpenPaywall = {},
        onClosePaywall = {},
        onBuy = {},
        onRestore = {},
        onTolerance = {},
        onDebugUnlock = {},
        onOpenSettings = {},
        onCloseSettings = {},
        onToggleLessons = {},
        onBeginnerMode = {},
        onDismissIntro = {},
        onStroke = {},
        onHint = {},
        onGiveUp = {},
        onClosePractice = {},
        onDismissNotice = {},
    )
}
