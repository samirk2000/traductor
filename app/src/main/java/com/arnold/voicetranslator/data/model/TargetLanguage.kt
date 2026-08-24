package com.arnold.voicetranslator.data.model

/**
 * Represents the supported translation target languages.
 *
 * - [JAPANESE]: The user speaks Spanish/English, the output is Romaji.
 * - [KOREAN]: The user speaks Spanish/English, the output is Spanish/English
 *   phonetics of Korean.
 * - [ENGLISH]: The user speaks Spanish, the output is English.
 */
enum class TargetLanguage(
    val id: String,
    val displayName: String,
    val flagEmoji: String,
    /** Locale tag used by ML Kit's translator model. */
    val mlKitModelTag: String,
    /** Locale tag used natively for TTS playback and model download. */
    val localeTag: String,
) {
    JAPANESE(
        id = "ja",
        displayName = "Japonés Romaji",
        flagEmoji = "🇯🇵",
        mlKitModelTag = "ja",
        localeTag = "ja",
    ),
    KOREAN(
        id = "ko",
        displayName = "Coreano Fonético",
        flagEmoji = "🇰🇷",
        mlKitModelTag = "ko",
        localeTag = "ko",
    ),
    ENGLISH(
        id = "en",
        displayName = "Inglés",
        flagEmoji = "🇺🇸",
        mlKitModelTag = "en",
        localeTag = "en",
    ),
}
