package com.arnold.voicetranslator.writing

/**
 * Hiragana and katakana lessons, grouped the way a kana writing course does:
 * basic rows, then the dakuten row that belongs with them, then handakuten.
 * Small kana (ゃゅょ, っ) and obsolete kana are not part of this course.
 */
enum class WritingScript {
    Hiragana,
    Katakana,
    Mixed,
}

data class WritingLessonDef(
    val id: String,
    val number: Int,
    val characters: List<String>,
)

data class WritingLevelDef(
    val id: String,
    val number: Int,
    val script: WritingScript,
    /** Character drawn inside the level circle. */
    val symbol: String,
    val lessons: List<WritingLessonDef>,
)

object KanaCatalog {

    /** Gojūon rows, with dakuten / handakuten sitting right after the row they modify. */
    val rows: List<String> = listOf(
        "あいうえお",
        "かきくけこ",
        "がぎぐげご",
        "さしすせそ",
        "ざじずぜぞ",
        "たちつてと",
        "だぢづでど",
        "なにぬねの",
        "はひふへほ",
        "ばびぶべぼ",
        "ぱぴぷぺぽ",
        "まみむめも",
        "やゆよ",
        "らりるれろ",
        "わをん",
    )

    val levels: List<WritingLevelDef> = listOf(
        level("hiragana", 1, WritingScript.Hiragana, "あ", rows.map { it.map(Char::toString) }),
        level(
            "katakana",
            2,
            WritingScript.Katakana,
            "ア",
            rows.map { row -> row.map { kataOf(it).toString() } },
        ),
        level(
            "mixed",
            3,
            WritingScript.Mixed,
            "あア",
            rows.map { row ->
                row.flatMap { ch -> listOf(ch.toString(), kataOf(ch).toString()) }
            },
        ),
    )

    fun level(id: String): WritingLevelDef = levels.first { it.id == id }

    fun lesson(levelId: String, lessonId: String): WritingLessonDef? =
        level(levelId).lessons.firstOrNull { it.id == lessonId }

    /** Every distinct kana the course asks the learner to write. */
    fun allCharacters(): Set<String> = levels.flatMap { level ->
        level.lessons.flatMap { it.characters }
    }.toSet()

    fun preview(lesson: WritingLessonDef, script: WritingScript): String {
        if (script != WritingScript.Mixed) return lesson.characters.joinToString("")
        val hiragana = lesson.characters.filter { it.first() < '\u30A0' }.joinToString("")
        val katakana = lesson.characters.filter { it.first() >= '\u30A0' }.joinToString("")
        return "$hiragana · $katakana"
    }

    private fun level(
        id: String,
        number: Int,
        script: WritingScript,
        symbol: String,
        rows: List<List<String>>,
    ): WritingLevelDef = WritingLevelDef(
        id = id,
        number = number,
        script = script,
        symbol = symbol,
        lessons = rows.mapIndexed { index, characters ->
            WritingLessonDef(
                id = "$id-${index + 1}",
                number = index + 1,
                characters = characters,
            )
        },
    )

    private fun kataOf(hiragana: Char): Char = hiragana + 0x60
}
