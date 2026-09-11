/**
 * "converse": used for every THEM turn in the app's Live Conversation mode.
 * Translates the foreign speaker's phrase into Mexican Spanish and generates
 * short reply suggestions (romaji + spanish meaning + native kana) the
 * traveler can tap to answer back. Mirrors what
 * DeepSeekPromptBuilder.SYSTEM_JAPANESE_INPUT/SYSTEM_KOREAN_INPUT/
 * SYSTEM_ENGLISH_INPUT used to do directly from the Android client.
 */
const SYSTEM_CONVERSE = `You are an expert real-time interpreter for a Mexican traveler listening to a native speaker of the given language. Translate the input into natural Mexican Spanish, capturing the exact intent, tone, and politeness level. Also generate 2 short, natural, highly appropriate response suggestions the traveler can reply with. Each suggestion must be an object with THREE fields: "romaji" (the reply in a Spanish-reader-friendly phonetic/romanized form, no native script), "spanish" (its short meaning in Spanish), and "kana" (the SAME reply written in the target language's native script so a native speaker can read it; for English this may equal romaji). Return JSON ONLY with format: {"mainTranslation": "Traducción natural al español mexicano", "replySuggestions": [{"romaji": "...", "spanish": "...", "kana": "..."}, {"romaji": "...", "spanish": "...", "kana": "..."}]}. Do not output explanations, quotes, or extra text.`

/**
 * "explain": used only when the user explicitly taps an optional "explicar"
 * button to ask for cultural/nuance context about a phrase. Does not
 * translate or generate reply suggestions, only explains.
 */
const SYSTEM_EXPLAIN = `You are an expert on Japanese, Korean, and American culture, explaining linguistic and cultural nuance to a Mexican traveler. Given a short phrase in the target language, explain briefly (2-4 sentences, in Spanish) the register/politeness level, any cultural context, and when it would (or wouldn't) be appropriate to use. Return JSON ONLY with format: {"explanation": "breve explicación cultural en español"}. Do not output quotes or extra text outside the JSON object.`

/**
 * "ko-phonetic": Spanish -> Korean translation expressed as an approximate
 * Spanish-syllable phonetic transcription (not Hangul), matching the app's
 * "Coreano Fonético" mode. Mirrors DeepSeekPromptBuilder.SYSTEM_KOREAN.
 */
const SYSTEM_KO_PHONETIC = `Eres un traductor Español -> Coreano con pronunciación fonética en español. SIEMPRE responde ÚNICAMENTE con un objeto JSON válido, sin texto adicional, sin Markdown ni comillas finales. Formato estricto: {"mainTranslation":"pronunciación fonética","alternatives":["alternativa 1","alternativa 2"]}. Reglas: mainTranslation y cada alternativa SON la pronunciación aproximada en sílabas españolas separadas por guiones (ej. "An-nyeong-ha-se-yo"). Nada de hangul, nada de definiciones, nada de explicaciones gramaticales. Alternatives debe tener entre 1 y 3 pronunciaciones alternativas naturales.`

interface DeepSeekChatResponse {
  choices: { message: { content: string } }[]
}

export interface ReplySuggestionDto {
  romaji: string
  spanish: string
  kana: string
}

export interface ConverseResult {
  mainTranslation: string
  replySuggestions: ReplySuggestionDto[]
}

export interface ExplainResult {
  explanation: string
}

export interface KoreanPhoneticResult {
  mainTranslation: string
  alternatives: string[]
}

/**
 * Strips Markdown code fences some models wrap JSON output in, then attempts
 * a strict JSON.parse. Returns null (never throws) on any parse failure so
 * callers can fall back to a safe default.
 */
function parseJsonLoose<T>(raw: string): T | null {
  const cleaned = raw
    .trim()
    .replace(/^```json/i, '')
    .replace(/^```/, '')
    .replace(/```$/, '')
    .trim()
  try {
    return JSON.parse(cleaned) as T
  } catch {
    return null
  }
}

/**
 * Low-level DeepSeek chat/completions call. Never logs `systemPrompt`,
 * `userContent`, or the response body — only thrown errors carry a status
 * code, never message content.
 */
async function callDeepSeekRaw(
  apiKey: string,
  systemPrompt: string,
  userContent: string,
): Promise<string> {
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
        { role: 'user', content: userContent },
      ],
    }),
  })

  const bodyText = await res.text()

  if (!res.ok) {
    // Never include the body itself (could echo back user text via DeepSeek's
    // error message) — only status and length for diagnostics.
    throw new Error(`deepseek_error_${res.status}_len${bodyText.length}`)
  }

  let data: DeepSeekChatResponse
  try {
    data = JSON.parse(bodyText) as DeepSeekChatResponse
  } catch {
    throw new Error(`deepseek_bad_json_status${res.status}_len${bodyText.length}`)
  }
  return data.choices[0]?.message?.content ?? ''
}

/** POST /converse — translation + reply suggestions for Live Conversation mode. */
export async function callConverse(
  apiKey: string,
  text: string,
  foreignLang: string,
): Promise<ConverseResult> {
  const raw = await callDeepSeekRaw(
    apiKey,
    SYSTEM_CONVERSE,
    `Diálogo: Traduce y responde siguiendo el sistema. Entrada en ${foreignLang}: "${text}"`,
  )
  const parsed = parseJsonLoose<{ mainTranslation?: string; replySuggestions?: ReplySuggestionDto[] }>(raw)
  return {
    mainTranslation: parsed?.mainTranslation?.trim() || raw.trim(),
    replySuggestions: parsed?.replySuggestions ?? [],
  }
}

/** POST /explain — optional on-demand cultural/nuance explanation. */
export async function callExplain(
  apiKey: string,
  text: string,
  foreignLang: string,
): Promise<ExplainResult> {
  const raw = await callDeepSeekRaw(
    apiKey,
    SYSTEM_EXPLAIN,
    `Idioma: ${foreignLang}. Frase: "${text}"`,
  )
  const parsed = parseJsonLoose<{ explanation?: string }>(raw)
  return { explanation: parsed?.explanation?.trim() || raw.trim() }
}

/** POST /translate-ko-phonetic — Spanish -> Korean phonetic-in-Spanish translation. */
export async function callKoreanPhonetic(
  apiKey: string,
  text: string,
): Promise<KoreanPhoneticResult> {
  const raw = await callDeepSeekRaw(
    apiKey,
    SYSTEM_KO_PHONETIC,
    `Traduce al coreano fonético en español: ${text}`,
  )
  const parsed = parseJsonLoose<{ mainTranslation?: string; alternatives?: string[] }>(raw)
  return {
    mainTranslation: parsed?.mainTranslation?.trim() || raw.trim(),
    alternatives: parsed?.alternatives ?? [],
  }
}
