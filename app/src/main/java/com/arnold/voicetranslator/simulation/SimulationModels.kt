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

/** Real-life scenario the AI roleplays as a native character in. */
enum class SimulationScenario(
    val id: String,
    val displayName: String,
    val emoji: String,
) {
    STORE(id = "tienda", displayName = "Tienda", emoji = "🛍️"),
    HOTEL(id = "hotel", displayName = "Hotel", emoji = "🏨"),
    RESTAURANT(id = "restaurante", displayName = "Restaurante", emoji = "🍽️"),
    DATE(id = "cita", displayName = "Cita", emoji = "💖"),
    WORK(id = "trabajo", displayName = "Trabajo", emoji = "💼"),
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
    val flagEmoji: String,
    val speechLocaleTag: String,
    val inputHint: String,
) {
    JAPANESE(
        id = "ja",
        displayName = "Japonés",
        flagEmoji = "🇯🇵",
        speechLocaleTag = "ja-JP",
        inputHint = "Escribe o habla en japonés…",
    ),
    KOREAN(
        id = "ko",
        displayName = "Coreano",
        flagEmoji = "🇰🇷",
        speechLocaleTag = "ko-KR",
        inputHint = "Escribe o habla en coreano…",
    ),
    CHINESE(
        id = "zh",
        displayName = "Chino",
        flagEmoji = "🇨🇳",
        speechLocaleTag = "zh-CN",
        inputHint = "Escribe o habla en chino…",
    ),
    ENGLISH(
        id = "en",
        displayName = "Inglés",
        flagEmoji = "🇺🇸",
        speechLocaleTag = "en-US",
        inputHint = "Escribe o habla en inglés…",
    ),
    ;

    /** [Locale] derived from [speechLocaleTag], used by [android.speech.tts.TextToSpeech]. */
    val locale: Locale get() = Locale.forLanguageTag(speechLocaleTag)
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
 *   code). [romanized]/[spanishMeaning] are filled in afterwards
 *   from the Worker's `userRomanized`/`userSpanish` fields (see
 *   [SimulationViewModel.onSendMessage]) so the user's own bubble can show
 *   the same 3-line layout as the AI's.
 * - AI messages always carry all three: [nativeScript] (kana/hangul — shown
 *   large, MUST always be present, never romaji-only), [romanized] (small,
 *   gray, shown below), and [spanishMeaning] (smaller still, shown last).
 */
data class SimulationMessage(
    val id: String,
    val sender: SimulationSender,
    val spanishText: String = "",
    val nativeScript: String = "",
    val romanized: String = "",
    val spanishMeaning: String = "",
)

/** How many past messages (both sides) are kept as context for each new AI reply. */
const val SIMULATION_MEMORY_SIZE = 6

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
    }
