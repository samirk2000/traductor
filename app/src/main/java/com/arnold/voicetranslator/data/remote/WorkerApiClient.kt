package com.arnold.voicetranslator.data.remote

import android.util.Log
import com.arnold.voicetranslator.data.model.TranslationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
            // 20s (was 15s): /simulate with the "Cita" scenario's longer,
            // more elaborate system prompt was occasionally exceeding 15s.
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        engine {
            maxConnectionsCount = 2
        }
    }

    /**
     * Calls POST /translate on the Worker (Google Cloud Translation under the
     * hood). Fast, no reply suggestions, no alternatives.
     *
     * @param target one of "ja", "ko", "en" (see [TargetLanguage.id]).
     */
    suspend fun translate(text: String, target: String): String {
        require(text.isNotBlank()) { "Texto de entrada vacío" }

        val rawText = postAndGetRawText("/translate") {
            client.post("$BASE_URL/translate") {
                contentType(ContentType.Application.Json)
                setBody(TranslateRequest(text = text, target = target))
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
     * the foreign speaker's phrase into Mexican Spanish and returns reply
     * suggestions, for the Live Conversation mode's THEM turn.
     *
     * @param foreignLang one of "ja", "ko", "en" (see [TargetLanguage.id]).
     */
    suspend fun converse(text: String, foreignLang: String): TranslationResult {
        val rawText = postAndGetRawText("/converse") {
            client.post("$BASE_URL/converse") {
                contentType(ContentType.Application.Json)
                setBody(ConverseRequest(text = text, foreignLang = foreignLang))
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
    ): SimulateResponse {
        require(message.isNotBlank()) { "Mensaje de entrada vacío" }

        val rawText = postAndGetRawText("/simulate") {
            client.post("$BASE_URL/simulate") {
                contentType(ContentType.Application.Json)
                setBody(
                    SimulateRequest(
                        scenario = scenario,
                        language = language,
                        history = history,
                        message = message,
                    ),
                )
            }
        }

        return runCatching {
            strictJson.decodeFromString<SimulateResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de simulación inválida.")
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
     */
    private suspend fun postAndGetRawText(
        routeForLogging: String,
        request: suspend () -> HttpResponse,
    ): String {
        val rawText = try {
            val response = request()
            if (response.status.isSuccess()) {
                response.bodyAsText()
            } else {
                val errorBody = response.bodyAsText()
                Log.e(
                    TAG,
                    "$routeForLogging non-2xx status=${response.status.value} " +
                        "body=${maskTail(errorBody)}",
                )
                val error = runCatching {
                    strictJson.decodeFromString<WorkerErrorResponse>(errorBody)
                }.getOrNull()
                throw WorkerApiException(messageForStatus(response.status.value, error?.error))
            }
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "$routeForLogging timeout: ${maskTail(e.message)}")
            throw WorkerApiException(
                "La solicitud tardó demasiado. Revisa tu internet / API key inválida e intenta de nuevo."
            )
        } catch (e: WorkerApiException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$routeForLogging connection error: ${maskTail(e.message)}")
            throw WorkerApiException("Revisa tu internet / API key inválida e intenta de nuevo.")
        }

        if (rawText.isBlank()) {
            throw WorkerApiException("El servidor devolvió una respuesta vacía.")
        }
        return rawText
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
        401, 403 -> "Revisa tu internet / API key inválida en el servidor."
        404 -> "Servicio no disponible (404). Revisa tu internet / que el servidor esté desplegado."
        429 -> "Se alcanzó el límite diario de traducciones. Intenta más tarde."
        else -> fallback ?: "Revisa tu internet / API key inválida. (Error $status)"
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
