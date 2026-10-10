package com.arnold.voicetranslator.writing

import com.arnold.voicetranslator.ui.localization.UiLanguage
import kotlin.random.Random

/** Stroke tracing, or multiple-choice recognition of the same course items. */
enum class StudyMode { Strokes, Recognize }

enum class RecognizePrompt { GlyphToReading, ReadingToGlyph }

data class RecognizeQuestion(
    val itemId: String,
    val prompt: RecognizePrompt,
    val promptText: String,
    val hint: String,
    val speakText: String,
    val options: List<String>,
    val answerIndex: Int,
) {
    val key: String get() = "$itemId|${prompt.name}"

    val answerText: String get() = options[answerIndex]
}

/**
 * One recognition lesson. A miss stays in [queue] until it is answered
 * correctly. [firstTryCorrect] counts only the first time each question appears.
 */
data class RecognizeRun(
    val queue: List<RecognizeQuestion>,
    val total: Int,
    val missed: Set<String> = emptySet(),
    val firstTryCorrect: Int = 0,
    val awaiting: Boolean = false,
    val picked: Int? = null,
    val lastCorrect: Boolean = false,
    val complete: Boolean = false,
)

/**
 * Multiple-choice recognition over the writing course. Distractors prefer
 * look-alike kana, then the same lesson, then items the learner has already
 * recognized.
 */
object RecognitionQuiz {

    fun questions(
        lessonItems: List<CourseItem>,
        courseItems: List<CourseItem>,
        seenIds: Set<String>,
        language: UiLanguage,
        random: Random,
    ): List<RecognizeQuestion> {
        val lessonIds = lessonItems.map { it.id }.toSet()
        return lessonItems.flatMap { item ->
            listOf(
                question(item, RecognizePrompt.GlyphToReading, lessonIds, courseItems, seenIds, language, random),
                question(item, RecognizePrompt.ReadingToGlyph, lessonIds, courseItems, seenIds, language, random),
            )
        }.shuffled(random)
    }

    fun question(
        item: CourseItem,
        prompt: RecognizePrompt,
        lessonIds: Set<String>,
        courseItems: List<CourseItem>,
        seenIds: Set<String>,
        language: UiLanguage,
        random: Random,
    ): RecognizeQuestion {
        val answer = label(item, prompt)
        val ranked = courseItems
            .filter { eligible(it, item, prompt) }
            .groupBy { rank(it, item, lessonIds, seenIds) }
            .toSortedMap()
            .flatMap { (_, group) -> group.shuffled(random) }
            .distinctBy { label(it, prompt) }
            .take(3)
        val options = (ranked.map { label(it, prompt) } + answer).shuffled(random)
        val answerIndex = options.indexOf(answer).coerceAtLeast(0)
        return RecognizeQuestion(
            itemId = item.id,
            prompt = prompt,
            promptText = if (prompt == RecognizePrompt.GlyphToReading) item.text else item.spokenReading(),
            hint = hint(item, prompt, language),
            speakText = item.pronunciation(),
            options = options,
            answerIndex = answerIndex,
        )
    }

    fun newRun(queue: List<RecognizeQuestion>): RecognizeRun =
        RecognizeRun(queue = queue, total = queue.size, complete = queue.isEmpty())

    fun answer(run: RecognizeRun, picked: Int): RecognizeRun {
        if (run.complete || run.awaiting) return run
        val question = run.queue.firstOrNull() ?: return run
        if (picked !in question.options.indices) return run
        return run.copy(
            awaiting = true,
            picked = picked,
            lastCorrect = picked == question.answerIndex,
        )
    }

    /**
     * Leaves the current card. A wrong answer is appended again as [rebuilt]
     * so the character returns later in the session.
     */
    fun advance(run: RecognizeRun, rebuilt: RecognizeQuestion): RecognizeRun {
        if (!run.awaiting || run.complete) return run
        val question = run.queue.firstOrNull() ?: return run.copy(complete = true, awaiting = false)
        val rest = run.queue.drop(1)
        val retry = question.key in run.missed
        val next = if (run.lastCorrect) rest else rest + rebuilt
        val missed = if (run.lastCorrect) run.missed else run.missed + question.key
        val firstTry = run.firstTryCorrect + if (run.lastCorrect && !retry) 1 else 0
        return run.copy(
            queue = next,
            missed = missed,
            firstTryCorrect = firstTry,
            awaiting = false,
            picked = null,
            lastCorrect = false,
            complete = next.isEmpty(),
        )
    }

