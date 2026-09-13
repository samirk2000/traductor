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

/**
 * "simulate": powers the standalone Simulation Mode ("Modo Simulación").
 * The AI roleplays as a native speaker of the target language inside a
 * chosen real-life scenario — it must NEVER act as a translator/assistant,
 * only as the character. Always replies fully in the target language, plus
 * its own romanization and Spanish meaning so the client can render the
 * "kana/hangul grande + romaji chico + traducción" layout.
 */
const SCENARIO_LABELS: Record<string, string> = {
  tienda: 'una tienda o comercio, tú eres el dependiente/vendedor',
  hotel: 'la recepción de un hotel, tú eres el recepcionista',
  restaurante: 'un restaurante, tú eres el mesero/mesera',
  cita: 'una cita romántica informal, tú eres la otra persona en la cita',
  trabajo: 'una oficina o entrevista de trabajo, tú eres el entrevistador/colega',
}

const LANGUAGE_LABELS: Record<string, string> = {
  ja: 'japonés',
  ko: 'coreano',
  zh: 'chino (mandarín)',
  en: 'inglés',
}

function systemSimulate(scenario: string, language: string): string {
  const scenarioLabel = SCENARIO_LABELS[scenario] ?? scenario
  const languageLabel = LANGUAGE_LABELS[language] ?? language
  // NOTE: the user's own turn can now come typed/spoken in either the
  // practiced language OR Spanish (the Android client's STT/hint switched
  // to the practiced language, but nothing blocks typing Spanish) — the
  // model must accept both. It must also return the romanization + Spanish
  // meaning of the USER'S last message (userRomanized/userSpanish) so the
  // client can render those under the user's own chat bubble, exactly like
  // it already does for its own reply (native/romanized/spanish).
  return `Eres un hablante nativo de ${languageLabel} actuando en este escenario de la vida real: ${scenarioLabel}. Debes actuar SIEMPRE como ese personaje, nunca como traductor ni asistente de IA, y nunca cambies de idioma. Responde ÚNICAMENTE en ${languageLabel} (en su escritura nativa), de forma breve (1-2 frases), natural y coherente con el escenario y el historial reciente de la conversación. El usuario puede escribirte tanto en ${languageLabel} (está practicando) como en español; entiendes ambos perfectamente y tú siempre respondes en ${languageLabel}. Después entrega también: la romanización/lectura fonética latina de tu respuesta, su traducción breve al español, y —muy importante— la romanización y la traducción breve al español del ÚLTIMO mensaje que el usuario te acaba de escribir a ti (si el usuario ya escribió en español, usa ese mismo texto como "userSpanish" y da su romanización aproximada en "userRomanized" solo si aplica, o repite el texto si no aplica). Devuelve SOLO un objeto JSON, sin texto ni comillas adicionales, con este formato exacto: {"native": "tu respuesta en la escritura nativa del idioma (kana/kanji para japonés, hangul para coreano, hanzi para chino)", "romanized": "romanización o lectura fonética en alfabeto latino de esa misma respuesta", "spanish": "traducción breve al español de tu respuesta", "userRomanized": "romanización/lectura fonética del último mensaje del usuario", "userSpanish": "traducción breve al español del último mensaje del usuario"}.`
}

export interface SimulateTurnDto {
  role: 'user' | 'ai'
  text: string
}

export interface SimulateResult {
  native: string
  romanized: string
  spanish: string
  userRomanized: string
  userSpanish: string
}

/**
 * "simulate-feedback": one-shot analysis of the whole simulated conversation,
 * used by the "Terminar y dar feedback" button. Not a roleplay reply — pure
 * Spanish-language assessment of the user's level.
 */
const SYSTEM_SIMULATION_FEEDBACK = `Eres un evaluador de idiomas para un estudiante hispanohablante que acaba de practicar una conversación simulada en japonés o coreano. A partir de la transcripción proporcionada (solo sus mensajes en español, que representan lo que quiso decir en el idioma meta, y las respuestas de la IA), da una evaluación breve y honesta en español (4-6 líneas): nivel aproximado (principiante/intermedio/avanzado), 1-2 fortalezas observadas, y 2-3 sugerencias concretas de mejora. Sé constructivo y directo. Devuelve SOLO un objeto JSON con formato: {"feedback": "texto de la evaluación en español"}. No agregues comillas ni texto fuera del JSON.`

export interface SimulationFeedbackResult {
  feedback: string
}

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

/**
 * POST /simulate — one in-character reply for Simulation Mode: the AI
 * roleplays as a native speaker of `language` inside `scenario`, given the
 * last few turns of context and the user's new Spanish message.
 */
export async function callSimulate(
  apiKey: string,
  scenario: string,
  language: string,
  history: SimulateTurnDto[],
  userMessage: string,
): Promise<SimulateResult> {
  const historyText = history
    .map((turn) => `${turn.role === 'user' ? 'Usuario (habló en español)' : 'Tú (tu respuesta previa)'}: ${turn.text}`)
    .join('\n')

  const userContent = [
    historyText ? `Historial reciente de la conversación:\n${historyText}` : null,
    `Nuevo mensaje del usuario, dicho en español: "${userMessage}"`,
    'Responde siguiendo estrictamente las instrucciones del sistema.',
  ]
    .filter(Boolean)
    .join('\n\n')

  const raw = await callDeepSeekRaw(apiKey, systemSimulate(scenario, language), userContent)
  const parsed = parseJsonLoose<{
    native?: string
    romanized?: string
    spanish?: string
    userRomanized?: string
    userSpanish?: string
  }>(raw)
  return {
    native: parsed?.native?.trim() || raw.trim(),
    romanized: parsed?.romanized?.trim() || '',
    spanish: parsed?.spanish?.trim() || '',
    userRomanized: parsed?.userRomanized?.trim() || '',
    userSpanish: parsed?.userSpanish?.trim() || '',
  }
}

/** POST /simulate-feedback — end-of-session level assessment for Simulation Mode. */
export async function callSimulationFeedback(
  apiKey: string,
  transcript: string,
): Promise<SimulationFeedbackResult> {
  const raw = await callDeepSeekRaw(apiKey, SYSTEM_SIMULATION_FEEDBACK, `Transcripción de la conversación:\n${transcript}`)
  const parsed = parseJsonLoose<{ feedback?: string }>(raw)
  return { feedback: parsed?.feedback?.trim() || raw.trim() }
}
