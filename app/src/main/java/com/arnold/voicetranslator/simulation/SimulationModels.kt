package com.arnold.voicetranslator.simulation

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

/** Language the user wants to practice. The AI always answers in this language. */
enum class SimulationLanguage(
    val id: String,
    val displayName: String,
    val flagEmoji: String,
) {
    JAPANESE(id = "ja", displayName = "Japonés", flagEmoji = "🇯🇵"),
    KOREAN(id = "ko", displayName = "Coreano", flagEmoji = "🇰🇷"),
}

/** Who sent a given [SimulationMessage]. */
enum class SimulationSender { USER, AI }

/**
 * A single chat message in the simulated conversation.
 *
 * - User messages ([sender] == USER) only ever carry [spanishText] — the
 *   golden rule is that the user always writes/speaks in Spanish.
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