    fun label(item: CourseItem, prompt: RecognizePrompt): String =
        if (prompt == RecognizePrompt.GlyphToReading) item.spokenReading() else item.text

    private fun hint(item: CourseItem, prompt: RecognizePrompt, language: UiLanguage): String {
        if (prompt != RecognizePrompt.ReadingToGlyph) return ""
        val meaning = item.meaning(language)
        return if (meaning.isBlank() || meaning == item.text) "" else meaning
    }

    private fun eligible(candidate: CourseItem, answer: CourseItem, prompt: RecognizePrompt): Boolean {
        if (candidate.id == answer.id) return false
        val candidateLabel = label(candidate, prompt)
        if (candidateLabel.isBlank() || candidateLabel == label(answer, prompt)) return false
        if (prompt == RecognizePrompt.ReadingToGlyph && candidate.spokenReading() == answer.spokenReading()) return false
        return true
    }

    private fun rank(
        candidate: CourseItem,
        answer: CourseItem,
        lessonIds: Set<String>,
        seenIds: Set<String>,
    ): Int = when {
        similar(answer.text, candidate.text) -> 0
        candidate.id in lessonIds -> 1
        candidate.id in seenIds -> 2
        else -> 3
    }

    fun similar(left: String, right: String): Boolean {
        if (left == right) return false
        return SIMILAR.any { left in it && right in it }
    }

    private val SIMILAR: List<Set<String>> = listOf(
        setOf("シ", "ツ"),
        setOf("ソ", "ン"),
        setOf("ヌ", "ス", "フ"),
        setOf("ウ", "ワ", "フ", "ラ"),
        setOf("ク", "ケ", "タ"),
        setOf("ア", "マ"),
        setOf("コ", "ユ"),
        setOf("ロ", "ル", "レ"),
        setOf("ぬ", "め", "ね", "れ", "わ"),
        setOf("あ", "お"),
        setOf("い", "り"),
        setOf("う", "ら"),
        setOf("き", "さ"),
        setOf("は", "ほ"),
        setOf("る", "ろ"),
        setOf("そ", "ん", "ろ"),
        setOf("つ", "し"),
        setOf("か", "が"),
        setOf("き", "ぎ"),
        setOf("く", "ぐ"),
        setOf("け", "げ"),
        setOf("こ", "ご"),
        setOf("さ", "ざ"),
        setOf("し", "じ"),
        setOf("す", "ず"),
        setOf("せ", "ぜ"),
        setOf("そ", "ぞ"),
        setOf("た", "だ"),
        setOf("ち", "ぢ"),
        setOf("つ", "づ"),
        setOf("て", "で"),
        setOf("と", "ど"),
        setOf("は", "ば", "ぱ"),
        setOf("ひ", "び", "ぴ"),
        setOf("ふ", "ぶ", "ぷ"),
        setOf("へ", "べ", "ぺ"),
        setOf("ほ", "ぼ", "ぽ"),
        setOf("カ", "ガ"),
        setOf("キ", "ギ"),
        setOf("ク", "グ"),
        setOf("ケ", "ゲ"),
        setOf("コ", "ゴ"),
        setOf("サ", "ザ"),
        setOf("シ", "ジ"),
        setOf("ス", "ズ"),
        setOf("セ", "ゼ"),
        setOf("ソ", "ゾ"),
        setOf("タ", "ダ"),
        setOf("チ", "ヂ"),
        setOf("ツ", "ヅ"),
        setOf("テ", "デ"),
        setOf("ト", "ド"),
        setOf("ハ", "バ", "パ"),
        setOf("ヒ", "ビ", "ピ"),
        setOf("フ", "ブ", "プ"),
        setOf("ヘ", "ベ", "ペ"),
        setOf("ホ", "ボ", "ポ"),
        setOf("ゃ", "や"),
        setOf("ゅ", "ゆ"),
        setOf("ょ", "よ"),
        setOf("ャ", "ヤ"),
        setOf("ュ", "ユ"),
        setOf("ョ", "ヨ"),
        setOf("っ", "つ"),
        setOf("ッ", "ツ"),
    )
}
