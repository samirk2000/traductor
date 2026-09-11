package com.arnold.voicetranslator.data.remote

import android.util.Log
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Thin client for the Cloudflare Worker proxy (`/translate`, `/explain`).
 * No API key is ever attached here — the Worker holds the Google/DeepSeek
 * secrets server-side; the client only talks to our own domain.
 *
 * Mirrors [DeepSeekApiClient]'s Ktor setup/conventions for consistency.
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
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 15_000
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

        val rawText = try {
            val response = client.post("$BASE_URL/translate") {
                contentType(ContentType.Application.Json)
                setBody(TranslateRequest(text = text, target = target))
            }
            if (response.status.isSuccess()) {
                response.bodyAsText()
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "translate non-2xx status=${response.status.value} body=$errorBody")
                val error = runCatching {
                    strictJson.decodeFromString<WorkerErrorResponse>(errorBody)
                }.getOrNull()
                if (response.status.value == 429) {
                    throw WorkerApiException(
                        "Se alcanzó el límite diario de traducciones. Intenta más tarde."
                    )
                }
                throw WorkerApiException(
                    error?.error ?: "El servidor de traducción respondió con error ${response.status.value}"
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            throw WorkerApiException("La solicitud al servidor de traducción agotó el tiempo de espera.")
        } catch (e: WorkerApiException) {
            throw e
        } catch (e: Exception) {
            throw WorkerApiException("No se pudo conectar con el servidor de traducción: ${e.message}")
        }

        if (rawText.isBlank()) {
            throw WorkerApiException("El servidor de traducción devolvió una respuesta vacía.")
        }

        val parsed = runCatching {
            strictJson.decodeFromString<TranslateResponse>(rawText)
        }.getOrNull() ?: throw WorkerApiException("Respuesta de traducción inválida.")

        return parsed.mainTranslation
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
