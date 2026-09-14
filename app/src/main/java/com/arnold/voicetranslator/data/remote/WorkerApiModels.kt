package com.arnold.voicetranslator.data.remote

import com.arnold.voicetranslator.data.model.ReplySuggestion
import kotlinx.serialization.Serializable

/**
 * Body for POST /translate against the Cloudflare Worker proxy.
 *
 * @param source origin language id (see Language.id: "es"/"en"/"ja"/"ko"/"zh").
 *   Defaults to "es" for backwards compatibility with the app's original
 *   Spanish-only translate flow; pass a different value to translate FROM
 *   English/Japanese/etc. (e.g. EN -> JA in the Traductor).
 */
@Serializable
data class TranslateRequest(
    val text: String,
    val target: String,
    val source: String = "es",
)

/**
 * Response from POST /translate. Only `mainTranslation` is defined by the
 * Worker today; `ignoreUnknownKeys`/lenient decoding on the client means any
 * additional fields the Worker adds later are simply ignored here until a
 * corresponding property is added.
 */
@Serializable
data class TranslateResponse(
    val mainTranslation: String = "",
)

/** Body for POST /translate-ko-phonetic against the Cloudflare Worker proxy. */
@Serializable
data class KoreanPhoneticRequest(
    val text: String,
)

/** Response from POST /translate-ko-phonetic (already parsed server-side). */
@Serializable
data class KoreanPhoneticResponse(
    val mainTranslation: String = "",
    val alternatives: List<String> = emptyList(),
)

/**
 * Body for POST /converse against the Cloudflare Worker proxy.
 *
 * @param meaningLang language the returned `mainTranslation`/reply
 *   suggestions' "spanish" (meaning) field comes back in ("es"/"en").
 *   Defaults to "es" for backwards compatibility; the app now passes the
 *   app-wide UI language ([com.arnold.voicetranslator.ui.localization.UiLanguage])
 *   so "Listen Japanese"/Live mode show the meaning in English when the
 *   app's Origen selector is English, not hardcoded Spanish.
 */
@Serializable
data class ConverseRequest(
    val text: String,
    val foreignLang: String,
    val meaningLang: String = "es",
)

/** Response from POST /converse (already parsed server-side): translation +
 *  reply suggestions for the Live Conversation mode's THEM turn. */
@Serializable
data class ConverseResponse(
    val mainTranslation: String = "",
    val replySuggestions: List<ReplySuggestion> = emptyList(),
)

/** Body for POST /explain against the Cloudflare Worker proxy (reserved for a
 *  future "explicar" cultural-nuance button; not wired into the UI yet). */
@Serializable
data class ExplainRequest(
    val text: String,
    val foreignLang: String,
)

/** Response from POST /explain (already parsed server-side). */
@Serializable
data class ExplainResponse(
    val explanation: String = "",
)

/** Error envelope the Worker returns for 4xx/5xx responses, e.g. {"error":"rate_limit_exceeded"}. */
@Serializable
data class WorkerErrorResponse(
    val error: String? = null,
)

// ===========================================================================
// Simulation Mode ("Modo Simulación") — fully additive, used only by
// com.arnold.voicetranslator.simulation.*. Does not affect /translate,
// /translate-ko-phonetic, /converse or /explain (Live Conversation,
// Subtitles, Fraseario), which stay untouched.
// ===========================================================================

/** One turn of prior context sent to POST /simulate (last 6 messages). */
@Serializable
data class SimulateTurnDto(
    val role: String,
    val text: String,
)

/**
 * Body for POST /simulate against the Cloudflare Worker proxy.
 *
 * @param meaningLang language the "replySpanish"/"correction" meaning
 *   fields come back in ("es"/"en"), driven by the app-wide UI language
 *   ([com.arnold.voicetranslator.ui.localization.UiLanguage]) — NOT the
 *   practiced [SimulationLanguage]. Defaults to "es" for backwards
 *   compatibility.
 */
@Serializable
data class SimulateRequest(
    val scenario: String,
    val language: String,
    val history: List<SimulateTurnDto> = emptyList(),
    val message: String,
    val meaningLang: String = "es",
)

/**
 * One suggested way the practicing user could reply next. [native] is what
 * actually gets sent if tapped; [romanized]/[spanish] are what the chip
 * displays. Now fetched via the separate, on-demand POST /suggestions (see
 * [SuggestionsResponse]) — NOT inside every /simulate response anymore.
 */
@Serializable
data class SimulateSuggestionDto(
    val native: String = "",
    val romanized: String = "",
    val spanish: String = "",
)

/**
 * Response from POST /simulate: the AI's in-character reply
 * ([replyNative]/[replyRomaji]/[replySpanish]), PLUS the user's own last
 * message echoed back in 3 forms ([userTranscription]/[userRomaji]/
 * [userSpanish]) so their own bubble can show romaji + a translation
 * instead of just the raw native-script text they typed/said. [isUserCorrect]
 * flags whether that message was correct target-language usage; when
 * `false`, [correctionNative] is how to actually say it (native script,
 * genuinely useful to *read*) and [correctionSpanish] explains why, ALWAYS
 * in the app's UI language (`meaningLang`), never in the practiced language
 * — a beginner can't understand a correction written in the language
 * they're still learning.
 *
 * Fix: an earlier version of this endpoint replaced the whole
 * userNative/userRomanized/userSpanish trio with a single "correction"
 * field (in the meaning language) to cut tokens — but that left the user's
 * OWN bubble with no romaji/translation at all, and displayed corrections
 * with no native-script form to actually copy/read. Restored the user-side
 * 3-form breakdown, but with the correction properly split into a native
 * (readable) form and an ALWAYS-meaning-language explanation, instead of
 * ever mixing native script into the explanation.
 */
@Serializable
data class SimulateResponse(
    val userTranscription: String = "",
    val userRomaji: String = "",
    val userSpanish: String = "",
    val isUserCorrect: Boolean = true,
    val correctionNative: String = "",
    val correctionSpanish: String = "",
    val replyNative: String = "",
    val replyRomaji: String = "",
    val replySpanish: String = "",
)

/** Body for POST /suggestions against the Cloudflare Worker proxy. */
@Serializable
data class SuggestionsRequest(
    val scenario: String,
    val language: String,
    val history: List<SimulateTurnDto> = emptyList(),
    val meaningLang: String = "es",
)

/**
 * Response from POST /suggestions: 3 contextual "what could I say next"
 * options — fetched ONLY when the user taps "¿No sabes cómo decirlo?
 * Escríbelo en español" in Simulation Mode, not on every /simulate turn.
 */
@Serializable
data class SuggestionsResponse(
    val suggestions: List<SimulateSuggestionDto> = emptyList(),
)

/** Body for POST /simulate-feedback. */
@Serializable
data class SimulateFeedbackRequest(
    val transcript: String,
)

/** Response from POST /simulate-feedback: a short Spanish-language level assessment. */
@Serializable
data class SimulateFeedbackResponse(
    val feedback: String = "",
)
