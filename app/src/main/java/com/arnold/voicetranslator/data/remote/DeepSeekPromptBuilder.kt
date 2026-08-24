package com.arnold.voicetranslator.data.remote

import com.arnold.voicetranslator.data.model.ChatMessage
import com.arnold.voicetranslator.data.model.TargetLanguage

/**
 * Builds the dynamic system prompt and user message for the DeepSeek request
 * based on the active [TargetLanguage]. The model is strictly instructed to
 * emit a single JSON object with the exact keys the app parses.
 */
object DeepSeekPromptBuilder {

    fun buildMessages(spanishText: String, target: TargetLanguage): List<ChatMessage> {
        val systemPrompt = when (target) {
            TargetLanguage.JAPANESE -> SYSTEM_JAPANESE
            TargetLanguage.KOREAN -> SYSTEM_KOREAN
        }
        val userPrompt = "Traduce al ${
            when (target) {
                TargetLanguage.JAPANESE -> "japonés romaji"
                TargetLanguage.KOREAN -> "coreano fonético en español"
            }
        }: $spanishText"

        return listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt),
        )
    }

    /**
     * Builds the messages for the Two-Way Conversation mode.
     *
     * When [isJapaneseInput] is true the user is being spoken to in Japanese,
     * so the assistant must translate into Mexican Spanish (as the main text)
     * and additionally propose 2-4 short reply suggestions in Romaji that the
     * local user can tap to answer back in Japanese.
     */
    fun buildConversationMessages(inputText: String, isJapaneseInput: Boolean): List<ChatMessage> {
        val systemPrompt =
            if (isJapaneseInput) SYSTEM_JAPANESE_INPUT else SYSTEM_SPANISH_INPUT
        val directionLabel = if (isJapaneseInput) "japonés" else "español"
        val dialogueLabel = if (isJapaneseInput) "Español" else "Japonés"
        val userPrompt =
            "Diálogo $dialogueLabel: " +
                "Traduce y responde siguiendo el sistema. " +
                "Entrada en $directionLabel: \"$inputText\""

        return listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt),
        )
    }

    private const val SYSTEM_JAPANESE =
        "You are an expert, culturally-aware real-time interpreter for a Mexican traveler in Japan. " +
            "Translate the input Spanish (which may contain Mexican slang/idioms) into natural, " +
            "conversational, spoken Japanese expressed strictly in Romaji. " +
            "Avoid overly rigid or robotic textbook phrasing; use natural everyday expressions spoken in Japan. " +
            "Return JSON ONLY with format: " +
            "{\"mainTranslation\": \"Most natural Romaji response\", \"alternatives\": [\"Alternative Romaji 1\", \"Alternative Romaji 2\"]}. " +
            "Do not output Kanji, Hiragana, Katakana, explanations, or quotes."

    private const val SYSTEM_KOREAN =
        "Eres un traductor Español -> Coreano con pronunciación fonética en español. " +
            "SIEMPRE responde ÚNICAMENTE con un objeto JSON válido, sin texto adicional, " +
            "sin Markdown ni comillas finales. Formato estricto: " +
            "{\"mainTranslation\":\"pronunciación fonética\",\"alternatives\":[\"alternativa 1\",\"alternativa 2\"]}. " +
            "Reglas: mainTranslation y cada alternativa SON la pronunciación aproximada en sílabas " +
            "españolas separadas por guiones (ej. \"An-nyeong-ha-se-yo\"). Nada de hangul, " +
            "nada de definiciones, nada de explicaciones gramaticales. " +
            "Alternatives debe tener entre 1 y 3 pronunciaciones alternativas naturales."

    private const val SYSTEM_JAPANESE_INPUT =
        "You are an expert real-time interpreter for a Mexican traveler listening to a native Japanese speaker. " +
            "Translate the input Japanese (spoken or Romaji) into natural Mexican Spanish, capturing the exact intent, " +
            "tone, and politeness level. " +
            "Also generate 2 short, natural, highly appropriate response suggestions the traveler can reply with. " +
            "Each suggestion must be an object with two fields: \"romaji\" (the reply expressed strictly in Romaji, " +
            "with no Kanji/Kana) and \"spanish\" (its short meaning in Spanish). " +
            "Return JSON ONLY with format: " +
            "{\"mainTranslation\": \"Traducción natural al español mexicano\", " +
            "\"replySuggestions\": [{\"romaji\": \"Respuesta en Romaji 1\", \"spanish\": \"Significado 1\"}, " +
            "{\"romaji\": \"Respuesta en Romaji 2\", \"spanish\": \"Significado 2\"}]}. " +
            "Do not output Kanji, Hiragana, Katakana, explanations, or quotes."

    private const val SYSTEM_SPANISH_INPUT =
        "You are an expert, culturally-aware real-time interpreter for a Mexican traveler in Japan. " +
            "Translate the input Spanish (which may contain Mexican slang/idioms) into natural, " +
            "conversational, spoken Japanese expressed strictly in Romaji. " +
            "Avoid overly rigid or robotic textbook phrasing; use natural everyday expressions spoken in Japan. " +
            "Return JSON ONLY with format: " +
            "{\"mainTranslation\": \"Most natural Romaji response\", \"alternatives\": [\"Alternative Romaji 1\", \"Alternative Romaji 2\"]}. " +
            "Do not output Kanji, Hiragana, Katakana, explanations, or quotes."
}
