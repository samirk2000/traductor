/**
 * The app's UI language (independent from the language being listened to /
 * practiced) that drives what language "meaning"/"gloss" fields come back
 * in — see [MEANING_LANGUAGE_LABELS]. Defaults to "es" for backward
 * compatibility with clients that never send `meaningLang`.
 */
const MEANING_LANGUAGE_LABELS: Record<string, string> = {
  es: 'español mexicano natural',
  en: 'natural English',
}

function meaningLangLabel(meaningLang?: string): string {
  return MEANING_LANGUAGE_LABELS[meaningLang ?? 'es'] ?? MEANING_LANGUAGE_LABELS.es
}

/**
 * "converse": used for every THEM turn in the app's Live Conversation mode.
 * Translates the foreign speaker's phrase into the app's UI language and
 * generates short reply suggestions (romaji + meaning + native kana) the
 * traveler can tap to answer back. Mirrors what
 * DeepSeekPromptBuilder.SYSTEM_JAPANESE_INPUT/SYSTEM_KOREAN_INPUT/
 * SYSTEM_ENGLISH_INPUT used to do directly from the Android client.
 *
 * Fix: the "meaning"/"spanish" field used to be hardcoded to always come
 * back in Spanish, even when the app's UI language (Origen selector) was
 * English — so "Listen Japanese"/Live mode kept showing Spanish meanings
 * under an all-English UI. Now parameterized by [meaningLangLabel] so it
 * matches whatever language the client asks for via `meaningLang`.
 */
function systemConverse(langLabel: string): string {
  return `You are an expert real-time interpreter for a traveler listening to a native speaker of the given language. Translate the input into natural ${langLabel}, capturing the exact intent, tone, and politeness level. Also generate 2 short, natural, highly appropriate response suggestions the traveler can reply with. Each suggestion must be an object with THREE fields: "romaji" (the reply in a phonetic/romanized form, no native script), "spanish" (its short meaning in ${langLabel}), and "kana" (the SAME reply written in the target language's native script so a native speaker can read it; for English this may equal romaji). Return JSON ONLY with format: {"mainTranslation": "Traducción natural al idioma pedido", "replySuggestions": [{"romaji": "...", "spanish": "...", "kana": "..."}, {"romaji": "...", "spanish": "...", "kana": "..."}]}. Do not output explanations, quotes, or extra text.`
}

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
 * its own romanization and meaning so the client can render the
 * "kana/hangul grande + romaji chico + traducción" layout. See
 * [systemSimulate]'s fix note for why this no longer also returns
 * suggestions or a full re-translation of the user's own message.
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

/**
 * @param meaningLangLabel language the "replySpanish"/"correction" fields
 *   must come back in (see [meaningLangLabel]/[MEANING_LANGUAGE_LABELS]).
 *
 * Fix: the response used to require 7 fields — native/romanized/spanish for
 * the AI's own reply, userNative/userRomanized/userSpanish re-translating
 * the user's own last message, AND 2-3 full replySuggestions objects — all
 * in one DeepSeek call. That's ~500-600 output tokens, and once the
 * conversation history grew past ~8 turns the combined input+output made
 * DeepSeek regularly exceed 8s, tripping the client's timeout ("Servidor
 * ocupado" hang at the 9th message). Trimmed to 4 short fields (~150-280
 * tokens): the AI's own reply (3 forms) plus a single optional one-line
 * "correction" of the user's last message instead of a full 3-form
 * retranslation. Suggestions moved entirely to the separate, on-demand
 * POST /suggestions (see [systemSuggestions]) so they're no longer
 * generated on every turn — only when the user actually asks for help.
 */
function systemSimulate(scenario: string, language: string, meaningLangLabel: string): string {
  const scenarioLabel = SCENARIO_LABELS[scenario] ?? scenario
  const languageLabel = LANGUAGE_LABELS[language] ?? language
  return `Hablante nativo de ${languageLabel} en: ${scenarioLabel}. Actua siempre como ese personaje, nunca traductor/IA, nunca cambies de idioma. Responde SOLO en ${languageLabel} (escritura nativa), 1-2 frases, natural, coherente con el historial. El usuario puede escribirte en ${languageLabel} o en cualquier otro idioma. Si su último mensaje tiene un error gramatical o de vocabulario en ${languageLabel}, pon una corrección MUY breve en "correction" (en ${meaningLangLabel}, ej. "Mejor: ..."); si ya estaba bien o no fue en ${languageLabel}, deja correction vacío (""). Responde SOLO este JSON, nada de texto extra: {"replyNative":"tu respuesta en escritura nativa","replyRomaji":"su romanización","replySpanish":"su significado breve en ${meaningLangLabel}","correction":""}`
}

/**
 * "suggestions": powers the on-demand "¿No sabes cómo decirlo?" helper in
 * Simulation Mode — only called when the user explicitly taps it, NOT on
 * every turn (see [systemSimulate]'s fix note). Generates 3 short, natural
 * ways the user could reply next, given the recent conversation.
 */
