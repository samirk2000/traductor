package com.arnold.voicetranslator.data.model

import java.util.Locale

/**
 * Central, single source of truth for the 5 languages the app now supports
 * end-to-end (Traductor's Origen/Destino selector): Español, Inglés,
 * Japonés, Coreano, Chino.
 *
 * Unlike [TargetLanguage] (destination-only, no Español, kept for backwards
 * compatibility with the offline/live-conversation/Fraseario/Simulación code
 * paths that still key off it), [Language] can represent BOTH the origin and
 * the destination of a translation — that's what makes ES<->EN, EN<->JA,
 * JA<->ES, etc. possible instead of always assuming the source is Spanish.
 *
 * @param id short code sent to the Worker's `/translate` (`source`/`target`).
 * @param displayName Spanish-language label shown in the UI.
 * @param flagEmoji flag shown next to [displayName] in the selector chips.
 * @param speechTag BCP-47 tag for [android.speech.SpeechRecognizer] (STT).
 * @param ttsTag BCP-47 tag for [android.speech.tts.TextToSpeech] (TTS).
 */
enum class Language(
    val id: String,
    val displayName: String,
    val flagEmoji: String,
    val speechTag: String,
    val ttsTag: String,
) {
    SPANISH(
        id = "es",
        displayName = "Español",
        // Fix: was 🇪🇸 (Spain) — this app is Mexican-made/-focused, so the
        // flag (and speech/TTS locale below) now match "Español mexicano"
        // instead of Spain's.
        flagEmoji = "🇲🇽",
        speechTag = "es-MX",
        ttsTag = "es-MX",
    ),
    ENGLISH(
        id = "en",
        displayName = "Inglés",
        flagEmoji = "🇺🇸",
        speechTag = "en-US",
        ttsTag = "en-US",
    ),
    JAPANESE(
        id = "ja",
        displayName = "Japonés",
        flagEmoji = "🇯🇵",
        speechTag = "ja-JP",
        ttsTag = "ja-JP",
    ),
    KOREAN(
        id = "ko",
        displayName = "Coreano",
        flagEmoji = "🇰🇷",
        speechTag = "ko-KR",
        ttsTag = "ko-KR",
    ),
    CHINESE(
        id = "zh",
        displayName = "Chino",
        flagEmoji = "🇨🇳",
        speechTag = "zh-CN",
        ttsTag = "zh-CN",
    ),
    ;

    /** [Locale] derived from [ttsTag], used by [android.speech.tts.TextToSpeech]. */
    val ttsLocale: Locale get() = Locale.forLanguageTag(ttsTag)

    companion object {
        val DEFAULT_SOURCE: Language = SPANISH
        val DEFAULT_DESTINATION: Language = JAPANESE

        fun fromId(id: String?): Language? = entries.find { it.id == id }
    }
}
