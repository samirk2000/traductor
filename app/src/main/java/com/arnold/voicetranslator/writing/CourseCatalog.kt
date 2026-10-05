package com.arnold.voicetranslator.writing

import android.content.Context
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.util.KanaRomaji
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CourseTrack { Kana, Words, Kanji, Phrases }

/**
 * One thing the learner writes: a kana, a kanji, a word, or a phrase.
 * [reveal] is `glyph` when the character itself is the prompt (kana, kanji)
 * and `hidden` when they write from the meaning and reading (words, phrases).
 *
 * New lessons are rows in `assets/writing/curriculum.json`. A later kanji
 * level is another stage with `"track": "kanji"`; no course code has to change.
 */
data class CourseItem(
    val id: String,
    val glyphs: List<String>,
    val reading: String,
    val meaningEs: String,
    val meaningEn: String,
    val reveal: String,
) {
    val text: String get() = glyphs.joinToString("")

    fun meaning(language: UiLanguage): String = if (language == UiLanguage.ES) meaningEs else meaningEn

    fun spokenReading(): String = reading.ifBlank { KanaRomaji.toRomaji(text) }

    /**
     * Text for Japanese speech. Kana is spoken as written. Kanji uses the
     * kana reading. This string is only sent to speech; it is not drawn.
     */
    fun pronunciation(): String {
        if (text.isNotEmpty() && text.all { isKana(it) }) return text
        val kana = kanaReading(reading)
        return kana.ifBlank { text }
    }

    fun headline(language: UiLanguage): String =
        if (reveal == "hidden") meaning(language) else text

    fun subtitle(language: UiLanguage): String {
        val spoken = spokenReading()
        if (reveal == "hidden") return spoken
        val gloss = meaning(language)
        return if (gloss == text || gloss.isBlank()) spoken else "$spoken · $gloss"
    }
}

private fun isKana(ch: Char): Boolean {
    val code = ch.code
    return code in 0x3040..0x30FF || ch == '\u30FC' || ch == '\u3005'
}

/** Keeps the kana in a reading such as "イチ / ひと" and pauses between readings. */
private fun kanaReading(raw: String): String {
    val out = StringBuilder()
    for (ch in raw) {
        when {
            isKana(ch) -> out.append(ch)
            ch == '/' || ch == '・' || ch == '、' || ch == ',' -> {
                if (out.isNotEmpty() && out.last() != '、') out.append('、')
            }
        }
    }
    return out.toString().trim('、')
}

data class CourseLesson(
    val id: String,
    val titleEs: String,
    val titleEn: String,
    val items: List<CourseItem>,
) {
    fun title(language: UiLanguage): String = if (language == UiLanguage.ES) titleEs else titleEn

    fun preview(): String = items.joinToString("  ") { it.text }
}

data class CourseStage(
    val id: String,
    val titleEs: String,
    val titleEn: String,
    val blurbEs: String,
    val blurbEn: String,
    val track: CourseTrack,
    val premium: Boolean,
    val lessons: List<CourseLesson>,
) {
    fun title(language: UiLanguage): String = if (language == UiLanguage.ES) titleEs else titleEn

    fun blurb(language: UiLanguage): String = if (language == UiLanguage.ES) blurbEs else blurbEn
}

data class Course(val stages: List<CourseStage>) {
    fun stage(id: String): CourseStage? = stages.firstOrNull { it.id == id }

    fun lesson(id: String): CourseLesson? = stages.asSequence()
        .flatMap { it.lessons }
        .firstOrNull { it.id == id }

    fun stageOfLesson(lessonId: String): CourseStage? =
        stages.firstOrNull { stage -> stage.lessons.any { it.id == lessonId } }

    fun allItems(): List<CourseItem> = stages.flatMap { stage -> stage.lessons.flatMap { it.items } }

    fun glyphs(): Set<String> = allItems().flatMap { it.glyphs }.toSet()
}

object CourseCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): Course {
        val raw = context.assets.open("writing/curriculum.json").bufferedReader().use { it.readText() }
        return parse(raw)
    }

    fun parse(raw: String): Course {
        val file = json.decodeFromString(CourseFile.serializer(), raw)
        return Course(
            stages = file.stages.map { stage ->
                CourseStage(
                    id = stage.id,
                    titleEs = stage.titleEs,
                    titleEn = stage.titleEn,
                    blurbEs = stage.blurbEs,
                    blurbEn = stage.blurbEn,
                    track = when (stage.track) {
                        "words" -> CourseTrack.Words
                        "kanji" -> CourseTrack.Kanji
                        "phrases" -> CourseTrack.Phrases
                        else -> CourseTrack.Kana
                    },
                    premium = stage.premium,
                    lessons = stage.lessons.map { lesson ->
                        CourseLesson(
                            id = lesson.id,
                            titleEs = lesson.titleEs,
                            titleEn = lesson.titleEn,
                            items = lesson.items.map { item ->
                                CourseItem(
                                    id = item.id,
                                    glyphs = item.glyphs,
                                    reading = item.reading,
                                    meaningEs = item.meaningEs,
                                    meaningEn = item.meaningEn,
                                    reveal = item.reveal,
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    @Serializable
    private data class CourseFile(val stages: List<StageDto> = emptyList())

    @Serializable
    private data class StageDto(
        val id: String,
        val titleEs: String,
        val titleEn: String,
        val blurbEs: String = "",
        val blurbEn: String = "",
        val track: String = "kana",
        val premium: Boolean = false,
        val lessons: List<LessonDto> = emptyList(),
    )

    @Serializable
    private data class LessonDto(
        val id: String,
        val titleEs: String,
        val titleEn: String,
        val items: List<ItemDto> = emptyList(),
    )

    @Serializable
    private data class ItemDto(
        val id: String,
        val glyphs: List<String>,
        val reading: String = "",
        val meaningEs: String = "",
        val meaningEn: String = "",
        val reveal: String = "glyph",
    )
}
