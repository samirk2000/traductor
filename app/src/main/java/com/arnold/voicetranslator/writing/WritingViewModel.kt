package com.arnold.voicetranslator.writing

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arnold.voicetranslator.BuildConfig
import com.arnold.voicetranslator.ui.localization.UiLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WritingScreenKind { Path, Lessons, Practice }

enum class WritingNotice {
    LessonLocked,
    MissingGlyph,
    PremiumRequired,
    BillingUnavailable,
    BillingPending,
    BillingError,
    Restored,
    AlreadyOwned,
    NothingToRestore,
}

data class StageCard(
    val id: String,
    val title: String,
    val blurb: String,
    val track: CourseTrack,
    val premium: Boolean,
    val learnedCount: Int,
    val lessonCount: Int,
    val locked: Boolean,
)

data class LessonRow(
    val id: String,
    val title: String,
    val preview: String,
    val access: LessonAccess,
)

data class LessonListState(
    val stageId: String,
    val title: String,
    val rows: List<LessonRow>,
)

data class PracticeState(
    val mode: PracticeMode,
    val headline: String,
    val subtitle: String,
    val character: String,
    val strokes: List<List<Vec>>,
    val strokeIndex: Int,
    val glyphIndex: Int,
    val glyphCount: Int,
    val itemIndex: Int,
    val itemCount: Int,
    val phase: PracticePhase,
    val guideStyle: GuideStyle,
    val showHint: Boolean,
    val error: StrokeVerdict?,
    val justCleared: Boolean,
    val sessionComplete: Boolean,
    val unlockedNext: Boolean,
    val recallAssist: RecallAssist = RecallAssist.None,
    val showAnswer: Boolean = false,
    /** Strokes the learner actually drew, in view-box coordinates. Never snapped to the model. */
    val userStrokes: List<List<Vec>> = emptyList(),
    /** Grey KanjiVG reference drawn behind [userStrokes]. */
    val showReference: Boolean = false,
    /** Speaker control. Hidden when Japanese speech is unavailable. */
    val showSpeaker: Boolean = false,
)

data class WritingUiState(
    val screen: WritingScreenKind = WritingScreenKind.Path,
    val writingLessonsEnabled: Boolean = true,
    val showSettings: Boolean = false,
    val showIntro: Boolean = false,
    val showPaywall: Boolean = false,
    val notice: WritingNotice? = null,
    val premium: Boolean = false,
    val debugBuild: Boolean = false,
    val debugUnlock: Boolean = false,
    val tolerance: StrokeTolerance = StrokeTolerance.Relaxed,
    val priceLabel: String? = null,
    val dueCount: Int = 0,
    val stages: List<StageCard> = emptyList(),
    val lessons: LessonListState? = null,
    val practice: PracticeState? = null,
    val introStrokes: List<List<Vec>> = emptyList(),
    val uiLanguage: UiLanguage = UiLanguage.ES,
    val beginnerMode: Boolean = false,
    val autoPronounce: Boolean = true,
)

/**
 * Owns the writing course: path, stroke checking, spaced review, and whether
 * paid stages are open. Independent of the translator.
 */
class WritingViewModel(application: Application) : AndroidViewModel(application), PlayBilling.Listener {

    private val glyphs: Map<String, List<List<Vec>>> = StrokeRepository.load(application)
    private val course: Course = CourseCatalog.load(application)
    private val store = WritingProgressStore(application)
    private var progress: PersistedProgress = store.read()

    private val billing = PlayBilling(
        context = application,
        productId = BuildConfig.PREMIUM_PRODUCT_ID,
        listener = this,
    )
    private val speaker = WritingSpeaker(application) { available ->
        viewModelScope.launch {
            speechAvailable = available
            emit()
        }
    }
    private var speechAvailable: Boolean = false
    private var lastSpokenKey: String? = null

