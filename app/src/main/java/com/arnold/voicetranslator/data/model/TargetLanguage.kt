package com.arnold.voicetranslator.data.model

/**
 * Represents the two supported translation target languages.
 *
 * - [JAPANESE]: The user speaks Spanish, the output is Romaji (Romanized Japanese).
 * - [KOREAN]: The user speaks Spanish, the output is Spanish-phonetic pronunciation of Korean.
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
}
