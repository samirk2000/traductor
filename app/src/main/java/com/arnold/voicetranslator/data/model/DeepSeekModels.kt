package com.arnold.voicetranslator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body sent to the DeepSeek `chat/completions` endpoint.
 * See: https://api-docs.deepseek.com
 */
@Serializable
data class DeepSeekChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
    @SerialName("max_tokens") val maxTokens: Int = 512,
)

/**
 * A single message in a chat conversation. DeepSeek uses OpenAI-compatible
 * roles: `system`, `user`, and `assistant`.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * Top-level response envelope returned by the DeepSeek API.
 */
@Serializable
data class DeepSeekChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
)

@Serializable
data class Choice(
    val index: Int? = null,
    val message: ChoiceMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChoiceMessage(
    val role: String? = null,
    val content: String? = null,
)

/**
 * Standard error envelope returned by DeepSeek for non-2xx responses.
 */
@Serializable
data class DeepSeekErrorResponse(
    val error: ApiError? = null,
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
