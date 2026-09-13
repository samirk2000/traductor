package com.arnold.voicetranslator.data.remote

import android.util.Log
import com.arnold.voicetranslator.data.model.TranslationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Thin client for the Cloudflare Worker proxy (`/translate`,
 * `/translate-ko-phonetic`, `/converse`, `/explain`). No API key is ever
 * attached here — the Worker holds the Google/DeepSeek secrets server-side;
 * the client only talks to our own domain. This is the ONLY networking
 * client in the app now; every online translation path goes through it.
 */
class WorkerApiClient {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            // 30s (was 20s): DeepSeek could still exceed 20s once the
            // "Cita" scenario's conversation history grew — combined with
            // the shorter SIMULATION_MEMORY_SIZE (4) and trimmed system
            // prompt, 30s gives enough headroom for the occasional slow
            // response instead of surfacing a timeout error.
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        engine {
            // Fix: maxConnectionsCount = 2 was starving the CIO connection
            // pool — after ~2 requests, Cloudflare would silently close the
            // kept-alive TCP connections, but CIO kept trying to reuse the
            // now-dead pooled connection instead of opening a fresh one,
            // hanging every subsequent /simulate call until the 20s timeout
            // ("solo me deja enviar 2 mensajitos, de ahi se traba"; required
            // an app restart to get a brand-new HttpClient/pool). A larger
            // pool + a short keepAliveTime (evict stale connections sooner
            // than Cloudflare does) + connectAttempts > 1 (retry once on a
            // dead-connection failure) fixes it without an app restart.
            maxConnectionsCount = 8
            endpoint.maxConnectionsPerRoute = 8
            endpoint.keepAliveTime = 5_000
            endpoint.connectAttempts = 2
        }
    }

    /**
     * Calls POST /translate on the Worker (Google Cloud Translation under the
     * hood). Fast, no reply suggestions, no alternatives.
     *
     * @param target one of "ja", "ko", "en", "zh", "es" (see [TargetLanguage.id]
     *   / [Language.id]).
     * @param source origin language id, defaults to "es" (the app's original
     *   assumption). Pass "en"/"ja"/etc. for non-Spanish source directions
     *   (e.g. EN -> JA), which the Worker now supports.
     */
    suspend fun translate(text: String, target: String, source: String = "es"): String {
        require(text.isNotBlank()) { "Texto de entrada vacío" }

        val rawText = postAndGetRawText("/translate") {
            client.post("$BASE_URL/translate") {
                contentType(ContentType.Application.Json)
                setBody(TranslateRequest(text = text, target = target, source = source))
            }
        }

        val parsed = runCatching {
            strictJson.decodeFromString<TranslateResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de traducción inválida.")

        return parsed.mainTranslation
    }

    /**
     * Calls POST /translate-ko-phonetic on the Worker (DeepSeek under the
     * hood): Spanish -> Korean phonetic-in-Spanish transliteration, matching
     * the app's "Coreano Fonético" mode.
     */
    suspend fun translateKoreanPhonetic(text: String): TranslationResult {
        val rawText = postAndGetRawText("/translate-ko-phonetic") {
            client.post("$BASE_URL/translate-ko-phonetic") {
                contentType(ContentType.Application.Json)
                setBody(KoreanPhoneticRequest(text = text))
            }
        }
        val parsed = runCatching {
            strictJson.decodeFromString<KoreanPhoneticResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de traducción inválida.")

        return TranslationResult(
            mainTranslation = parsed.mainTranslation.ifBlank { text },
            alternatives = parsed.alternatives,
        )
    }

    /**
     * Calls POST /converse on the Worker (DeepSeek under the hood): translates
     * the foreign speaker's phrase into [meaningLang] and returns reply
     * suggestions, for the Live Conversation mode's THEM turn.
     *
     * @param foreignLang one of "ja", "ko", "en" (see [TargetLanguage.id]).
     * @param meaningLang app-wide UI language ("es"/"en") the translation and
     *   reply suggestions' meaning should come back in — NOT [foreignLang].
     *   Defaults to "es" for backwards compatibility.
     */
    suspend fun converse(text: String, foreignLang: String, meaningLang: String = "es"): TranslationResult {
        val rawText = postAndGetRawText("/converse") {
            client.post("$BASE_URL/converse") {
                contentType(ContentType.Application.Json)
                setBody(ConverseRequest(text = text, foreignLang = foreignLang, meaningLang = meaningLang))
            }
        }
        val parsed = runCatching {
            strictJson.decodeFromString<ConverseResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de conversación inválida.")

        return TranslationResult(
            mainTranslation = parsed.mainTranslation.ifBlank { text },
            replySuggestions = parsed.replySuggestions,
        )
    }

    /**
     * Calls POST /simulate on the Worker (DeepSeek under the hood). Used
     * exclusively by the standalone Simulation Mode
     * ([com.arnold.voicetranslator.simulation]): the AI roleplays as a native
     * speaker of [language] inside [scenario] and replies fully in that
     * language, given the last few turns of [history] and the user's new
     * Spanish [message]. Fully independent from [translate]/[converse]
     * above, which power Live Conversation / Subtitles / Fraseario.
     */
    suspend fun simulate(
        scenario: String,
        language: String,
        history: List<SimulateTurnDto>,
        message: String,
        // App-wide UI language ("es"/"en") the "replySpanish"/"correction"
        // meaning fields should come back in — NOT `language` (the
        // practiced language). Defaults to "es" for backwards compatibility.
        meaningLang: String = "es",
    ): SimulateResponse {
        require(message.isNotBlank()) { "Mensaje de entrada vacío" }

        val rawText = postAndGetRawText(
            routeForLogging = "/simulate",
            // 45s specifically for /simulate (was the shared 30s), plus a
            // backoff retry schedule (try now, then wait 2s and try once
            // more) instead of retrying instantly — DeepSeek being
            // momentarily busy needs a beat before a retry has a real chance
            // of succeeding. Fix: the Worker's response is now trimmed to 4
            // short fields (was 7, incl. 2-3 full suggestion objects) so it
            // should rarely need this whole budget anymore, but the timeout
            // itself is kept generous since a slow DeepSeek moment can still
            // happen regardless of response size.
            retryDelaysMs = listOf(0L, 2_000L),
            timeoutErrorMessage = "Servidor ocupado, toca reintentar.",
        ) {
            client.post("$BASE_URL/simulate") {
                contentType(ContentType.Application.Json)
                setBody(
                    SimulateRequest(
                        scenario = scenario,
                        language = language,
                        history = history,
                        message = message,
                        meaningLang = meaningLang,
                    ),
                )
                timeout {
                    requestTimeoutMillis = 45_000
                    connectTimeoutMillis = 45_000
                    socketTimeoutMillis = 45_000
                }
            }
        }

        return runCatching {
            strictJson.decodeFromString<SimulateResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de simulación inválida.")
    }

    /**
     * Calls POST /suggestions on the Worker (DeepSeek under the hood): 3
     * contextual "what could I say next" options for Simulation Mode.
     * Fetched ONLY on-demand — when the user taps "¿No sabes cómo decirlo?
     * Escríbelo en español" — NOT automatically on every [simulate] turn
     * (see [SimulateResponse]'s fix note for why that used to blow up
     * output tokens/latency).
     */
    suspend fun suggestions(
        scenario: String,
        language: String,
        history: List<SimulateTurnDto>,
        meaningLang: String = "es",
    ): SuggestionsResponse {
        val rawText = postAndGetRawText(
            routeForLogging = "/suggestions",
            timeoutErrorMessage = "No se pudieron generar sugerencias, intenta de nuevo.",
        ) {
            client.post("$BASE_URL/suggestions") {
                contentType(ContentType.Application.Json)
                setBody(
                    SuggestionsRequest(
                        scenario = scenario,
                        language = language,
                        history = history,
                        meaningLang = meaningLang,
                    ),
                )
            }
        }

        return runCatching {
            strictJson.decodeFromString<SuggestionsResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de sugerencias inválida.")
    }

    /**
     * Calls POST /simulate-feedback on the Worker: analyzes the whole
     * Simulation Mode conversation [transcript] and returns a short
     * Spanish-language level assessment for the "Terminar y dar feedback"
     * button.
     */
    suspend fun simulateFeedback(transcript: String): String {
        require(transcript.isNotBlank()) { "Transcripción vacía" }

        val rawText = postAndGetRawText("/simulate-feedback") {
            client.post("$BASE_URL/simulate-feedback") {
                contentType(ContentType.Application.Json)
                setBody(SimulateFeedbackRequest(transcript = transcript))
            }
        }

        val parsed = runCatching {
            strictJson.decodeFromString<SimulateFeedbackResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de feedback inválida.")

        return parsed.feedback
    }

    /**
     * Shared request/error-handling wrapper: runs [request], surfaces 429s and
     * non-2xx bodies as [WorkerApiException], and returns the raw response
     * body text for the caller to decode into its specific response shape.
     *
     * Fix: a timeout (DeepSeek being slow, e.g. on longer scenarios) used to
     * surface immediately as an "API key inválida" style error, confusing
     * for something that's just transient slowness. Now it retries with a
     * backoff schedule ([retryDelaysMs] — e.g. `[0, 2000]` = try now, then
     * wait 2s and try once more) before giving up with [timeoutErrorMessage].
     *
     * @param retryDelaysMs how many attempts to make and how long to wait
     *   (in ms) before each one; defaults to 2 attempts, both immediate,
     *   matching the previous "retry once" behavior for every other route.
     */
    private suspend fun postAndGetRawText(
        routeForLogging: String,
        retryDelaysMs: List<Long> = listOf(0L, 0L),
        timeoutErrorMessage: String = "DeepSeek está lento, reintentando… Intenta de nuevo en un momento.",
        request: suspend () -> HttpResponse,
    ): String {
        var lastTimeout: HttpRequestTimeoutException? = null
        retryDelaysMs.forEachIndexed { attempt, delayMs ->
            if (delayMs > 0) delay(delayMs)
            try {
                val rawText = executeOnce(routeForLogging, request)
                if (rawText.isBlank()) {
                    throw WorkerApiException("El servidor devolvió una respuesta vacía.")
                }
                return rawText
            } catch (e: HttpRequestTimeoutException) {
                lastTimeout = e
                Log.w(
                    TAG,
                    "$routeForLogging timeout on attempt ${attempt + 1}/${retryDelaysMs.size}: " +
                        maskTail(e.message),
                )
            } catch (e: WorkerApiException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$routeForLogging connection error: ${maskTail(e.message)}")
                throw WorkerApiException("Revisa tu internet e intenta de nuevo.")
            }
        }
        Log.e(
            TAG,
            "$routeForLogging exhausted ${retryDelaysMs.size} attempts: ${maskTail(lastTimeout?.message)}",
        )
        throw WorkerApiException(timeoutErrorMessage)
    }

    /** Runs [request] once and either returns the body text or throws [WorkerApiException]/[HttpRequestTimeoutException]. */
    private suspend fun executeOnce(
        routeForLogging: String,
        request: suspend () -> HttpResponse,
    ): String {
        val response = request()
        if (response.status.isSuccess()) return response.bodyAsText()

        val errorBody = response.bodyAsText()
        Log.e(
            TAG,
            "$routeForLogging non-2xx status=${response.status.value} body=${maskTail(errorBody)}",
        )
        val error = runCatching {
            strictJson.decodeFromString<WorkerErrorResponse>(errorBody)
        }.getOrNull()
        throw WorkerApiException(messageForStatus(response.status.value, error?.error))
    }

    /**
     * Maps a non-2xx HTTP status from the Worker into a safe, user-facing
     * Spanish message. 401/403 point at an invalid/missing API key
     * configured server-side (the Worker secrets), 404 at a missing/stale
     * route (Worker not deployed with this endpoint), 429 at the daily rate
     * limit, and everything else falls back to [fallback] or a generic
     * message — never echoing raw response bodies to the UI.
     */
    private fun messageForStatus(status: Int, fallback: String?): String = when (status) {
        // These are the only cases where "API key inválida" is actually
        // accurate (Worker secret misconfigured server-side) — kept as-is.
        401, 403 -> "Revisa tu internet / API key inválida en el servidor."
        404 -> "Servicio no disponible (404). Revisa tu internet / que el servidor esté desplegado."
        429 -> "Se alcanzó el límite diario de traducciones. Intenta más tarde."
        // Fix: this generic fallback used to also say "API key inválida",
        // which was misleading for what's usually just DeepSeek being slow.
        else -> fallback ?: "DeepSeek está lento, reintentando… Intenta de nuevo en un momento."
    }

    /**
     * Logs at most the last 4 characters of [text] (never the full value),
     * so an accidental token/key leaked into a library exception message or
     * error body is never fully written to Logcat.
     */
    private fun maskTail(text: String?): String {
        if (text.isNullOrBlank()) return "<empty>"
        val tail = text.takeLast(4)
        return "…$tail (len=${text.length})"
    }

    /** Releases the underlying HTTP client and its connection pool. */
    fun close() {
        runCatching { client.close() }
    }

    private companion object {
        const val TAG = "WorkerApi"
        const val BASE_URL = "https://voice-translator-proxy.samirblancohernandez.workers.dev"

        val strictJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Typed error for any Worker call failure, carrying a message safe to display in Spanish. */
class WorkerApiException(message: String) : Exception(message)