function systemSuggestions(scenario: string, language: string, meaningLangLabel: string): string {
  const scenarioLabel = SCENARIO_LABELS[scenario] ?? scenario
  const languageLabel = LANGUAGE_LABELS[language] ?? language
  return `Hablante nativo de ${languageLabel} en: ${scenarioLabel}. Dado el historial de la conversación, da EXACTAMENTE 3 sugerencias breves y naturales (diferentes entre sí) de qué podría responder el usuario a continuación, en ${languageLabel}. Responde SOLO este JSON: {"suggestions":[{"native":"","romanized":"","spanish":"significado en ${meaningLangLabel}"},{"native":"","romanized":"","spanish":""},{"native":"","romanized":"","spanish":""}]}`
}

export interface SimulateTurnDto {
  role: 'user' | 'ai'
  text: string
}

/** One suggested way the practicing user could reply next, in 3 parallel forms. */
export interface SimulateSuggestionDto {
  native: string
  romanized: string
  spanish: string
}

/**
 * Result of POST /simulate — trimmed to 4 short fields (see [systemSimulate]'s
 * fix note) to keep output tokens low and avoid the "Servidor ocupado"
 * timeouts that used to hit around the 9th message.
 */
export interface SimulateResult {
  replyNative: string
  replyRomaji: string
  replySpanish: string
  /** Brief correction of the user's last message, or "" if none needed. */
  correction: string
}

/** Result of POST /suggestions — 3 contextual "what to say next" options. */
export interface SuggestionsResult {
  suggestions: SimulateSuggestionDto[]
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
  // Fix: was a flat 512 for every route — /simulate's old 7-field response
  // needed most of that, but the new 4-field shape (see systemSimulate)
  // only needs ~280 to leave headroom, so it's now overridable per call
  // instead of always paying for the biggest possible response.
  maxTokens = 512,
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
      max_tokens: maxTokens,
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
  meaningLang?: string,
): Promise<ConverseResult> {
  const raw = await callDeepSeekRaw(
    apiKey,
    systemConverse(meaningLangLabel(meaningLang)),
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
  meaningLang?: string,
): Promise<SimulateResult> {
  const historyText = history
    .map((turn) => `${turn.role === 'user' ? 'Usuario' : 'Tú (tu respuesta previa)'}: ${turn.text}`)
    .join('\n')

  const userContent = [
    historyText ? `Historial reciente de la conversación:\n${historyText}` : null,
    // Fix: used to hardcode "dicho en español", biasing the model to treat
    // every input as Spanish even when the user typed English (or the
    // practiced language itself) — now left neutral so the system prompt's
    // "español O inglés" rule actually applies.
    `Nuevo mensaje del usuario: "${userMessage}"`,
    'Responde siguiendo estrictamente las instrucciones del sistema.',
  ]
    .filter(Boolean)
    .join('\n\n')

  const raw = await callDeepSeekRaw(
    apiKey,
    systemSimulate(scenario, language, meaningLangLabel(meaningLang)),
    userContent,
    // Fix: 280 (was the shared 512) — the trimmed 4-field JSON response
    // never needs more than ~200 tokens; capping it lower also means
    // DeepSeek can't ramble past the point of usefulness and cuts latency.
    280,
  )
  const parsed = parseJsonLoose<{
    replyNative?: string
    replyRomaji?: string
    replySpanish?: string
    correction?: string
  }>(raw)
  return {
    replyNative: parsed?.replyNative?.trim() || raw.trim(),
    replyRomaji: parsed?.replyRomaji?.trim() || '',
    replySpanish: parsed?.replySpanish?.trim() || '',
    correction: parsed?.correction?.trim() || '',
  }
}

/**
 * POST /suggestions — 3 contextual "what could I say next" options, called
 * ONLY when the user taps "¿No sabes cómo decirlo?" in Simulation Mode
 * (never automatically per-turn — see [systemSimulate]'s fix note for why).
 */
export async function callSuggestions(
  apiKey: string,
  scenario: string,
  language: string,
  history: SimulateTurnDto[],
  meaningLang?: string,
): Promise<SuggestionsResult> {
  const historyText = history
    .map((turn) => `${turn.role === 'user' ? 'Usuario' : 'Tú (tu respuesta previa)'}: ${turn.text}`)
    .join('\n')
  const userContent = historyText
    ? `Historial reciente de la conversación:\n${historyText}\n\nDa las 3 sugerencias siguiendo el sistema.`
    : 'Aún no hay historial. Da 3 sugerencias iniciales típicas para empezar esta conversación.'

  const raw = await callDeepSeekRaw(
    apiKey,
    systemSuggestions(scenario, language, meaningLangLabel(meaningLang)),
    userContent,
    220,
  )
  const parsed = parseJsonLoose<{ suggestions?: { native?: string; romanized?: string; spanish?: string }[] }>(raw)
  return {
    suggestions: (parsed?.suggestions ?? [])
      .filter((s) => !!s.native)
      .map((s) => ({
        native: s.native as string,
        romanized: s.romanized?.trim() || (s.native as string),
        spanish: s.spanish?.trim() || '',
      })),
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
