package com.arnold.voicetranslator.data.remote

import android.util.Log
import com.arnold.voicetranslator.BuildConfig
import com.arnold.voicetranslator.data.model.DeepSeekChatRequest
import com.arnold.voicetranslator.data.model.DeepSeekChatResponse
import com.arnold.voicetranslator.data.model.DeepSeekErrorResponse
import com.arnold.voicetranslator.data.model.ChatMessage
import com.arnold.voicetranslator.data.model.ParsedTranslationResponse
import com.arnold.voicetranslator.data.model.ReplySuggestion
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult
import com.arnold.voicetranslator.data.model.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Thin client for the DeepSeek `chat/completions` endpoint using Ktor's CIO
 * engine plus a request/response logger.
 *
 * The HTTP body is read as raw text ([bodyAsText]) and decoded manually (it is
 * intentionally *not* bound to a typed request/response through the client's
 * ContentNegotiation) so that Markdown fences the model sometimes emits do not
 * break deserialization.
 */
class DeepSeekApiClient {

    private val client = HttpClient(CIO) {
        // ContentNegotiation serializes outgoing request bodies (setBody of the
        // DeepSeekChatRequest). The *response* is intentionally read as raw text
        // via bodyAsText() and decoded manually, so Markdown fences from the
        // model never break deserialization.
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                // Serialize default values (model/temperature/maxTokens) so the
                // request body actually includes `model`, which DeepSeek requires.
                encodeDefaults = true
            })
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    android.util.Log.d("DeepSeek", message)
                }
            }
            level = LogLevel.INFO
        }
        // Hard ceiling so a stalled network never hangs the UI forever.
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
        engine {
            maxConnectionsCount = 2
        }
    }

    /**
     * Translates Spanish text into the target language and returns the decoded
     * [TranslationResult].
     *
     * @throws DeepSeekException when the call fails, with a readable message
     *   the UI can surface.
     */
    suspend fun translate(spanishText: String, target: TargetLanguage): TranslationResult {
        val messages = DeepSeekPromptBuilder.buildMessages(spanishText, target)
        return requestTranslation(messages)
    }

    /**
     * Two-Way Conversation translation: the input is either Spanish or
     * (approx) Japanese, and the assistant returns a main translation plus
     * short reply suggestions in Romaji.
     *
     * @param isJapaneseInput true when the recognized speech was Japanese.
     */
    suspend fun translateConversation(
        inputText: String,
        isJapaneseInput: Boolean,
    ): TranslationResult {
        val messages = DeepSeekPromptBuilder.buildConversationMessages(inputText, isJapaneseInput)
        return requestTranslation(messages)
    }

    /** Releases the underlying HTTP client and its connection pool. */
    fun close() {
        runCatching { client.close() }
    }

    private suspend fun requestTranslation(messages: List<ChatMessage>): TranslationResult {
        require(messages.any { it.content.isNotBlank() }) { "Texto de entrada vacío" }
        if (BuildConfig.DEEPSEEK_API_KEY.isBlank()) {
            throw DeepSeekException(
                "No se configuró la API key de DeepSeek. " +
                    "Agrega deepseek.apiKey=TU_CLAVE en local.properties."
            )
        }

        val request = DeepSeekChatRequest(messages = messages)
        Log.d(TAG, "requestTranslation body=${strictJson.encodeToString(request)}")

        val rawText: String = try {
            val response = client.post(ENDPOINT) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
                setBody(request)
            }

            if (response.status.isSuccess()) {
                response.bodyAsText()
            } else {
                // Error envelopes are plain JSON too; read as text so we never
                // throw a serialization error for a non-2xx response.
                val errorBody = response.bodyAsText()
                Log.e(TAG, "requestTranslation non-2xx status=${response.status.value} body=$errorBody")
                val errorEnvelope = runCatching {
                    strictJson.decodeFromString<DeepSeekErrorResponse>(errorBody)
                }.getOrNull()
                throw DeepSeekException(
                    errorEnvelope?.error?.message
                        ?: "DeepSeek respondió con error ${response.status.value}"
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            throw DeepSeekException("La solicitud a DeepSeek agotó el tiempo de espera.")
        } catch (e: DeepSeekException) {
            throw e
        } catch (e: Exception) {
            throw DeepSeekException("No se pudo conectar con DeepSeek: ${e.message}")
        }

        if (rawText.isBlank()) {
            throw DeepSeekException("DeepSeek devolvió una respuesta vacía.")
        }

        Log.d(TAG, "requestTranslation raw response length=${rawText.length}")
        return decodeTranslationResponse(rawText)
    }

    /**
     * Decodes a raw DeepSeek `chat/completions` response body into a
     * [TranslationResult], tolerating Markdown fences around the content JSON.
     *
     * Flow: strip ```json/``` wrappers -> decode the envelope ->
     * extract `choices.first().message.content` -> decode that inner string
     * into [ParsedTranslationResponse] -> normalize into [TranslationResult].
     */
    private fun decodeTranslationResponse(rawText: String): TranslationResult {
        val cleanedText = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val envelope = strictJson.decodeFromString<DeepSeekChatResponse>(cleanedText)
        val content = envelope.choices
            .firstOrNull { !it.message?.content.isNullOrBlank() }
            ?.message?.content
            ?.trim()
            ?: throw DeepSeekException("DeepSeek no devolvió contenido en la respuesta.")

        val cleanedContent = content
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // The model is instructed to return {mainTranslation, alternatives}.
        val parsed = runCatching {
            lenientJson.decodeFromString<ParsedTranslationResponse>(cleanedContent)
        }.getOrNull()

        val replySuggestions = parsed?.replySuggestions.orEmpty()

        return TranslationResult(
            mainTranslation = parsed?.mainTranslation?.takeIf { it.isNotBlank() } ?: cleanedContent,
            alternatives = parsed?.alternatives.orEmpty(),
            replySuggestions = replySuggestions,
        )
    }

    private companion object {
        const val TAG = "DeepSeek"
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"

        // Envelope/error parsing must be strict so a malformed body surfaces as
        // a readable exception instead of silently returning empty content.
        // encodeDefaults is set so an encodeToString(request) log reflects the
        // same body Ktor actually sends (with model included).
        val strictJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * Typed error for any DeepSeek failure, carrying a message safe to display
 * to the end user (in Spanish).
 */
class DeepSeekException(message: String) : Exception(message)
