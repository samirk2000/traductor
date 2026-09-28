package com.arnold.voicetranslator.writing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arnold.voicetranslator.util.KanaRomaji
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WritingScreenKind { Levels, Lessons, Practice }

enum class LessonViewMode { List, Grid }

enum class WritingNotice { LessonLocked, MissingGlyph }

data class LevelCard(
    val id: String,
    val number: Int,
    val script: WritingScript,
    val symbol: String,
    val learnedCount: Int,
    val lessonCount: Int,
)

data class LessonRow(
    val id: String,
    val number: Int,
    val symbol: String,
    val preview: String,
    val status: LessonStatus,
)

data class LessonListState(
    val levelId: String,
    val script: WritingScript,
    val levelNumber: Int,
    val rows: List<LessonRow>,
)

data class PracticeState(
    val mode: PracticeMode,
    val character: String,
    val romaji: String,
    val strokes: List<List<Vec>>,
    val strokeIndex: Int,
    val totalCharacters: Int,
    val characterIndex: Int,
    val showGuide: Boolean,
    val showHint: Boolean,
    val error: StrokeVerdict?,
    val justCleared: Boolean,
    val sessionComplete: Boolean,
    /** True when finishing this session marked a lesson learned and another lesson follows. */
    val unlockedNext: Boolean,
)

data class WritingUiState(
    val screen: WritingScreenKind = WritingScreenKind.Levels,
    val viewMode: LessonViewMode = LessonViewMode.List,
    val writingLessonsEnabled: Boolean = true,
    val showSettings: Boolean = false,
    val showIntro: Boolean = false,
    val notice: WritingNotice? = null,
    val levels: List<LevelCard> = emptyList(),
    val lessons: LessonListState? = null,
    val practice: PracticeState? = null,
    /** Stroke polylines for あ, drawn in the writing-lesson intro. */
    val introStrokes: List<List<Vec>> = emptyList(),
)

/**
 * Owns the writing course: which screen is open, stroke checking, and the
 * locally persisted learned / mistake record. Independent of the translator.
 */
class WritingViewModel(application: Application) : AndroidViewModel(application) {

    private val glyphs: Map<String, List<List<Vec>>> = StrokeRepository.load(application)
    private val store = WritingProgressStore(application)
    private var progress: PersistedProgress = store.read()

    private var screen: WritingScreenKind = WritingScreenKind.Levels
    private var openLevelId: String? = null
    private var showSettings: Boolean = false
    private var showIntro: Boolean = false
    private var notice: WritingNotice? = null
    private var session: Session? = null
    private var transientJob: Job? = null

    private val _ui = MutableStateFlow(buildState())
    val uiState: StateFlow<WritingUiState> = _ui.asStateFlow()

    fun openLevel(levelId: String) {
        if (KanaCatalog.levels.none { it.id == levelId }) return
        openLevelId = levelId
        screen = WritingScreenKind.Lessons
        emit()
    }

    fun closeLessons() {
        screen = WritingScreenKind.Levels
        emit()
    }

    fun openLesson(lessonId: String) {
        val level = currentLevel() ?: return
        if (!WritingProgress.isUnlocked(level, lessonId, learned())) {
            notice = WritingNotice.LessonLocked
            emit()
            return
        }
        val lesson = level.lessons.firstOrNull { it.id == lessonId } ?: return
        beginSession(
            levelId = level.id,
            lessonId = lesson.id,
            mode = PracticeMode.Lesson,
            characters = lesson.characters,
        )
    }

    fun startSmartReview() {
        val level = currentLevel() ?: return
        val characters = WritingProgress.smartReview(level, learned(), progress.stats)
        if (characters.isEmpty()) return
        beginSession(level.id, lessonId = null, PracticeMode.SmartReview, characters)
    }

    fun startReviewAll() {
        val level = currentLevel() ?: return
        val characters = WritingProgress.reviewAll(level, learned())
        if (characters.isEmpty()) return
        beginSession(level.id, lessonId = null, PracticeMode.ReviewAll, characters)
    }

    fun setViewMode(mode: LessonViewMode) {
        progress = progress.copy(listMode = mode == LessonViewMode.List)
        persist()
        emit()
    }

    fun openSettings() {
        showSettings = true
        emit()
    }

    fun closeSettings() {
        showSettings = false
        emit()
    }

    fun setWritingLessonsEnabled(enabled: Boolean) {
        progress = progress.copy(writingLessonsEnabled = enabled)
        if (!enabled) showIntro = false
        persist()
        emit()
    }

    fun dismissIntro() {
        showIntro = false
        progress = progress.copy(introSeen = true)
        persist()
        emit()
    }

    fun dismissNotice() {
        notice = null
        emit()
    }

    fun closePractice() {
        transientJob?.cancel()
        session = null
        showIntro = false
        screen = if (openLevelId != null) WritingScreenKind.Lessons else WritingScreenKind.Levels
        emit()
    }

    fun showHint() {
        val current = session ?: return
        if (current.complete || current.cleared) return
        session = current.copy(hint = true)
        emit()
    }

    fun onStrokeFinished(points: List<Vec>) {
        val current = session ?: return
        if (current.complete || current.cleared) return
        val glyph = glyphs[current.characters[current.index]]
        if (glyph == null || current.strokeIndex !in glyph.indices) {
            notice = WritingNotice.MissingGlyph
            emit()
            return
        }
        when (val verdict = StrokeMatcher.evaluate(points, glyph[current.strokeIndex])) {
            StrokeVerdict.Accepted -> acceptStroke(current, glyph)
            StrokeVerdict.TooShort -> rejectStroke(current, verdict, countMistake = false)
            else -> rejectStroke(current, verdict, countMistake = true)
        }
    }

