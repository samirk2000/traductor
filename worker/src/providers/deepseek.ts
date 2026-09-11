export type ExplainMode = 'converse' | 'explain'

/**
 * "converse" mode: used for every THEM turn in the app's Live Conversation
 * mode. Translates the foreign speaker's phrase into Mexican Spanish and
 * generates short reply suggestions (romaji + spanish meaning + native kana)
 * the traveler can tap to answer back. This is the same job
 * DeepSeekPromptBuilder.SYSTEM_JAPANESE_INPUT/SYSTEM_KOREAN_INPUT/
 * SYSTEM_ENGLISH_INPUT do today in the Android app.
 */
const SYSTEM_CONVERSE = `You are an expert real-time interpreter for a Mexican traveler listening to a native speaker of the given language. Translate the input into natural Mexican Spanish, capturing the exact intent, tone, and politeness level. Also generate 2 short, natural, highly appropriate response suggestions the traveler can reply with. Each suggestion must be an object with THREE fields: "romaji" (the reply in a Spanish-reader-friendly phonetic/romanized form, no native script), "spanish" (its short meaning in Spanish), and "kana" (the SAME reply written in the target language's native script so a native speaker can read it; for English this may equal romaji). Return JSON ONLY with format: {"mainTranslation": "Traducción natural al español mexicano", "replySuggestions": [{"romaji": "...", "spanish": "...", "kana": "..."}, {"romaji": "...", "spanish": "...", "kana": "..."}]}. Do not output explanations, quotes, or extra text.`

/**
 * "explain" mode: used only when the user explicitly taps an optional
 * "explicar" button to ask for cultural/nuance context about a phrase. This
 * is a NEW prompt, distinct from "converse" — it does not translate or
 * generate reply suggestions, only explains.
 */
const SYSTEM_EXPLAIN = `You are an expert on Japanese, Korean, and American culture, explaining linguistic and cultural nuance to a Mexican traveler. Given a short phrase in the target language, explain briefly (2-4 sentences, in Spanish) the register/politeness level, any cultural context, and when it would (or wouldn't) be appropriate to use. Return JSON ONLY with format: {"explanation": "breve explicación cultural en español"}. Do not output quotes or extra text outside the JSON object.`

interface DeepSeekChatResponse {
  choices: { message: { content: string } }[]
}

/**
 * Calls DeepSeek's chat/completions endpoint with the system prompt selected
 * by `mode`. Returns the raw `content` string from the model — the Android
 * client already has tolerant JSON/fence parsing for this shape, so we don't
 * duplicate that parsing here.
 *
 * IMPORTANT: never logs `text`, `foreignLang`, or the response body.
 */
export async function callDeepSeek(
  apiKey: string,
  mode: ExplainMode,
  text: string,
  foreignLang: string,
): Promise<string> {
  const systemPrompt = mode === 'converse' ? SYSTEM_CONVERSE : SYSTEM_EXPLAIN

  const res = await fetch('https://api.deepseek.com/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model: 'deepseek-chat',
      temperature: 0.1,
      max_tokens: 512,
      messages: [
        { role: 'system', content: systemPrompt },
        {
          role: 'user',
          content: `Idioma: ${foreignLang}. Entrada: "${text}"`,
        },
      ],
    }),
  })

  if (!res.ok) {
    throw new Error(`deepseek_error_${res.status}`)
  }

  const data = await res.json<DeepSeekChatResponse>()
  return data.choices[0]?.message?.content ?? ''
}
