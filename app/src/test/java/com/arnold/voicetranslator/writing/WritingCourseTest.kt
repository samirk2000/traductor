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
    fun curriculumKeepsOnlyBasicHiraganaFree() {
        val free = course.stages.filter { !it.premium }
        assertEquals(listOf("hira-basic"), free.map { it.id })
        val hiragana = course.stage("hira-basic")!!
        assertEquals(46, hiragana.lessons.sumOf { it.items.size })
        assertTrue(course.stages.filter { it.id != "hira-basic" }.all { it.premium })
        assertTrue(course.stage("kata-basic")!!.premium)
        assertTrue(course.stage("kata-dakuten")!!.premium)
        assertTrue(course.stage("youon-kata")!!.premium)
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
    fun moderatelyWobblyAndSlightlyOffStrokesPassTheDefaultTolerance() {
        val stroke = glyphs.getValue("あ")[2]
        val wobbly = wobble(stroke, body = 10f, ends = 8f, seed = 11)
        assertEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(wobbly, stroke, StrokeTolerance.Relaxed))
        val shifted = stroke.map { Vec(it.x + 10f, it.y - 6f) }
        assertEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(shifted, stroke, StrokeTolerance.Relaxed))
        val shortened = StrokeMatcher.portion(stroke, 0.9f)
        assertEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(shortened, stroke, StrokeTolerance.Relaxed))
        val last = stroke.last()
        val prev = stroke[stroke.lastIndex - 1]
        val dx = last.x - prev.x
        val dy = last.y - prev.y
        val span = StrokeMatcher.dist(prev, last).coerceAtLeast(1f)
        val extended = stroke.dropLast(1) + Vec(last.x + dx / span * 12f, last.y + dy / span * 12f)
        assertEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(extended, stroke, StrokeTolerance.Relaxed))
    }

    @Test
    fun pronunciationSpeaksKanaAndDoesNotUseTheWrittenAnswerAsTheRecallPrompt() {
        val kana = CourseItem("a", listOf("あ"), "", "あ", "あ", "glyph")
        assertEquals("あ", kana.pronunciation())
        assertEquals("a", kana.spokenReading())
        val word = CourseItem("mizu", listOf("み", "ず"), "mizu", "agua", "water", "hidden")
        assertEquals("みず", word.pronunciation())
        val kanji = CourseItem("one", listOf("一"), "イチ / ひと", "uno", "one", "glyph")
        assertEquals("イチ、ひと", kanji.pronunciation())
        val phrase = CourseItem("hi", listOf("お", "は", "よ", "う"), "ohayou", "buenos días", "good morning", "hidden")
        assertEquals("おはよう", phrase.pronunciation())
    }

    @Test
    fun relaxedAcceptsAnOffsetStartThatStrictRejects() {
        val stroke = glyphs.getValue("あ").first()
        val shiftedStart = stroke.mapIndexed { index, point ->
            if (index == 0) Vec(point.x + 8f, point.y + 4f) else point
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
    fun wrongDirectionShapeAndPositionAreRejectedAtTheDefaultTolerance() {
        val strokes = glyphs.getValue("あ")
        val tolerance = StrokeTolerance.Relaxed
        assertEquals(
            StrokeVerdict.WrongDirection,
            StrokeMatcher.evaluate(strokes[0].asReversed(), strokes[0], tolerance),
        )

        val loop = strokes[2]
        val straight = listOf(loop.first(), Vec((loop.first().x + loop.last().x) / 2f, (loop.first().y + loop.last().y) / 2f), loop.last())
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(straight, loop, tolerance))
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(strokes[0], strokes[1], tolerance))

        val misplaced = strokes[1].map { Vec(it.x + 28f, it.y - 24f) }
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(misplaced, strokes[1], tolerance))

        val corner = listOf(Vec(8f, 8f), Vec(28f, 12f), Vec(46f, 10f))
        assertNotEquals(StrokeVerdict.Accepted, StrokeMatcher.evaluate(corner, strokes[1], tolerance))
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
    fun finishedKatakanaStaysSavedAndLockedUntilPurchase() {
        val kata = course.stage("kata-basic")!!
        val learnedIds = kata.lessons.map { it.id }
        val progress = learnedIds.fold(PersistedProgress()) { acc, id ->
            WritingProgress.withLessonLearned(acc, id)
        }
        assertEquals(learnedIds, progress.learnedLessonIds)
        val learned = learnedIds.toSet()
        assertEquals(LessonAccess.Premium, WritingProgress.access(kata, 0, learned, premium = false))
        assertFalse(WritingProgress.isPlayable(kata, kata.lessons.first().id, learned, premium = false))
        val now = 1_000L
        assertTrue(
            WritingProgress.dueItems(course, learned, emptyMap(), now, premium = false)
                .none { item -> kata.lessons.any { lesson -> lesson.items.any { it.id == item.id } } },
        )
        assertEquals(learnedIds, progress.learnedLessonIds)
        assertEquals(LessonAccess.Learned, WritingProgress.access(kata, 0, learned, premium = true))
        assertTrue(WritingProgress.isPlayable(kata, kata.lessons.first().id, learned, premium = true))
        assertTrue(
            WritingProgress.dueItems(course, learned, emptyMap(), now, premium = true)
                .any { item -> kata.lessons.any { lesson -> lesson.items.any { it.id == item.id } } },
        )
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
    fun forcePremiumUnlocksWithoutAPurchase() {
        assertTrue(
            Entitlement.isPremium(
                playOwned = false,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = true,
                verifiedAt = 0L,
                forcePremium = true,
            ),
        )
        assertFalse(
            Entitlement.isPremium(
                playOwned = false,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = true,
                verifiedAt = 0L,
                forcePremium = false,
            ),
        )
    }

    @Test
    fun serverVerifiedPremiumExpiresOutsideTheGraceWindow() {
        val now = 10 * Entitlement.PURCHASE_GRACE_MS
        assertFalse(
            Entitlement.isPremium(
                playOwned = true,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = true,
                verifiedAt = 0L,
                now = now,
            ),
        )
        assertTrue(
            Entitlement.isPremium(
                playOwned = true,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = true,
                verifiedAt = now - Entitlement.PURCHASE_GRACE_MS,
                now = now,
            ),
        )
        assertFalse(
            Entitlement.isPremium(
                playOwned = true,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = true,
                verifiedAt = now - Entitlement.PURCHASE_GRACE_MS - 1L,
                now = now,
            ),
        )
        assertFalse(
            Entitlement.isPremium(
                playOwned = false,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = true,
                verifiedAt = now,
                now = now,
            ),
        )
        assertTrue(
            Entitlement.isPremium(
                playOwned = false,
                debugUnlock = true,
                debugBuild = true,
                purchaseCheckRequired = true,
                verifiedAt = 0L,
                now = now,
            ),
        )
    }

    @Test
    fun purchaseVerificationTrustsPlayUntilTheServerIsConfigured() {
        val now = 5_000L
        val stored = PersistedProgress(playPremium = false)

        val fallback = PurchaseVerification.apply(
            current = stored,
            playOwned = true,
            token = "play-token",
            verdict = ServerPurchaseVerdict.NotConfigured,
            now = now,
        )
        assertTrue(fallback.playPremium)
        assertFalse(fallback.purchaseCheckRequired)
        assertEquals("play-token", fallback.purchaseToken)
        assertTrue(
            Entitlement.isPremium(
                fallback.playPremium,
                fallback.debugUnlock,
                debugBuild = false,
                purchaseCheckRequired = fallback.purchaseCheckRequired,
                verifiedAt = fallback.premiumVerifiedAt,
                now = now,
            ),
        )

        val owned = PurchaseVerification.apply(
            current = fallback,
            playOwned = true,
            token = "play-token",
            verdict = ServerPurchaseVerdict.Owned,
            now = now,
        )
        assertTrue(owned.purchaseCheckRequired)
        assertEquals(now, owned.premiumVerifiedAt)

        val offline = PurchaseVerification.apply(
            current = owned,
            playOwned = true,
            token = "play-token",
            verdict = ServerPurchaseVerdict.Unavailable,
            now = now + Entitlement.PURCHASE_GRACE_MS,
        )
        assertTrue(offline.playPremium)
        assertEquals(now, offline.premiumVerifiedAt)

        val expired = PurchaseVerification.apply(
            current = owned,
            playOwned = true,
            token = "play-token",
            verdict = ServerPurchaseVerdict.Unavailable,
            now = now + Entitlement.PURCHASE_GRACE_MS + 1L,
        )
        assertFalse(expired.playPremium)

        val revoked = PurchaseVerification.apply(
            current = owned,
            playOwned = true,
            token = "play-token",
            verdict = ServerPurchaseVerdict.NotOwned,
            now = now,
        )
        assertFalse(revoked.playPremium)
        assertTrue(revoked.purchaseCheckRequired)
        assertEquals(0L, revoked.premiumVerifiedAt)
        assertFalse(
            Entitlement.isPremium(
                playOwned = true,
                debugUnlock = false,
                debugBuild = false,
                purchaseCheckRequired = revoked.purchaseCheckRequired,
                verifiedAt = revoked.premiumVerifiedAt,
                now = now,
            ),
        )

        val cleared = PurchaseVerification.apply(
            current = owned,
            playOwned = false,
            token = null,
            verdict = null,
            now = now,
        )
        assertFalse(cleared.playPremium)
        assertEquals("", cleared.purchaseToken)
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
    fun lessonTracesOnceThenShuffledRecall() {
        val ids = listOf("a", "i", "u", "e", "o")
        val steps = RecallFlow.lessonSteps(ids, beginner = false) { it.reversed() }
        assertEquals(ids.map { RecallStep(it, PracticePhase.Teach) }, steps.take(ids.size))
        val recall = steps.drop(ids.size)
        assertEquals(listOf("o", "e", "u", "i", "a"), recall.map { it.itemId })
        assertTrue(recall.all { it.phase == PracticePhase.Recall })
        assertTrue(steps.none { it.phase == PracticePhase.Fade })
        assertEquals(GuideStyle.Full, RecallFlow.guideStyle(PracticePhase.Teach))
        assertEquals(GuideStyle.None, RecallFlow.guideStyle(PracticePhase.Recall))
        val beginner = RecallFlow.lessonSteps(ids, beginner = true)
        assertEquals(ids.size, beginner.size)
        assertTrue(beginner.all { it.phase == PracticePhase.Teach })
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
            premiumVerifiedAt = 123L,
            purchaseToken = "token",
            purchaseCheckRequired = true,
        )
        val decoded = ProgressCodec.decode(ProgressCodec.encode(original))
        assertEquals(original, decoded)
        val legacy = ProgressCodec.decode("""{"playPremium":true}""")
        assertFalse(legacy.purchaseCheckRequired)
        assertEquals(0L, legacy.premiumVerifiedAt)
        assertEquals("", legacy.purchaseToken)
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
