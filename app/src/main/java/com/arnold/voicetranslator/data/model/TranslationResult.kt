package com.arnold.voicetranslator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Normalized translation result shared by both the online (DeepSeek) and
 * offline (ML Kit) translation paths, so the UI layer only ever deals with one
 * shape.
 *
 * @param mainTranslation the primary rendered translation.
 * @param alternatives optional list of alternative phrasings (may be empty when
 *   the raw model only produced a single outcome).
 */
data class TranslationResult(
    val mainTranslation: String,
    val alternatives: List<String> = emptyList(),
    /** Structured Romaji reply suggestions (each with its Spanish meaning). */
    val replySuggestions: List<ReplySuggestion> = emptyList(),
    /**
     * For Japanese targets: the native kana/kanji script behind
     * [mainTranslation] (which itself holds the Romaji), so native speakers
     * can read it too. Null for other targets or when unavailable. Kept
     * separate from [alternatives], which has its own unrelated UI meaning
     * (alternative phrasings shown in the standard translate screen).
     */
    val nativeScript: String? = null,
)

/**
 * A suggested reply shown in "Escuchar Japonés" mode: the [romaji] phrase is
 * displayed on top and its [spanish] meaning below, so the traveler can see
 * exactly what they would say before tapping to have it read aloud. [kana]
 * holds the same reply written in Japanese kana/kanji, used when the local
 * speaker wants to read it (shown optionally via a UI toggle).
 */
@Serializable
data class ReplySuggestion(
    @SerialName("romaji") val romaji: String,
    @SerialName("spanish") val spanish: String = "",
    @SerialName("kana") val kana: String = "",
)

/**
 * The strict JSON shape DeepSeek is instructed to return. Mirrors exactly the
 * keys described in the dynamic system prompt.
 */
@Serializable
data class ParsedTranslationResponse(
    @SerialName("mainTranslation") val mainTranslation: String = "",
    @SerialName("alternatives") val alternatives: List<String> = emptyList(),
    /** Structured "romaji + spanish meaning" reply cards in conversation mode. */
    @SerialName("replySuggestions") val replySuggestions: List<ReplySuggestion> = emptyList(),
)

/**
 * A robust JSON serializer tolerant to slightly malformed model output.
 * It ignores unknown keys (so the parsed object never crashes) and fails
 * *softly* (returns an empty default) when the model emits Markdown fences.
 */
val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Strips Markdown code fences (```json ... ```) that language models sometimes
 * wrap their output in, then parses the remaining JSON into a
 * [ParsedTranslationResponse]. On any parse failure it falls back to treating
 * the entire raw text as the main translation.
 */
fun parseTranslationResponse(raw: String): ParsedTranslationResponse {
    val cleaned = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    return runCatching { lenientJson.decodeFromString<ParsedTranslationResponse>(cleaned) }
        .getOrElse {
            ParsedTranslationResponse(
                mainTranslation = cleaned.ifBlank { raw },
                alternatives = emptyList(),
            )
        }
}
