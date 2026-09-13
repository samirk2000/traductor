package com.arnold.voicetranslator.data.remote

import com.arnold.voicetranslator.data.model.ReplySuggestion
import kotlinx.serialization.Serializable

/** Body for POST /translate against the Cloudflare Worker proxy. */
@Serializable
data class TranslateRequest(
    val text: String,
    val target: String,
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

/** Body for POST /converse against the Cloudflare Worker proxy. */
@Serializable
data class ConverseRequest(
    val text: String,
    val foreignLang: String,
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

/** Body for POST /simulate against the Cloudflare Worker proxy. */
@Serializable
data class SimulateRequest(
    val scenario: String,
    val language: String,
    val history: List<SimulateTurnDto> = emptyList(),
    val message: String,
)

/**
 * Response from POST /simulate: the AI's in-character reply, always fully in
 * the target language ([native]), plus its Latin-script romanization and a
 * short Spanish meaning.
 */
@Serializable
data class SimulateResponse(
    val native: String = "",
    val romanized: String = "",
    val spanish: String = "",
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
