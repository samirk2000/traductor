package com.arnold.voicetranslator.writing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

enum class LessonAccess {
    Learned,
    Open,
    Sequential,
    Premium,
}

enum class PracticeMode {
    Lesson,
    DueReview,
    Reinforce,
}

@Serializable
data class CharacterStats(
    val mistakes: Int = 0,
    val successes: Int = 0,
    val lastPracticedAt: Long = 0L,
)

/** SM-2 card for one course item (a kana, a word, a kanji, or a phrase). */
@Serializable
data class SrsCard(
    val ease: Float = 2.5f,
    val intervalDays: Int = 0,
    val dueAt: Long = 0L,
    val reps: Int = 0,
    val lapses: Int = 0,
)

@Serializable
data class PersistedProgress(
    val learnedLessonIds: List<String> = emptyList(),
    val stats: Map<String, CharacterStats> = emptyMap(),
    val srs: Map<String, SrsCard> = emptyMap(),
    val writingLessonsEnabled: Boolean = true,
    val introSeen: Boolean = false,
    val listMode: Boolean = true,
    val tolerance: String = StrokeTolerance.Relaxed.name,
    /** Set from a confirmed Play Billing purchase. */
    val playPremium: Boolean = false,
    /**
     * Owner unlock for debug builds only. Release builds ignore this flag
     * even if it is present in an old file.
     */
    val debugUnlock: Boolean = false,
    /**
     * Guided lessons for someone just starting. Recall stays the default.
     * Reviews ignore this and stay blank-canvas.
     */
    val beginnerMode: Boolean = false,
    /** Speak the Japanese reading when a character is shown. */
    val autoPronounce: Boolean = true,
    /**
     * Wall-clock time of the last server confirmation, or 0 when Play Billing
     * has never been confirmed by POST /verify-purchase.
     */
    val premiumVerifiedAt: Long = 0L,
    /** Last Play purchase token. Sent back to the Worker on the next check. Never logged. */
    val purchaseToken: String = "",
    /**
     * True after the Worker has answered with the verifier configured.
     * While this is false, a Play PURCHASED result is enough (the service
     * account is not set up yet). Once true, a prefs flag with no timestamp
     * does not grant premium.
     */
    val purchaseCheckRequired: Boolean = false,
)

/**
 * Whether paid stages open. The debug switch counts only when the build
 * itself is a debug build, so a release APK has no unlock backdoor.
 *
 * After the server has verified a purchase once, premium also requires a
 * recent [premiumVerifiedAt]. Offline, that timestamp stays valid for
 * [PURCHASE_GRACE_MS]. A playPremium flag that was never confirmed by the
 * server does not count once [purchaseCheckRequired] is set.
 */
object Entitlement {
    const val PURCHASE_GRACE_MS = 7L * 24L * 60L * 60L * 1000L

    fun isPremium(
        playOwned: Boolean,
        debugUnlock: Boolean,
        debugBuild: Boolean,
        purchaseCheckRequired: Boolean = false,
        verifiedAt: Long = 0L,
        now: Long = 0L,
        graceMs: Long = PURCHASE_GRACE_MS,
    ): Boolean {
        if (debugBuild && debugUnlock) return true
        if (!playOwned) return false
        if (!purchaseCheckRequired) return true
        if (verifiedAt <= 0L) return false
        return now - verifiedAt <= graceMs
    }

    fun canOpen(stagePremium: Boolean, premium: Boolean): Boolean = !stagePremium || premium
}

/** What POST /verify-purchase decided. [NotConfigured] means the Worker secret is unset. */
enum class ServerPurchaseVerdict {
    NotConfigured,
    Owned,
    NotOwned,
    Unavailable,
}

object PurchaseVerification {
    /**
     * Folds a Play Billing result and the Worker verdict into stored progress.
     *
     * [ServerPurchaseVerdict.NotConfigured] is the explicit fallback for a
     * Worker that has no PLAY_SERVICE_ACCOUNT_JSON yet: trust Play Billing
     * and leave [PersistedProgress.purchaseCheckRequired] false, so the app
     * keeps working before the service account is uploaded. Once the Worker
     * has answered as configured, a later "not owned" (refund or revoke)
     * clears premium and a prefs edit without a fresh timestamp cannot turn
     * it back on.
     */
    fun apply(
        current: PersistedProgress,
        playOwned: Boolean,
        token: String?,
        verdict: ServerPurchaseVerdict?,
        now: Long,
    ): PersistedProgress {
        if (!playOwned) {
            return current.copy(
                playPremium = false,
                purchaseToken = "",
                premiumVerifiedAt = if (current.purchaseCheckRequired) 0L else current.premiumVerifiedAt,
            )
        }
        val purchaseToken = token?.takeIf { it.isNotBlank() } ?: current.purchaseToken
        return when (verdict) {
            null, ServerPurchaseVerdict.NotConfigured -> current.copy(
                playPremium = true,
                purchaseToken = purchaseToken,
            )
            ServerPurchaseVerdict.Owned -> current.copy(
                playPremium = true,
                purchaseToken = purchaseToken,
                purchaseCheckRequired = true,
                premiumVerifiedAt = now,
            )
            ServerPurchaseVerdict.NotOwned -> current.copy(
                playPremium = false,
                purchaseToken = "",
                purchaseCheckRequired = true,
                premiumVerifiedAt = 0L,
            )
            ServerPurchaseVerdict.Unavailable -> when {
                !current.purchaseCheckRequired -> current.copy(
                    playPremium = true,
                    purchaseToken = purchaseToken,
                )
                current.premiumVerifiedAt > 0L &&
                    now - current.premiumVerifiedAt <= Entitlement.PURCHASE_GRACE_MS -> current.copy(
                    playPremium = true,
                    purchaseToken = purchaseToken,
                )
                else -> current.copy(
                    playPremium = false,
                    purchaseToken = purchaseToken,
                )
            }
        }
    }
}

