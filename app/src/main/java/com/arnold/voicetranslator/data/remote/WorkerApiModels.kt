package com.arnold.voicetranslator.data.remote

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

/** Body for POST /explain against the Cloudflare Worker proxy. */
@Serializable
data class ExplainRequest(
    val mode: String, // "converse" | "explain"
    val text: String,
    val foreignLang: String,
)

/** Response from POST /explain: the raw DeepSeek message content (possibly
 *  Markdown-fenced JSON), decoded further by the caller. */
@Serializable
data class ExplainResponse(
    val raw: String = "",
)

/** Error envelope the Worker returns for 4xx/5xx responses, e.g. {"error":"rate_limit_exceeded"}. */
@Serializable
data class WorkerErrorResponse(
    val error: String? = null,
)