    private fun acceptStroke(current: Session, glyph: List<List<Vec>>) {
        transientJob?.cancel()
        if (current.strokeIndex < glyph.lastIndex) {
            session = current.copy(
                strokeIndex = current.strokeIndex + 1,
                hint = false,
                error = null,
            )
            emit()
            return
        }
        val character = current.characters[current.index]
        progress = WritingProgress.withSuccess(progress, character, System.currentTimeMillis())
        val lastCharacter = current.index == current.characters.lastIndex
        if (lastCharacter && current.mode == PracticeMode.Lesson && current.lessonId != null) {
            progress = WritingProgress.withLessonLearned(progress, current.lessonId)
        }
        persist()
        if (!lastCharacter) {
            session = current.copy(cleared = true, hint = false, error = null)
            emit()
            transientJob = viewModelScope.launch {
                delay(CLEAR_DELAY_MS)
                val latest = session ?: return@launch
                if (!latest.cleared) return@launch
                session = latest.copy(
                    index = latest.index + 1,
                    strokeIndex = 0,
                    cleared = false,
                    hint = false,
                    error = null,
                )
                emit()
            }
            return
        }
        val level = KanaCatalog.levels.firstOrNull { it.id == current.levelId }
        val unlockedNext = if (current.mode == PracticeMode.Lesson && current.lessonId != null && level != null) {
            val index = level.lessons.indexOfFirst { it.id == current.lessonId }
            index >= 0 && index < level.lessons.lastIndex
        } else {
            false
        }
        session = current.copy(complete = true, hint = false, error = null, unlockedNext = unlockedNext)
        emit()
    }

    private fun rejectStroke(current: Session, verdict: StrokeVerdict, countMistake: Boolean) {
        if (countMistake) {
            val character = current.characters[current.index]
            progress = WritingProgress.withMistake(progress, character, System.currentTimeMillis())
            persist()
        }
        session = current.copy(error = verdict)
        emit()
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(ERROR_DELAY_MS)
            val latest = session ?: return@launch
            if (latest.error != verdict) return@launch
            session = latest.copy(error = null)
            emit()
        }
    }

    private fun beginSession(
        levelId: String,
        lessonId: String?,
        mode: PracticeMode,
        characters: List<String>,
    ) {
        transientJob?.cancel()
        val playable = characters.filter { glyphs[it]?.isNotEmpty() == true }
        if (playable.isEmpty()) {
            notice = WritingNotice.MissingGlyph
            emit()
            return
        }
        session = Session(
            levelId = levelId,
            lessonId = lessonId,
            mode = mode,
            characters = playable,
        )
        screen = WritingScreenKind.Practice
        showIntro = mode == PracticeMode.Lesson &&
            progress.writingLessonsEnabled &&
            !progress.introSeen
        emit()
    }

    private fun buildState(): WritingUiState {
        val learned = learned()
        val level = currentLevel()
        val active = session
        return WritingUiState(
            screen = screen,
            viewMode = if (progress.listMode) LessonViewMode.List else LessonViewMode.Grid,
            writingLessonsEnabled = progress.writingLessonsEnabled,
            showSettings = showSettings,
            showIntro = showIntro,
            notice = notice,
            levels = KanaCatalog.levels.map { def ->
                LevelCard(
                    id = def.id,
                    number = def.number,
                    script = def.script,
                    symbol = def.symbol,
                    learnedCount = def.lessons.count { it.id in learned },
                    lessonCount = def.lessons.size,
                )
            },
            lessons = level?.let { def ->
                LessonListState(
                    levelId = def.id,
                    script = def.script,
                    levelNumber = def.number,
                    rows = def.lessons.mapIndexed { index, lesson ->
                        LessonRow(
                            id = lesson.id,
                            number = lesson.number,
                            symbol = lesson.characters.first(),
                            preview = KanaCatalog.preview(lesson, def.script),
                            status = WritingProgress.status(def, index, learned),
                        )
                    },
                )
            },
            practice = active?.let { sessionToState(it) },
            introStrokes = glyphs["あ"].orEmpty(),
        )
    }

    private fun sessionToState(current: Session): PracticeState? {
        val character = current.characters.getOrNull(current.index) ?: return null
        val glyph = glyphs[character].orEmpty()
        return PracticeState(
            mode = current.mode,
            character = character,
            romaji = KanaRomaji.toRomaji(character),
            strokes = glyph,
            strokeIndex = current.strokeIndex.coerceIn(0, (glyph.size - 1).coerceAtLeast(0)),
            totalCharacters = current.characters.size,
            characterIndex = current.index,
            showGuide = progress.writingLessonsEnabled,
            showHint = current.hint && progress.writingLessonsEnabled,
            error = current.error,
            justCleared = current.cleared,
            sessionComplete = current.complete,
            unlockedNext = current.unlockedNext,
        )
    }

    private fun currentLevel(): WritingLevelDef? = openLevelId?.let { id ->
        KanaCatalog.levels.firstOrNull { it.id == id }
    }

    private fun learned(): Set<String> = progress.learnedLessonIds.toSet()

    private fun persist() {
        store.write(progress)
    }

    private fun emit() {
        _ui.value = buildState()
    }

    private data class Session(
        val levelId: String,
        val lessonId: String?,
        val mode: PracticeMode,
        val characters: List<String>,
        val index: Int = 0,
        val strokeIndex: Int = 0,
        val hint: Boolean = false,
        val error: StrokeVerdict? = null,
        val cleared: Boolean = false,
        val complete: Boolean = false,
        val unlockedNext: Boolean = false,
    )

    private companion object {
        const val CLEAR_DELAY_MS = 650L
        const val ERROR_DELAY_MS = 900L
    }
}
