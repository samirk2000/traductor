package com.arnold.voicetranslator.writing

/**
 * How much of the model stroke is on screen.
 * Teach draws the full guide. Fade draws a faint outline. Recall starts blank.
 */
enum class GuideStyle {
    Full,
    Faint,
    None,
}

/**
 * Help shown during a blank-canvas recall, after repeated misses on the same stroke.
 * Nothing is drawn until [HINT_AFTER_MISTAKES] wrong attempts.
 */
enum class RecallAssist {
    None,
    Outline,
    Stroke,
}

/** One pass over a course item inside a practice session. */
enum class PracticePhase {
    Teach,
    Fade,
    Recall,
}

data class RecallStep(
    val itemId: String,
    val phase: PracticePhase,
    val unaidedStreak: Int = 0,
)

data class RecallOutcome(
    val queue: List<RecallStep>,
    /** True when this unaided recall was enough to count the item as memorized. */
    val mastered: Boolean,
)

/**
 * Lesson order: each new item is traced once with the full guide, then recalled
 * from memory in a shuffled mix. A recall that needed a hint comes back a few
 * steps later. A lesson is memorized only after [UNAIDED_TO_LEARN] unaided
 * recalls in a row, which adds one later recall and no more.
 */
object RecallFlow {

    const val TEACH_PASSES = 1
    const val FADE_PASSES = 0
    const val UNAIDED_TO_LEARN = 2
    const val REQUEUE_GAP = 2

    /** Wrong attempts on the current stroke before any hint is drawn. */
    const val HINT_AFTER_MISTAKES = 2

    fun guideStyle(phase: PracticePhase): GuideStyle = when (phase) {
        PracticePhase.Teach -> GuideStyle.Full
        PracticePhase.Fade -> GuideStyle.Faint
        PracticePhase.Recall -> GuideStyle.None
    }

    /** 0–1 misses: blank. 2: faint outline and start dot. 3 or more: the stroke, static. */
    fun assistFor(strokeMistakes: Int): RecallAssist = when {
        strokeMistakes >= HINT_AFTER_MISTAKES + 1 -> RecallAssist.Stroke
        strokeMistakes >= HINT_AFTER_MISTAKES -> RecallAssist.Outline
        else -> RecallAssist.None
    }

    fun revealsAnswer(phase: PracticePhase): Boolean = phase != PracticePhase.Recall

    /**
     * @param beginner when true, the lesson stays on the guided passes and does not
     * ask for a blank-canvas recall. Reviews ignore this and stay recall-only.
     */
    fun lessonSteps(
        itemIds: List<String>,
        beginner: Boolean,
        shuffle: (List<String>) -> List<String> = { it.shuffled() },
    ): List<RecallStep> {
        if (beginner) {
            return itemIds.map { id -> RecallStep(id, PracticePhase.Teach) }
        }
        val guided = itemIds.map { id -> RecallStep(id, PracticePhase.Teach) }
        val recall = shuffle(itemIds).map { RecallStep(it, PracticePhase.Recall) }
        return guided + recall
    }

    fun reviewSteps(
        itemIds: List<String>,
        shuffle: (List<String>) -> List<String> = { it.shuffled() },
    ): List<RecallStep> = shuffle(itemIds).map { RecallStep(it, PracticePhase.Recall) }

    fun afterGuided(queue: List<RecallStep>): List<RecallStep> = queue.drop(1)

    /**
     * A clean recall. [required] is 2 while learning a lesson and 1 in a review.
     * Short of that, the same item returns a few steps later with the streak kept.
     */
    fun afterUnaidedRecall(queue: List<RecallStep>, required: Int): RecallOutcome {
        val current = queue.first()
        val streak = current.unaidedStreak + 1
        if (streak >= required) {
            return RecallOutcome(queue.drop(1), mastered = true)
        }
        val again = current.copy(phase = PracticePhase.Recall, unaidedStreak = streak)
        return RecallOutcome(requeue(queue, again, REQUEUE_GAP), mastered = false)
    }

    /** Hint or an otherwise aided finish. The streak resets and the item comes back. */
    fun afterAidedRecall(queue: List<RecallStep>): List<RecallStep> {
        val again = queue.first().copy(phase = PracticePhase.Recall, unaidedStreak = 0)
        return requeue(queue, again, REQUEUE_GAP)
    }

    /**
     * The item was finished with a hint or a give-up. It returns later as a recall,
     * with the streak cleared. There is no extra guided pass.
     */
    fun afterMiss(queue: List<RecallStep>): List<RecallStep> {
        val again = queue.first().copy(phase = PracticePhase.Recall, unaidedStreak = 0)
        return requeue(queue, again, REQUEUE_GAP)
    }

    /** True when every id has been memorized. An empty id list is not a finished lesson. */
    fun lessonMemorized(itemIds: List<String>, mastered: Set<String>): Boolean =
        itemIds.isNotEmpty() && itemIds.all { it in mastered }

    private fun requeue(queue: List<RecallStep>, step: RecallStep, gap: Int): List<RecallStep> {
        val rest = queue.drop(1)
        val at = gap.coerceAtMost(rest.size)
        return rest.take(at) + step + rest.drop(at)
    }
}
