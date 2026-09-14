package com.arnold.voicetranslator.simulation

import java.util.Locale

/**
 * Standalone data models for "Modo Simulación" (Simulation Mode).
 *
 * Intentionally fully independent from the rest of the app: nothing here is
 * shared with [com.arnold.voicetranslator.data.model.TargetLanguage],
 * [com.arnold.voicetranslator.ui.state.TranslatorUiState], or any Live
 * Conversation / Subtitles / Fraseario code, so this module can evolve (or be
 * removed) without ever touching those features.
 */

/**
 * Real-life scenario the AI roleplays as a native character in.
 *
 * @param displayNameEn English label — used when [SimulationLanguage.ENGLISH]
 *   is the language being practiced, so the whole screen switches to English
 *   for that immersion mode instead of staying in Spanish regardless.
 */
enum class SimulationScenario(
    val id: String,
    val displayName: String,
    val displayNameEn: String,
    val emoji: String,
) {
    STORE(id = "tienda", displayName = "Tienda", displayNameEn = "Store", emoji = "🛍️"),
    HOTEL(id = "hotel", displayName = "Hotel", displayNameEn = "Hotel", emoji = "🏨"),
    RESTAURANT(id = "restaurante", displayName = "Restaurante", displayNameEn = "Restaurant", emoji = "🍽️"),
    DATE(id = "cita", displayName = "Cita", displayNameEn = "Date", emoji = "💖"),
    WORK(id = "trabajo", displayName = "Trabajo", displayNameEn = "Work", emoji = "💼"),
    /**
     * Free-form, low-stakes chat with a friend/acquaintance — no
     * transactional goal (no menu, no checkout, no interview), so the AI
     * roleplays a casual friend making small talk, asking about the user's
     * day/hobbies/plans, instead of the scripted service-encounter flow the
     * other scenarios have.
     */
    CASUAL(id = "casual", displayName = "Casual", displayNameEn = "Casual", emoji = "💬"),
    ;

    /** Picks [displayName] or [displayNameEn] depending on the UI language. */
    fun label(englishUi: Boolean): String = if (englishUi) displayNameEn else displayName
}

/**
 * Language the user wants to practice. The AI always answers in this
 * language, and — since the STT/TTS fix — the user's own turn (mic input
 * and text field hint) is now in this same language too, not fixed Spanish.
 *
 * @param speechLocaleTag BCP-47 tag passed to [android.speech.SpeechRecognizer]
 *   (via [com.arnold.voicetranslator.audio.SpeechRecognitionManager.speechLanguage])
 *   and used to derive [locale] for TTS. Must change whenever the user taps a
 *   different language chip — see [SimulationViewModel.onSelectLanguage].
 * @param inputHint placeholder shown in the text field / empty-chat state so
 *   the user knows which language to type/speak in.
 */
enum class SimulationLanguage(
    val id: String,
    val displayName: String,
    val displayNameEn: String,
    val flagEmoji: String,
    val speechLocaleTag: String,
    val inputHint: String,
    val inputHintEn: String,
) {
    JAPANESE(
        id = "ja",
        displayName = "Japonés",
        displayNameEn = "Japanese",
        flagEmoji = "🇯🇵",
        speechLocaleTag = "ja-JP",
        inputHint = "Escribe o habla en japonés…",
        inputHintEn = "Write or speak in Japanese…",
    ),
    KOREAN(
        id = "ko",
        displayName = "Coreano",
        displayNameEn = "Korean",
        flagEmoji = "🇰🇷",
        speechLocaleTag = "ko-KR",
        inputHint = "Escribe o habla en coreano…",
        inputHintEn = "Write or speak in Korean…",
    ),
    CHINESE(
        id = "zh",
        displayName = "Chino",
        displayNameEn = "Chinese",
        flagEmoji = "🇨🇳",
        speechLocaleTag = "zh-CN",
        inputHint = "Escribe o habla en chino…",
        inputHintEn = "Write or speak in Chinese…",
    ),
    // Fix: selecting English as the practiced language now flips the whole
    // Simulation Mode screen's own UI copy to English too (an "immersion"
    // mode for English speakers) — every other language keeps the app's
    // native Spanish/Mexican UI. See SimulationMode.kt's `englishUi` flag.
    ENGLISH(
        id = "en",
        displayName = "Inglés",
        displayNameEn = "English",
        flagEmoji = "🇺🇸",
        speechLocaleTag = "en-US",
        inputHint = "Escribe o habla en inglés…",
        inputHintEn = "Write or speak in English…",
    ),
    ;

    /** [Locale] derived from [speechLocaleTag], used by [android.speech.tts.TextToSpeech]. */
    val locale: Locale get() = Locale.forLanguageTag(speechLocaleTag)

    /** Picks [displayName] or [displayNameEn] depending on the UI language. */
    fun label(englishUi: Boolean): String = if (englishUi) displayNameEn else displayName

    /** Picks [inputHint] or [inputHintEn] depending on the UI language. */
    fun hint(englishUi: Boolean): String = if (englishUi) inputHintEn else inputHint
}

