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
    val showGuide: Boolean,
    val showHint: Boolean,
    val error: StrokeVerdict?,
    val justCleared: Boolean,
    val sessionComplete: Boolean,
    val unlockedNext: Boolean,
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
        screen = if (openStageId != null) WritingScreenKind.Lessons else WritingScreenKind.Path
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
        val item = current.items.getOrNull(current.itemIndex) ?: return
        val glyph = glyphs[item.glyphs.getOrNull(current.glyphIndex).orEmpty()]
        if (glyph == null || current.strokeIndex !in glyph.indices) {
            notice = WritingNotice.MissingGlyph
            emit()
            return
        }
        when (val verdict = StrokeMatcher.evaluate(points, glyph[current.strokeIndex], WritingProgress.toleranceOf(progress))) {
            StrokeVerdict.Accepted -> acceptStroke(current, glyph)
            StrokeVerdict.TooShort -> rejectStroke(current, verdict, countMistake = false)
            else -> rejectStroke(current, verdict, countMistake = true)
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

    private fun acceptStroke(current: Session, glyph: List<List<Vec>>) {
        transientJob?.cancel()
        if (current.strokeIndex < glyph.lastIndex) {
            session = current.copy(strokeIndex = current.strokeIndex + 1, hint = false, error = null)
            emit()
            return
        }
        val item = current.items[current.itemIndex]
        val now = System.currentTimeMillis()
        if (current.glyphIndex < item.glyphs.lastIndex) {
            session = current.copy(
                glyphIndex = current.glyphIndex + 1,
                strokeIndex = 0,
                hint = false,
                error = null,
            )
            emit()
            return
        }
        progress = WritingProgress.withSuccess(progress, item.id, now)
        progress = WritingProgress.withScheduled(progress, item.id, current.mistakesOnItem, now)
        val lastItem = current.itemIndex == current.items.lastIndex
        if (lastItem && current.mode == PracticeMode.Lesson && current.lessonId != null) {
            progress = WritingProgress.withLessonLearned(progress, current.lessonId)
        }
        persist()
        if (!lastItem) {
            session = current.copy(cleared = true, hint = false, error = null)
            emit()
            transientJob = viewModelScope.launch {
                delay(CLEAR_DELAY_MS)
                val latest = session ?: return@launch
                if (!latest.cleared) return@launch
                session = latest.copy(
                    itemIndex = latest.itemIndex + 1,
                    glyphIndex = 0,
                    strokeIndex = 0,
                    mistakesOnItem = 0,
                    cleared = false,
                    hint = false,
                    error = null,
                )
                emit()
            }
            return
        }
        val stage = current.stageId?.let { course.stage(it) }
        val unlockedNext = if (current.mode == PracticeMode.Lesson && current.lessonId != null && stage != null) {
            val index = stage.lessons.indexOfFirst { it.id == current.lessonId }
            index >= 0 && index < stage.lessons.lastIndex
        } else {
            false
        }
        session = current.copy(complete = true, hint = false, error = null, unlockedNext = unlockedNext)
        emit()
    }

    private fun rejectStroke(current: Session, verdict: StrokeVerdict, countMistake: Boolean) {
        var mistakes = current.mistakesOnItem
        if (countMistake) {
            val item = current.items[current.itemIndex]
            mistakes += 1
            progress = WritingProgress.withMistake(progress, item.id, System.currentTimeMillis())
            persist()
        }
        session = current.copy(error = verdict, mistakesOnItem = mistakes)
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
        session = Session(stageId = stageId, lessonId = lessonId, mode = mode, items = playable)
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
        )
    }

    private fun sessionToState(current: Session): PracticeState? {
        val item = current.items.getOrNull(current.itemIndex) ?: return null
        val character = item.glyphs.getOrNull(current.glyphIndex) ?: return null
        val glyph = glyphs[character].orEmpty()
        return PracticeState(
            mode = current.mode,
            headline = item.headline(language),
            subtitle = item.subtitle(language),
            character = character,
            strokes = glyph,
            strokeIndex = current.strokeIndex.coerceIn(0, (glyph.size - 1).coerceAtLeast(0)),
            glyphIndex = current.glyphIndex,
            glyphCount = item.glyphs.size,
            itemIndex = current.itemIndex,
            itemCount = current.items.size,
            showGuide = progress.writingLessonsEnabled,
            showHint = current.hint && progress.writingLessonsEnabled,
            error = current.error,
            justCleared = current.cleared,
            sessionComplete = current.complete,
            unlockedNext = current.unlockedNext,
        )
    }

    private fun currentStage(): CourseStage? = openStageId?.let { course.stage(it) }

    private fun learned(): Set<String> = progress.learnedLessonIds.toSet()

    private fun premiumNow(): Boolean =
        Entitlement.isPremium(progress.playPremium, progress.debugUnlock, BuildConfig.DEBUG)

    private fun persist() {
        store.write(progress)
    }

    private fun emit() {
        _ui.value = buildState()
    }

    private data class Session(
        val stageId: String?,
        val lessonId: String?,
        val mode: PracticeMode,
        val items: List<CourseItem>,
        val itemIndex: Int = 0,
        val glyphIndex: Int = 0,
        val strokeIndex: Int = 0,
        val mistakesOnItem: Int = 0,
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