object WritingProgress {

    fun toleranceOf(progress: PersistedProgress): StrokeTolerance =
        StrokeTolerance.entries.firstOrNull { it.name == progress.tolerance } ?: StrokeTolerance.Relaxed

    fun access(
        stage: CourseStage,
        lessonIndex: Int,
        learned: Set<String>,
        premium: Boolean,
    ): LessonAccess {
        if (!Entitlement.canOpen(stage.premium, premium)) return LessonAccess.Premium
        val lesson = stage.lessons[lessonIndex]
        if (lesson.id in learned) return LessonAccess.Learned
        if (lessonIndex == 0 || stage.lessons[lessonIndex - 1].id in learned) return LessonAccess.Open
        return LessonAccess.Sequential
    }

    fun isPlayable(stage: CourseStage, lessonId: String, learned: Set<String>, premium: Boolean): Boolean {
        val index = stage.lessons.indexOfFirst { it.id == lessonId }
        if (index < 0) return false
        val access = access(stage, index, learned, premium)
        return access == LessonAccess.Open || access == LessonAccess.Learned
    }

    /**
     * Learned items that are due, from stages the learner is allowed to open.
     * Items without a card yet count as due.
     */
    fun dueItems(
        course: Course,
        learned: Set<String>,
        cards: Map<String, SrsCard>,
        now: Long,
        premium: Boolean,
        limit: Int = DUE_LIMIT,
    ): List<CourseItem> {
        return accessibleItems(course, learned, premium)
            .filter { item ->
                val card = cards[item.id]
                card == null || card.dueAt <= now
            }
            .sortedBy { cards[it.id]?.dueAt ?: 0L }
            .take(limit)
    }

    /** Learned items with the most mistakes, so practice goes where it hurts. */
    fun reinforce(
        course: Course,
        learned: Set<String>,
        stats: Map<String, CharacterStats>,
        premium: Boolean,
        limit: Int = REINFORCE_LIMIT,
    ): List<CourseItem> {
        val pool = accessibleItems(course, learned, premium)
        if (pool.isEmpty()) return emptyList()
        return pool
            .sortedWith(
                compareByDescending<CourseItem> { stats[it.id]?.mistakes ?: 0 }
                    .thenBy { stats[it.id]?.lastPracticedAt ?: 0L },
            )
            .take(limit)
    }

    fun schedule(card: SrsCard, mistakesDuringItem: Int, now: Long): SrsCard {
        val quality = when {
            mistakesDuringItem <= 0 -> 5
            mistakesDuringItem <= 2 -> 4
            else -> 3
        }
        val delta = 0.1f - (5 - quality) * (0.08f + (5 - quality) * 0.02f)
        val ease = (card.ease + delta).coerceAtLeast(1.3f)
        val interval = when (card.reps) {
            0 -> 1
            1 -> 3
            else -> (card.intervalDays * ease).roundToInt().coerceAtLeast(1)
        }
        return card.copy(
            ease = ease,
            intervalDays = interval,
            reps = card.reps + 1,
            dueAt = now + interval * DAY_MS,
        )
    }

    fun withMistake(progress: PersistedProgress, itemId: String, now: Long): PersistedProgress {
        val current = progress.stats[itemId] ?: CharacterStats()
        val updated = current.copy(mistakes = current.mistakes + 1, lastPracticedAt = now)
        return progress.copy(stats = progress.stats + (itemId to updated))
    }

    fun withSuccess(progress: PersistedProgress, itemId: String, now: Long): PersistedProgress {
        val current = progress.stats[itemId] ?: CharacterStats()
        val updated = current.copy(successes = current.successes + 1, lastPracticedAt = now)
        return progress.copy(stats = progress.stats + (itemId to updated))
    }

    fun withScheduled(progress: PersistedProgress, itemId: String, mistakes: Int, now: Long): PersistedProgress {
        val card = schedule(progress.srs[itemId] ?: SrsCard(), mistakes, now)
        return progress.copy(srs = progress.srs + (itemId to card))
    }

    /** A failed recall comes back soon instead of on the old interval. */
    fun lapse(card: SrsCard, now: Long): SrsCard = card.copy(
        ease = (card.ease - 0.2f).coerceAtLeast(1.3f),
        intervalDays = 0,
        reps = 0,
        lapses = card.lapses + 1,
        dueAt = now + SHORT_INTERVAL_MS,
    )

    fun withLapse(progress: PersistedProgress, itemId: String, now: Long): PersistedProgress {
        val card = lapse(progress.srs[itemId] ?: SrsCard(), now)
        return progress.copy(srs = progress.srs + (itemId to card))
    }

    fun withLessonLearned(progress: PersistedProgress, lessonId: String): PersistedProgress {
        if (lessonId in progress.learnedLessonIds) return progress
        return progress.copy(learnedLessonIds = progress.learnedLessonIds + lessonId)
    }

    private fun accessibleItems(course: Course, learned: Set<String>, premium: Boolean): List<CourseItem> {
        return course.stages
            .filter { Entitlement.canOpen(it.premium, premium) }
            .flatMap { stage -> stage.lessons.filter { it.id in learned } }
            .flatMap { it.items }
    }

    const val DUE_LIMIT = 12
    const val REINFORCE_LIMIT = 8
    const val DAY_MS = 86_400_000L
    const val SHORT_INTERVAL_MS = 10 * 60 * 1000L
}

object ProgressCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(progress: PersistedProgress): String = json.encodeToString(PersistedProgress.serializer(), progress)

    fun decode(raw: String): PersistedProgress = json.decodeFromString(PersistedProgress.serializer(), raw)
}
