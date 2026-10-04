package com.arnold.voicetranslator.writing

/**
 * How much of the model stroke is on screen.
 * Recall is a blank canvas. Teach and the correction after a miss use the full guide.
 */
enum class GuideStyle {
    Full,
    Faint,
    None,
}

/** One pass over a course item inside a practice session. */
enum class PracticePhase {
    Teach,
    Fade,
    Recall,
    Repair,
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
 * Lesson order: each new item is traced with the guide, then with a faint outline,
 * then recalled from memory in a shuffled mix. A miss inserts a guided correction
 * and brings the item back a few steps later. A lesson is memorized only after
 * [UNAIDED_TO_LEARN] unaided recalls in a row.
 */
object RecallFlow {

    const val TEACH_PASSES = 2
    const val FADE_PASSES = 1
    const val UNAIDED_TO_LEARN = 2
    const val REQUEUE_GAP = 2

    fun guideStyle(phase: PracticePhase): GuideStyle = when (phase) {
        PracticePhase.Teach, PracticePhase.Repair -> GuideStyle.Full
        PracticePhase.Fade -> GuideStyle.Faint
        PracticePhase.Recall -> GuideStyle.None
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
            return itemIds.flatMap { id -> List(TEACH_PASSES) { RecallStep(id, PracticePhase.Teach) } }
        }
        val guided = itemIds.flatMap { id ->
            List(TEACH_PASSES) { RecallStep(id, PracticePhase.Teach) } +
                List(FADE_PASSES) { RecallStep(id, PracticePhase.Fade) }
        }
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
     * Wrong stroke or give-up during recall. The learner traces the item once with
     * the guide, then meets it again a few steps later, streak cleared.
     */
    fun afterMiss(queue: List<RecallStep>): List<RecallStep> {
        val current = queue.first()
        val rest = queue.drop(1)
        val repair = RecallStep(current.itemId, PracticePhase.Repair, unaidedStreak = 0)
        val again = RecallStep(current.itemId, PracticePhase.Recall, unaidedStreak = 0)
        val withRepair = listOf(repair) + rest
        val at = (1 + REQUEUE_GAP).coerceAtMost(withRepair.size)
        return withRepair.take(at) + again + withRepair.drop(at)
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
