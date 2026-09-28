package com.arnold.voicetranslator.writing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class LessonStatus {
    Learned,
    Available,
    Locked,
}

enum class PracticeMode {
    Lesson,
    SmartReview,
    ReviewAll,
}

@Serializable
data class CharacterStats(
    val mistakes: Int = 0,
    val successes: Int = 0,
    val lastPracticedAt: Long = 0L,
)

@Serializable
data class PersistedProgress(
    val learnedLessonIds: List<String> = emptyList(),
    val stats: Map<String, CharacterStats> = emptyMap(),
    val writingLessonsEnabled: Boolean = true,
    val introSeen: Boolean = false,
    val listMode: Boolean = true,
)

/**
 * Pure progress rules: sequential unlocking, and which characters a review
 * should ask for. Persistence is just [PersistedProgress] encoded as JSON.
 */
object WritingProgress {

    fun status(level: WritingLevelDef, lessonIndex: Int, learned: Set<String>): LessonStatus {
        val lesson = level.lessons[lessonIndex]
        if (lesson.id in learned) return LessonStatus.Learned
        if (lessonIndex == 0 || level.lessons[lessonIndex - 1].id in learned) {
            return LessonStatus.Available
        }
        return LessonStatus.Locked
    }

    fun isUnlocked(level: WritingLevelDef, lessonId: String, learned: Set<String>): Boolean {
        val index = level.lessons.indexOfFirst { it.id == lessonId }
        if (index < 0) return false
        return status(level, index, learned) != LessonStatus.Locked
    }

    /** Characters from unlocked lessons, in course order. */
    fun reviewAll(level: WritingLevelDef, learned: Set<String>): List<String> {
        return level.lessons
            .filter { isUnlocked(level, it.id, learned) }
            .flatMap { it.characters }
    }

    /**
     * Up to [limit] characters from the unlocked lessons.
     * More mistakes come first; within the same mistake count, the least
     * recently practiced (never practiced counts as oldest) comes first.
     */
    fun smartReview(
        level: WritingLevelDef,
        learned: Set<String>,
        stats: Map<String, CharacterStats>,
        limit: Int = SMART_REVIEW_LIMIT,
    ): List<String> {
        val pool = reviewAll(level, learned)
        val order = pool.withIndex().associate { it.value to it.index }
        return pool
            .sortedWith(
                compareByDescending<String> { stats[it]?.mistakes ?: 0 }
                    .thenBy { stats[it]?.lastPracticedAt ?: 0L }
                    .thenBy { order[it] ?: 0 },
            )
            .take(limit)
    }

    fun withMistake(progress: PersistedProgress, character: String, now: Long): PersistedProgress {
        val current = progress.stats[character] ?: CharacterStats()
        val updated = current.copy(mistakes = current.mistakes + 1, lastPracticedAt = now)
        return progress.copy(stats = progress.stats + (character to updated))
    }

    fun withSuccess(progress: PersistedProgress, character: String, now: Long): PersistedProgress {
        val current = progress.stats[character] ?: CharacterStats()
        val updated = current.copy(successes = current.successes + 1, lastPracticedAt = now)
        return progress.copy(stats = progress.stats + (character to updated))
    }

    fun withLessonLearned(progress: PersistedProgress, lessonId: String): PersistedProgress {
        if (lessonId in progress.learnedLessonIds) return progress
        return progress.copy(learnedLessonIds = progress.learnedLessonIds + lessonId)
    }

    const val SMART_REVIEW_LIMIT = 10
}

object ProgressCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(progress: PersistedProgress): String = json.encodeToString(PersistedProgress.serializer(), progress)

    fun decode(raw: String): PersistedProgress = json.decodeFromString(PersistedProgress.serializer(), raw)
}
