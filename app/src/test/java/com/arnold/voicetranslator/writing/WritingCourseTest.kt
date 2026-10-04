package com.arnold.voicetranslator.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class WritingCourseTest {

    private val glyphs: Map<String, List<List<Vec>>> by lazy {
        StrokeRepository.parse(asset("strokes.json").readText())
    }

    private val course: Course by lazy {
        CourseCatalog.parse(asset("curriculum.json").readText())
    }

    @Test
    fun curriculumKeepsBasicKanaFreeAndLaterStagesPaid() {
        val free = course.stages.filter { !it.premium }
        assertEquals(listOf("hira-basic", "kata-basic"), free.map { it.id })
        assertTrue(course.stages.any { it.id == "kanji-n5" && it.premium && it.track == CourseTrack.Kanji })
        assertTrue(course.stages.any { it.id == "vocab" && it.premium })
        assertTrue(course.stages.any { it.id == "phrases" && it.premium })
        assertTrue(course.stages.any { it.id == "youon" && it.premium })
        val kanji = course.stage("kanji-n5")!!
        assertTrue(kanji.lessons.flatMap { it.items }.size >= 80)
        val vowels = course.stage("hira-basic")!!.lessons.first()
        assertEquals(listOf("あ", "い", "う", "え", "お"), vowels.items.map { it.text })
        val word = course.stage("vocab")!!.lessons.first().items.first()
        assertEquals("hidden", word.reveal)
        assertTrue(word.glyphs.size > 1)
    }

    @Test
    fun everyCourseGlyphHasStrokes() {
        val missing = course.glyphs().filter { glyphs[it].isNullOrEmpty() }
        assertTrue("missing strokes: $missing", missing.isEmpty())
        assertTrue(glyphs.size >= course.glyphs().size)
        assertTrue(glyphs.containsKey("日"))
        assertTrue(glyphs.getValue("日").size >= 4)
        assertTrue(glyphs.containsKey("ー"))
    }

    @Test
    fun perfectAndSlightlyWobblyStrokesAreAccepted() {
        for ((character, strokes) in glyphs) {
            strokes.forEachIndexed { index, stroke ->
                assertEquals(
                    "$character stroke $index",
                    StrokeVerdict.Accepted,
                    StrokeMatcher.evaluate(stroke, stroke, StrokeTolerance.Strict),
                )
                val noisy = wobble(
                    stroke,
                    body = if (StrokeMatcher.length(stroke) < 18f) 2.5f else 6f,
                    ends = if (StrokeMatcher.length(stroke) < 18f) 2f else 7f,
                )
                assertEquals(
                    "$character stroke $index wobble",
                    StrokeVerdict.Accepted,
                    StrokeMatcher.evaluate(noisy, stroke, StrokeTolerance.Relaxed),
                )
            }
        }
    }

    @Test
    fun relaxedAcceptsAnOffsetStartThatStrictRejects() {
        val stroke = glyphs.getValue("あ").first()
        val shiftedStart = stroke.mapIndexed { index, point ->
            if (index == 0) Vec(point.x + 16f, point.y + 8f) else point
        }
        assertEquals(
            StrokeVerdict.Accepted,
            StrokeMatcher.evaluate(shiftedStart, stroke, StrokeTolerance.Relaxed),
        )
        assertNotEquals(
            StrokeVerdict.Accepted,
            StrokeMatcher.evaluate(shiftedStart, stroke, StrokeTolerance.Strict),
        )
    }

    @Test
    fun reversedLongStrokeAndFarScribbleAreRejectedAtEveryTolerance() {
        val first = glyphs.getValue("あ")[0]
        val scribble = listOf(Vec(5f, 5f), Vec(100f, 10f), Vec(10f, 100f), Vec(90f, 90f))
        for (tolerance in StrokeTolerance.entries) {
            assertEquals(StrokeVerdict.WrongDirection, StrokeMatcher.evaluate(first.asReversed(), first, tolerance))
            val shifted = first.map { Vec(it.x + 40f, it.y) }
            assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(shifted, first, tolerance))
            assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(scribble, glyphs.getValue("あ")[2], tolerance))
        }
    }

    @Test
    fun neighbouringDakutenTicksAreNotInterchangeable() {
        val ga = glyphs.getValue("が")
        assertTrue(ga.size >= 2)
        val tickA = ga[ga.lastIndex - 1]
        val tickB = ga[ga.lastIndex]
        for (tolerance in StrokeTolerance.entries) {
            assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(tickA, tickB, tolerance))
            assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(tickB, tickA, tolerance))
        }
    }

    @Test
    fun handakutenCircleMatchesFromAnotherStartPoint() {
        val circle = glyphs.getValue("ぱ").last()
        assertTrue(StrokeMatcher.dist(circle.first(), circle.last()) < 12f)
        val mid = circle.size / 2
        val rotated = circle.subList(mid, circle.size) + circle.subList(1, mid + 1)
        assertEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(rotated, circle, StrokeTolerance.Normal))
        val moved = circle.map { Vec(it.x - 30f, it.y) }
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(moved, circle, StrokeTolerance.Relaxed))
    }

    @Test
    fun lessonsUnlockInOrderAndPremiumStaysClosed() {
        val stage = course.stage("hira-basic")!!
        val learned = emptySet<String>()
        assertEquals(LessonAccess.Open, WritingProgress.access(stage, 0, learned, premium = false))
        assertEquals(LessonAccess.Sequential, WritingProgress.access(stage, 1, learned, premium = false))
        val afterFirst = setOf(stage.lessons[0].id)
        assertEquals(LessonAccess.Learned, WritingProgress.access(stage, 0, afterFirst, premium = false))
        assertEquals(LessonAccess.Open, WritingProgress.access(stage, 1, afterFirst, premium = false))

        val kanji = course.stage("kanji-n5")!!
        assertEquals(LessonAccess.Premium, WritingProgress.access(kanji, 0, emptySet(), premium = false))
        assertEquals(LessonAccess.Open, WritingProgress.access(kanji, 0, emptySet(), premium = true))
        assertFalse(WritingProgress.isPlayable(kanji, kanji.lessons[1].id, emptySet(), premium = true))
    }

    @Test
    fun entitlementIgnoresDebugUnlockOutsideDebugBuilds() {
        assertFalse(Entitlement.isPremium(playOwned = false, debugUnlock = true, debugBuild = false))
        assertTrue(Entitlement.isPremium(playOwned = false, debugUnlock = true, debugBuild = true))
        assertTrue(Entitlement.isPremium(playOwned = true, debugUnlock = false, debugBuild = false))
        assertFalse(Entitlement.canOpen(stagePremium = true, premium = false))
        assertTrue(Entitlement.canOpen(stagePremium = true, premium = true))
        assertTrue(Entitlement.canOpen(stagePremium = false, premium = false))
    }

    @Test
    fun spacedReviewPrefersDueAndMistakes() {
        val stage = course.stage("hira-basic")!!
        val lesson = stage.lessons.first()
        val learned = WritingProgress.withLessonLearned(PersistedProgress(), lesson.id)
        val now = 1_000_000L
        val due = WritingProgress.dueItems(
            course = course,
            learned = learned.learnedLessonIds.toSet(),
            cards = emptyMap(),
            now = now,
            premium = false,
        )
        assertEquals(lesson.items.map { it.id }, due.map { it.id })

        val later = lesson.items.associate { it.id to SrsCard(dueAt = now + WritingProgress.DAY_MS, reps = 1, intervalDays = 1) }
        assertTrue(
            WritingProgress.dueItems(course, learned.learnedLessonIds.toSet(), later, now, premium = false).isEmpty(),
        )

        val stats = mapOf(
            lesson.items[0].id to CharacterStats(mistakes = 1, lastPracticedAt = 50),
            lesson.items[1].id to CharacterStats(mistakes = 4, lastPracticedAt = 80),
        )
        val reinforce = WritingProgress.reinforce(course, learned.learnedLessonIds.toSet(), stats, premium = false, limit = 2)
        assertEquals(lesson.items[1].id, reinforce[0].id)

        val paid = WritingProgress.dueItems(
            course = course,
            learned = setOf(course.stage("kanji-n5")!!.lessons.first().id),
            cards = emptyMap(),
            now = now,
            premium = false,
        )
        assertTrue(paid.isEmpty())
    }

    @Test
    fun lessonMovesFromTeachToFadeToShuffledRecall() {
        val ids = listOf("a", "i", "u", "e", "o")
        val steps = RecallFlow.lessonSteps(ids, beginner = false) { it.reversed() }
        assertEquals(PracticePhase.Teach, steps[0].phase)
        assertEquals("a", steps[0].itemId)
        assertEquals(PracticePhase.Teach, steps[1].phase)
        assertEquals(PracticePhase.Fade, steps[2].phase)
        assertEquals(GuideStyle.Full, RecallFlow.guideStyle(PracticePhase.Teach))
        assertEquals(GuideStyle.Faint, RecallFlow.guideStyle(PracticePhase.Fade))
        assertEquals(GuideStyle.None, RecallFlow.guideStyle(PracticePhase.Recall))
        val recall = steps.filter { it.phase == PracticePhase.Recall }
        assertEquals(listOf("o", "e", "u", "i", "a"), recall.map { it.itemId })
        assertTrue(RecallFlow.lessonSteps(ids, beginner = true).none { it.phase == PracticePhase.Recall || it.phase == PracticePhase.Fade })
        assertTrue(RecallFlow.reviewSteps(ids) { it }.all { it.phase == PracticePhase.Recall })
    }

    @Test
    fun mistakeRequeuesAndLessonNeedsTwoUnaidedSuccesses() {
        val ids = listOf("a", "i")
        var queue = listOf(
            RecallStep("a", PracticePhase.Recall),
            RecallStep("i", PracticePhase.Recall),
        )
        val missed = RecallFlow.afterMiss(queue)
        assertTrue(missed.none { it.phase != PracticePhase.Recall })
        assertEquals("i", missed.first().itemId)
        assertTrue(missed.any { it.itemId == "a" && it.phase == PracticePhase.Recall && it.unaidedStreak == 0 })
        assertEquals(RecallAssist.None, RecallFlow.assistFor(0))
        assertEquals(RecallAssist.None, RecallFlow.assistFor(1))
        assertEquals(RecallAssist.Outline, RecallFlow.assistFor(RecallFlow.HINT_AFTER_MISTAKES))
        assertEquals(RecallAssist.Stroke, RecallFlow.assistFor(RecallFlow.HINT_AFTER_MISTAKES + 1))

        queue = listOf(RecallStep("a", PracticePhase.Recall, unaidedStreak = 0), RecallStep("i", PracticePhase.Recall))
        val once = RecallFlow.afterUnaidedRecall(queue, RecallFlow.UNAIDED_TO_LEARN)
        assertFalse(once.mastered)
        assertTrue(once.queue.any { it.itemId == "a" && it.unaidedStreak == 1 && it.phase == PracticePhase.Recall })
        val secondQueue = listOf(RecallStep("a", PracticePhase.Recall, unaidedStreak = 1))
        val twice = RecallFlow.afterUnaidedRecall(secondQueue, RecallFlow.UNAIDED_TO_LEARN)
        assertTrue(twice.mastered)
        assertTrue(twice.queue.isEmpty())
        assertFalse(RecallFlow.lessonMemorized(ids, setOf("a")))
        assertTrue(RecallFlow.lessonMemorized(ids, setOf("a", "i")))

        val aided = RecallFlow.afterAidedRecall(listOf(RecallStep("a", PracticePhase.Recall, unaidedStreak = 1)))
        assertEquals(0, aided.first().unaidedStreak)
        assertEquals(PracticePhase.Recall, aided.first().phase)

        val lapsed = WritingProgress.lapse(SrsCard(intervalDays = 6, reps = 4, ease = 2.5f), now = 5_000L)
        assertEquals(0, lapsed.reps)
        assertEquals(0, lapsed.intervalDays)
        assertEquals(5_000L + WritingProgress.SHORT_INTERVAL_MS, lapsed.dueAt)
        assertTrue(lapsed.dueAt - 5_000L < WritingProgress.DAY_MS)
    }

    @Test
    fun progressJsonRoundTripsWithToleranceAndUnlockFlags() {
        val original = PersistedProgress(
            learnedLessonIds = listOf("hira-basic-1"),
            stats = mapOf("あ" to CharacterStats(mistakes = 2, successes = 1, lastPracticedAt = 42L)),
            srs = mapOf("あ" to SrsCard(ease = 2.3f, intervalDays = 3, dueAt = 99L, reps = 2)),
            writingLessonsEnabled = false,
            introSeen = true,
            tolerance = StrokeTolerance.Strict.name,
            playPremium = true,
            debugUnlock = false,
        )
        val decoded = ProgressCodec.decode(ProgressCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(StrokeTolerance.Relaxed, WritingProgress.toleranceOf(PersistedProgress()))
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

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/writing/$name"),
            File("app/src/main/assets/writing/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