/** Who sent a given [SimulationMessage]. */
enum class SimulationSender { USER, AI }

/**
 * A single chat message in the simulated conversation.
 *
 * - User messages ([sender] == USER) carry [spanishText] with whatever the
 *   user actually typed/said — which, since the STT/hint fix, is normally in
 *   the practiced language (ja/ko/zh/en), not necessarily Spanish despite the
 *   field's name (kept for backwards compat with the history/transcript
 *   code). [nativeScript]/[romanized]/[spanishMeaning] are back-filled from
 *   the Worker's `userTranscription`/`userRomaji`/`userSpanish` fields (see
 *   [SimulationViewModel.dispatchToBackend]) so the user's own bubble shows
 *   romaji + a translation, not just the raw native-script text — otherwise
 *   a beginner has no way to verify whether what they said/typed actually
 *   means what they intended.
 * - AI messages always carry all three: [nativeScript] (kana/hangul — shown
 *   large, MUST always be present, never romaji-only), [romanized] (small,
 *   gray, shown below), and [spanishMeaning] (smaller still, shown last).
 * - [isCorrect]/[correctionNative]/[correctionSpanish] are only ever set on
 *   USER messages (from the same /simulate response, via `isUserCorrect`/
 *   `correctionNative`/`correctionSpanish`) — when [isCorrect] is false, the
 *   bubble shows a small "💡 Mejor: correctionNative (correctionSpanish)"
 *   hint below the 3 normal lines. [correctionSpanish] is ALWAYS in the
 *   app's UI language, never the practiced language — see
 *   [com.arnold.voicetranslator.data.remote.SimulateResponse]'s fix note.
 */
data class SimulationMessage(
    val id: String,
    val sender: SimulationSender,
    val spanishText: String = "",
    val nativeScript: String = "",
    val romanized: String = "",
    val spanishMeaning: String = "",
    val isCorrect: Boolean = true,
    val correctionNative: String = "",
    val correctionSpanish: String = "",
)

/**
 * How many past messages (both sides) are kept as context for each new AI
 * reply. Fix: was 6 — with the "Cita" scenario's longer, more elaborate
 * replies, the growing history (+ system prompt) pushed DeepSeek past the
 * client timeout around message 4-5. Lowered to 4 to keep each /simulate
 * call fast regardless of how long the conversation gets.
 */
const val SIMULATION_MEMORY_SIZE = 4

/**
 * Hardcoded, per-scenario starter suggestions shown under the chat for
 * beginners who don't know what to say next ("Sugerencias: ..."). No AI call
 * needed — purely a static UI hint the user can tap to prefill the input.
 */
val SimulationScenario.starterSuggestions: List<String>
    get() = when (this) {
        SimulationScenario.STORE -> listOf(
            "¿Cuánto cuesta esto?",
            "¿Tiene esto en otro color?",
            "Solo estoy viendo, gracias",
        )
        SimulationScenario.HOTEL -> listOf(
            "Tengo una reservación a mi nombre",
            "¿A qué hora es el check-out?",
            "¿Me puede dar otra habitación?",
        )
        SimulationScenario.RESTAURANT -> listOf(
            "¿Qué me recomienda?",
            "La cuenta, por favor",
            "¿Tienen opciones vegetarianas?",
        )
        SimulationScenario.DATE -> listOf(
            "Me gustas también",
            "¿Cómo te llamas?",
            "¿Quieres salir otra vez?",
        )
        SimulationScenario.WORK -> listOf(
            "Cuénteme sobre el puesto",
            "¿Cuál es el horario?",
            "Tengo experiencia en esta área",
        )
        SimulationScenario.CASUAL -> listOf(
            "¿Qué has hecho hoy?",
            "¿Tienes planes para el fin de semana?",
            "Cuéntame de ti",
        )
    }