    private var screen: WritingScreenKind = WritingScreenKind.Path
    private var openStageId: String? = null
    private var showSettings: Boolean = false
    private var showIntro: Boolean = false
    private var showPaywall: Boolean = false
    private var notice: WritingNotice? = null
    private var priceLabel: String? = null
    private var language: UiLanguage = UiLanguage.ES
    private var session: Session? = null
    private var transientJob: Job? = null

    private val _ui = MutableStateFlow(buildState())
    val uiState: StateFlow<WritingUiState> = _ui.asStateFlow()

    init {
        billing.connect()
    }

    fun setLanguage(language: UiLanguage) {
        this.language = language
        emit()
    }

    fun openStage(stageId: String) {
        val stage = course.stage(stageId) ?: return
        if (!Entitlement.canOpen(stage.premium, premiumNow())) {
            showPaywall = true
            emit()
            return
        }
        openStageId = stageId
        screen = WritingScreenKind.Lessons
        emit()
    }

    fun closeLessons() {
        screen = WritingScreenKind.Path
        emit()
    }

    fun openLesson(lessonId: String) {
        val stage = currentStage() ?: return
        if (!Entitlement.canOpen(stage.premium, premiumNow())) {
            showPaywall = true
            emit()
            return
        }
        if (!WritingProgress.isPlayable(stage, lessonId, learned(), premiumNow())) {
            notice = WritingNotice.LessonLocked
            emit()
            return
        }
        val lesson = stage.lessons.firstOrNull { it.id == lessonId } ?: return
        beginSession(stage.id, lesson.id, PracticeMode.Lesson, lesson.items)
    }

    fun startDueReview() {
        val items = WritingProgress.dueItems(course, learned(), progress.srs, System.currentTimeMillis(), premiumNow())
        if (items.isEmpty()) return
        beginSession(stageId = null, lessonId = null, PracticeMode.DueReview, items)
    }

    fun startReinforce() {
        val items = WritingProgress.reinforce(course, learned(), progress.stats, premiumNow())
        if (items.isEmpty()) return
        beginSession(stageId = null, lessonId = null, PracticeMode.Reinforce, items)
    }

    fun openPaywall() {
        showPaywall = true
        emit()
    }

    fun closePaywall() {
        showPaywall = false
        emit()
    }

    fun purchase(activity: Activity) {
        billing.purchase(activity)
    }

    fun restorePurchases() {
        billing.restore()
    }

    fun setTolerance(tolerance: StrokeTolerance) {
        progress = progress.copy(tolerance = tolerance.name)
        persist()
        emit()
    }

