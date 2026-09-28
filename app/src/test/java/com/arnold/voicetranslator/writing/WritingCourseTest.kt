package com.arnold.voicetranslator.writing

import com.arnold.voicetranslator.util.KanaRomaji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class WritingCourseTest {

    private val glyphs: Map<String, List<List<Vec>>> by lazy {
        StrokeRepository.parse(strokesFile().readText())
    }

    @Test
    fun catalogCoversBasicDakutenAndHandakutenInBothScripts() {
        assertEquals(3, KanaCatalog.levels.size)
        assertEquals(15, KanaCatalog.levels[0].lessons.size)
        assertEquals(15, KanaCatalog.levels[1].lessons.size)
        assertEquals(listOf("あいうえお", "かきくけこ", "がぎぐげご"), KanaCatalog.rows.take(3))
        val hiragana = KanaCatalog.level("hiragana").lessons.flatMap { it.characters }
        val katakana = KanaCatalog.level("katakana").lessons.flatMap { it.characters }
        assertEquals(71, hiragana.size)
        assertEquals(71, katakana.size)
        assertTrue(hiragana.containsAll(listOf("が", "ぱ", "ん", "ぢ")))
        assertTrue(katakana.containsAll(listOf("ガ", "パ", "ン", "ヂ")))
        val mixed = KanaCatalog.level("mixed").lessons.first()
        assertEquals(listOf("あ", "ア", "い", "イ", "う", "ウ", "え", "エ", "お", "オ"), mixed.characters)
    }

    @Test
    fun everyCourseCharacterHasKanjiVgStrokesAndRomaji() {
        val missing = KanaCatalog.allCharacters().filter { glyphs[it].isNullOrEmpty() }
        assertTrue("missing strokes: $missing", missing.isEmpty())
        for ((character, strokes) in glyphs) {
            assertTrue(strokes.isNotEmpty())
            for (stroke in strokes) {
                assertTrue(stroke.size >= 2)
                for (point in stroke) {
                    assertTrue(point.x in -8f..117f)
                    assertTrue(point.y in -8f..117f)
                }
            }
            val romaji = KanaRomaji.toRomaji(character)
            assertTrue(romaji.isNotBlank())
            assertTrue(romaji.all { it in 'a'..'z' })
            assertNotEquals(character, romaji)
        }
        assertEquals(142, glyphs.size)
    }

    @Test
    fun perfectAndSlightlyWobblyStrokesAreAccepted() {
        for ((character, strokes) in glyphs) {
            strokes.forEachIndexed { index, stroke ->
                assertEquals("$character stroke $index", StrokeVerdict.Accepted, StrokeMatcher.evaluate(stroke, stroke))
                val noisy = wobble(stroke, body = if (StrokeMatcher.length(stroke) < 18f) 2.5f else 6f, ends = if (StrokeMatcher.length(stroke) < 18f) 2f else 7f)
                assertEquals("$character stroke $index wobble", StrokeVerdict.Accepted, StrokeMatcher.evaluate(noisy, stroke))
            }
        }
    }

    @Test
    fun reversedLongStrokeAndFarScribbleAreRejected() {
        val first = glyphs.getValue("あ")[0]
        assertEquals(StrokeVerdict.WrongDirection, StrokeMatcher.evaluate(first.asReversed(), first))
        val shifted = first.map { Vec(it.x + 40f, it.y) }
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(shifted, first))
        val scribble = listOf(Vec(5f, 5f), Vec(100f, 10f), Vec(10f, 100f), Vec(90f, 90f))
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(scribble, glyphs.getValue("あ")[2]))
    }

    @Test
    fun neighbouringDakutenTicksAreNotInterchangeable() {
        val ga = glyphs.getValue("が")
        assertTrue(ga.size >= 2)
        val tickA = ga[ga.lastIndex - 1]
        val tickB = ga[ga.lastIndex]
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(tickA, tickB))
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(tickB, tickA))
    }

    @Test
    fun handakutenCircleMatchesFromAnotherStartPoint() {
        val circle = glyphs.getValue("ぱ").last()
        assertTrue(StrokeMatcher.dist(circle.first(), circle.last()) < 12f)
        val mid = circle.size / 2
        val rotated = circle.subList(mid, circle.size) + circle.subList(1, mid + 1)
        assertEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(rotated, circle))
        val moved = circle.map { Vec(it.x - 30f, it.y) }
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(moved, circle))
    }

    @Test
    fun lessonsUnlockInOrderAndReviewsPreferMistakes() {
        val level = KanaCatalog.level("hiragana")
        val learned = emptySet<String>()
        assertEquals(LessonStatus.Available, WritingProgress.status(level, 0, learned))
        assertEquals(LessonStatus.Locked, WritingProgress.status(level, 1, learned))

        val afterFirst = setOf(level.lessons[0].id)
        assertEquals(LessonStatus.Learned, WritingProgress.status(level, 0, afterFirst))
        assertEquals(LessonStatus.Available, WritingProgress.status(level, 1, afterFirst))
        assertEquals(LessonStatus.Locked, WritingProgress.status(level, 2, afterFirst))

        val pool = level.lessons[0].characters
        val stats = mapOf(
            pool[0] to CharacterStats(mistakes = 0, lastPracticedAt = 500),
            pool[1] to CharacterStats(mistakes = 4, lastPracticedAt = 900),
            pool[2] to CharacterStats(mistakes = 4, lastPracticedAt = 100),
        )
        val learnedFirst = WritingProgress.withLessonLearned(PersistedProgress(), level.lessons[0].id)
        val queue = WritingProgress.smartReview(
            level = level,
            learned = learnedFirst.learnedLessonIds.toSet(),
            stats = stats,
            limit = 3,
        )
        assertEquals(pool[2], queue[0])
        assertEquals(pool[1], queue[1])
        assertTrue(queue.contains(pool[3]))
        assertEquals(pool, WritingProgress.reviewAll(level, learned).take(pool.size))
    }

    @Test
    fun progressJsonRoundTrips() {
        val original = PersistedProgress(
            learnedLessonIds = listOf("hiragana-1"),
            stats = mapOf("あ" to CharacterStats(mistakes = 2, successes = 1, lastPracticedAt = 42L)),
            writingLessonsEnabled = false,
            introSeen = true,
            listMode = false,
        )
        val decoded = ProgressCodec.decode(ProgressCodec.encode(original))
        assertEquals(original, decoded)
    }

    private fun wobble(points: List<Vec>, body: Float, ends: Float, seed: Int = 3): List<Vec> {
        val random = Random(seed)
        return points.mapIndexed { index, point ->
            val magnitude = if (index == 0 || index == points.lastIndex) ends else body
            Vec(
                point.x + random.nextFloat() * 2f * magnitude - magnitude,
                point.y + random.nextFloat() * 2f * magnitude - magnitude,
            )
        }
    }

    private fun strokesFile(): File {
        val candidates = listOf(
            File("src/main/assets/writing/strokes.json"),
            File("app/src/main/assets/writing/strokes.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("strokes.json not found from ${File(".").absolutePath}")
    }
}