    fun setDebugUnlock(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        progress = progress.copy(debugUnlock = enabled)
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

    fun setBeginnerMode(enabled: Boolean) {
        progress = progress.copy(beginnerMode = enabled)
        persist()
        emit()
    }

    fun setAutoPronounce(enabled: Boolean) {
        progress = progress.copy(autoPronounce = enabled)
        persist()
        if (enabled) lastSpokenKey = null else speaker.stop()
        emit()
    }

    /** Replays the current prompt. Does not reveal the written form. */
    fun replayPronunciation() {
        if (!speechAvailable) return
        val text = currentPronunciation() ?: return
        speaker.speak(text)
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
        speaker.stop()
        lastSpokenKey = null
        session = null
        showIntro = false
        screen = if (openStageId != null) WritingScreenKind.Lessons else WritingScreenKind.Path
        emit()
    }

    fun showHint() {
        val current = session ?: return
        if (current.complete || current.cleared || current.queue.isEmpty()) return
        if (current.queue.first().phase != PracticePhase.Fade) return
        session = current.copy(hint = true)
        emit()
    }

    /** Shows the answer in place. The learner keeps drawing; there is no redo pass. */
    fun giveUp() {
        val current = session ?: return
        if (current.complete || current.cleared || current.queue.isEmpty()) return
        if (current.queue.first().phase != PracticePhase.Recall) return
        if (!current.aided) {
            val itemId = current.queue.first().itemId
            val now = System.currentTimeMillis()
            progress = WritingProgress.withMistake(progress, itemId, now)
            progress = WritingProgress.withLapse(progress, itemId, now)
            persist()
        }
        session = current.copy(
            aided = true,
            revealedAnswer = true,
            mistakesOnItem = current.mistakesOnItem + if (current.aided) 0 else 1,
            error = null,
        )
        emit()
    }

    fun onStrokeFinished(points: List<Vec>) {
        val current = session ?: return
        if (current.complete || current.cleared) return
        val step = current.queue.firstOrNull() ?: return
        val item = current.items[step.itemId] ?: return
        val glyph = glyphs[item.glyphs.getOrNull(current.glyphIndex).orEmpty()]
        if (glyph == null || current.strokeIndex !in glyph.indices) {
            notice = WritingNotice.MissingGlyph
            emit()
            return
        }
        when (val verdict = StrokeMatcher.evaluate(points, glyph[current.strokeIndex], WritingProgress.toleranceOf(progress))) {
            StrokeVerdict.Accepted -> acceptStroke(current, glyph, points)
            StrokeVerdict.TooShort -> rejectStroke(current, verdict, countMistake = false)
            else -> rejectStroke(current, verdict, countMistake = step.phase == PracticePhase.Recall)
        }
    }

    override fun onOwned(owned: Boolean) {
        viewModelScope.launch {
            if (progress.playPremium == owned) return@launch
            progress = progress.copy(playPremium = owned)
            persist()
            emit()
        }
    }

    override fun onPrice(formatted: String?) {
        viewModelScope.launch {
            priceLabel = formatted
            emit()
        }
    }

    override fun onNotice(billingNotice: BillingNotice) {
        viewModelScope.launch {
            notice = when (billingNotice) {
                BillingNotice.Unavailable -> WritingNotice.BillingUnavailable
                BillingNotice.Pending -> WritingNotice.BillingPending
                BillingNotice.Error -> WritingNotice.BillingError
                BillingNotice.Restored -> WritingNotice.Restored
                BillingNotice.AlreadyOwned -> WritingNotice.AlreadyOwned
                BillingNotice.NothingToRestore -> WritingNotice.NothingToRestore
            }
            if (billingNotice == BillingNotice.Restored || billingNotice == BillingNotice.AlreadyOwned) {
                showPaywall = false
            }
            emit()
        }
    }

    private fun acceptStroke(current: Session, glyph: List<List<Vec>>, points: List<Vec>) {
        transientJob?.cancel()
        val drawn = current.copy(
            userStrokes = current.userStrokes + listOf(points),
            strokeMisses = 0,
            hint = false,
            error = null,
        )
        if (current.strokeIndex < glyph.lastIndex) {
            session = drawn.copy(strokeIndex = current.strokeIndex + 1)
            emit()
            return
        }
        val step = current.queue.first()
        val item = current.items.getValue(step.itemId)
        if (current.glyphIndex < item.glyphs.lastIndex) {
            session = drawn.copy(cleared = true, advanceGlyph = true)
            emit()
            transientJob = viewModelScope.launch {
                delay(CLEAR_DELAY_MS)
                val latest = session ?: return@launch
                if (!latest.cleared || !latest.advanceGlyph) return@launch
                session = latest.copy(
                    glyphIndex = latest.glyphIndex + 1,
                    strokeIndex = 0,
                    userStrokes = emptyList(),
                    strokeMisses = 0,
                    cleared = false,
                    advanceGlyph = false,
                    hint = false,
                    error = null,
                )
                emit()
            }
            return
        }
        finishAttempt(drawn)
    }

    private fun finishAttempt(current: Session) {
        val step = current.queue.first()
        val item = current.items.getValue(step.itemId)
        val now = System.currentTimeMillis()
        var mastered = current.mastered
        val nextQueue = when (step.phase) {
            PracticePhase.Teach, PracticePhase.Fade -> RecallFlow.afterGuided(current.queue)
            PracticePhase.Recall -> {
                if (current.aided) {
                    RecallFlow.afterMiss(current.queue)
                } else {
                    val required = if (current.mode == PracticeMode.Lesson && !current.beginner) {
                        RecallFlow.UNAIDED_TO_LEARN
                    } else {
                        1
                    }
                    val outcome = RecallFlow.afterUnaidedRecall(current.queue, required)
                    if (outcome.mastered) {
                        mastered = mastered + item.id
                        progress = WritingProgress.withSuccess(progress, item.id, now)
                        progress = WritingProgress.withScheduled(progress, item.id, current.mistakesOnItem, now)
                        persist()
                    }
                    outcome.queue
                }
            }
        }
        val lessonDone = nextQueue.isEmpty() && current.mode == PracticeMode.Lesson &&
            (current.beginner || RecallFlow.lessonMemorized(current.itemOrder, mastered))
        if (lessonDone && current.lessonId != null) {
            if (current.beginner) {
                current.itemOrder.forEach { id ->
                    progress = WritingProgress.withScheduled(progress, id, mistakes = 0, now)
                }
            }
            progress = WritingProgress.withLessonLearned(progress, current.lessonId)
            persist()
        }
        if (nextQueue.isNotEmpty()) {
            session = current.copy(
                cleared = true,
                hint = false,
                error = null,
                pendingQueue = nextQueue,
                mastered = mastered,
            )
            emit()
            transientJob = viewModelScope.launch {
                delay(CLEAR_DELAY_MS)
                val latest = session ?: return@launch
                val pending = latest.pendingQueue ?: return@launch
                if (!latest.cleared) return@launch
                session = latest.copy(
                    queue = pending,
                    pendingQueue = null,
                    glyphIndex = 0,
                    strokeIndex = 0,
                    hint = false,
                    error = null,
                    cleared = false,
                    aided = false,
                    revealedAnswer = false,
                    strokeMisses = 0,
                    userStrokes = emptyList(),
                    advanceGlyph = false,
                    completedAttempts = latest.completedAttempts + 1,
                    mistakesOnItem = 0,
                )
                emit()
            }
            return
        }
        val stage = current.stageId?.let { course.stage(it) }
        val unlockedNext = if (lessonDone && current.lessonId != null && stage != null) {
            val index = stage.lessons.indexOfFirst { it.id == current.lessonId }
            index >= 0 && index < stage.lessons.lastIndex
        } else {
            false
        }
        session = current.copy(
            queue = emptyList(),
            complete = true,
            hint = false,
            error = null,
            unlockedNext = unlockedNext,
            mastered = mastered,
        )
        emit()
    }

    private fun rejectStroke(current: Session, verdict: StrokeVerdict, countMistake: Boolean) {
        val next = if (countMistake) {
            val misses = current.strokeMisses + 1
            val itemId = current.queue.first().itemId
            val now = System.currentTimeMillis()
            progress = WritingProgress.withMistake(progress, itemId, now)
            val needsHint = misses >= RecallFlow.HINT_AFTER_MISTAKES
            if (needsHint && !current.aided) {
                progress = WritingProgress.withLapse(progress, itemId, now)
            }
            persist()
            current.copy(
                strokeMisses = misses,
                mistakesOnItem = current.mistakesOnItem + 1,
                aided = current.aided || needsHint,
                error = verdict,
            )
        } else {
            current.copy(error = verdict)
        }
        session = next
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
        stageId: String?,
        lessonId: String?,
        mode: PracticeMode,
        items: List<CourseItem>,
    ) {
        transientJob?.cancel()
        val playable = items.filter { item -> item.glyphs.all { glyphs[it]?.isNotEmpty() == true } }
        if (playable.isEmpty()) {
            notice = WritingNotice.MissingGlyph
            emit()
            return
        }
        val ids = playable.map { it.id }
        val beginner = progress.beginnerMode && mode == PracticeMode.Lesson
        val queue = if (mode == PracticeMode.Lesson) {
            RecallFlow.lessonSteps(ids, beginner = beginner)
        } else {
            RecallFlow.reviewSteps(ids)
        }
        session = Session(
            stageId = stageId,
            lessonId = lessonId,
            mode = mode,
            items = playable.associateBy { it.id },
            itemOrder = ids,
            queue = queue,
            beginner = beginner,
        )
        screen = WritingScreenKind.Practice
        showIntro = mode == PracticeMode.Lesson && progress.writingLessonsEnabled && !progress.introSeen
        emit()
    }

    private fun buildState(): WritingUiState {
        val learned = learned()
        val premium = premiumNow()
        val stage = currentStage()
        val active = session
        val now = System.currentTimeMillis()
        return WritingUiState(
            screen = screen,
            writingLessonsEnabled = progress.writingLessonsEnabled,
            showSettings = showSettings,
            showIntro = showIntro,
            showPaywall = showPaywall,
            notice = notice,
            premium = premium,
            debugBuild = BuildConfig.DEBUG,
            debugUnlock = progress.debugUnlock,
            tolerance = WritingProgress.toleranceOf(progress),
            priceLabel = priceLabel,
            dueCount = WritingProgress.dueItems(course, learned, progress.srs, now, premium).size,
            stages = course.stages.map { def ->
                StageCard(
                    id = def.id,
                    title = def.title(language),
                    blurb = def.blurb(language),
                    track = def.track,
                    premium = def.premium,
                    learnedCount = def.lessons.count { it.id in learned },
                    lessonCount = def.lessons.size,
                    locked = !Entitlement.canOpen(def.premium, premium),
                )
            },
            lessons = stage?.let { def ->
                LessonListState(
                    stageId = def.id,
                    title = def.title(language),
                    rows = def.lessons.mapIndexed { index, lesson ->
                        LessonRow(
                            id = lesson.id,
                            title = lesson.title(language),
                            preview = lesson.preview(),
                            access = WritingProgress.access(def, index, learned, premium),
                        )
                    },
                )
            },
            practice = active?.let { sessionToState(it) },
            introStrokes = glyphs["あ"].orEmpty(),
            uiLanguage = language,
            beginnerMode = progress.beginnerMode,
            autoPronounce = progress.autoPronounce,
        )
    }

    private fun sessionToState(current: Session): PracticeState? {
        val step = current.queue.firstOrNull()
        val item = step?.let { current.items[it.itemId] }
        if (step == null || item == null) {
            if (!current.complete) return null
            val fallback = current.items.values.firstOrNull() ?: return null
            return PracticeState(
                mode = current.mode,
                headline = fallback.headline(language),
                subtitle = fallback.subtitle(language),
                character = fallback.glyphs.first(),
                strokes = glyphs[fallback.glyphs.first()].orEmpty(),
                strokeIndex = 0,
                glyphIndex = 0,
                glyphCount = fallback.glyphs.size,
                itemIndex = current.completedAttempts,
                itemCount = current.completedAttempts.coerceAtLeast(1),
                phase = PracticePhase.Recall,
                guideStyle = GuideStyle.None,
                showHint = false,
                error = null,
                justCleared = false,
                sessionComplete = true,
                unlockedNext = current.unlockedNext,
                recallAssist = RecallAssist.None,
                showAnswer = false,
                userStrokes = emptyList(),
                showReference = false,
            )
        }
        val character = item.glyphs.getOrNull(current.glyphIndex) ?: return null
        val glyph = glyphs[character].orEmpty()
        val (headline, subtitle) = prompts(item, step.phase)
        val remaining = current.queue.size
        return PracticeState(
            mode = current.mode,
            headline = headline,
            subtitle = subtitle,
            character = character,
            strokes = glyph,
            strokeIndex = current.strokeIndex.coerceIn(0, (glyph.size - 1).coerceAtLeast(0)),
            glyphIndex = current.glyphIndex,
            glyphCount = item.glyphs.size,
            itemIndex = current.completedAttempts,
            itemCount = current.completedAttempts + remaining,
            phase = step.phase,
            guideStyle = RecallFlow.guideStyle(step.phase),
            showHint = current.hint && step.phase == PracticePhase.Fade,
            error = current.error,
            justCleared = current.cleared,
            sessionComplete = current.complete,
            unlockedNext = current.unlockedNext,
            recallAssist = if (step.phase == PracticePhase.Recall && !current.revealedAnswer) {
                RecallFlow.assistFor(current.strokeMisses)
            } else {
                RecallAssist.None
            },
            showAnswer = current.revealedAnswer && step.phase == PracticePhase.Recall,
            userStrokes = current.userStrokes,
            showReference = current.cleared || (current.revealedAnswer && step.phase == PracticePhase.Recall),
            showSpeaker = speechAvailable,
        )
    }

    private fun prompts(item: CourseItem, phase: PracticePhase): Pair<String, String> {
        if (RecallFlow.revealsAnswer(phase)) {
            return item.text to item.subtitle(language)
        }
        val meaning = item.meaning(language)
        val reading = item.spokenReading()
        return if (meaning == item.text || meaning.isBlank()) {
            reading to ""
        } else {
            meaning to reading
        }
    }

    private fun currentStage(): CourseStage? = openStageId?.let { course.stage(it) }

    private fun learned(): Set<String> = progress.learnedLessonIds.toSet()

    private fun premiumNow(): Boolean =
        Entitlement.isPremium(progress.playPremium, progress.debugUnlock, BuildConfig.DEBUG)

    private fun persist() {
        store.write(progress)
    }

    private fun emit() {
        val state = buildState()
        _ui.value = state
        considerAutoSpeak(state.practice)
    }

    /**
     * Speaks the reading when a new prompt appears. Recall still shows only
     * romaji or the meaning; the written character stays off the canvas.
     */
    private fun considerAutoSpeak(practice: PracticeState?) {
        if (!speechAvailable || !progress.autoPronounce) return
        if (practice == null || practice.justCleared || practice.sessionComplete) return
        val key = "${practice.itemIndex}|${practice.phase}|${practice.headline}|${practice.subtitle}"
        if (key == lastSpokenKey) return
        val text = currentPronunciation() ?: return
        lastSpokenKey = key
        speaker.speak(text)
    }

    private fun currentPronunciation(): String? {
        val current = session ?: return null
        if (current.complete || current.cleared || current.queue.isEmpty()) return null
        val item = current.items[current.queue.first().itemId] ?: return null
        return item.pronunciation().ifBlank { null }
    }

    override fun onCleared() {
        speaker.release()
        super.onCleared()
    }

    private data class Session(
        val stageId: String?,
        val lessonId: String?,
        val mode: PracticeMode,
        val items: Map<String, CourseItem>,
        val itemOrder: List<String>,
        val queue: List<RecallStep>,
        val glyphIndex: Int = 0,
        val strokeIndex: Int = 0,
        val mistakesOnItem: Int = 0,
        val aided: Boolean = false,
        val hint: Boolean = false,
        val error: StrokeVerdict? = null,
        val cleared: Boolean = false,
        val complete: Boolean = false,
        val unlockedNext: Boolean = false,
        val beginner: Boolean = false,
        val mastered: Set<String> = emptySet(),
        val pendingQueue: List<RecallStep>? = null,
        val completedAttempts: Int = 0,
        val strokeMisses: Int = 0,
        val revealedAnswer: Boolean = false,
        val userStrokes: List<List<Vec>> = emptyList(),
        val advanceGlyph: Boolean = false,
    )

    private companion object {
        const val CLEAR_DELAY_MS = 1600L
        const val ERROR_DELAY_MS = 900L
    }
}
