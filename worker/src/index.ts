import { Context, Hono } from 'hono'
import { checkAndIncrementRateLimit } from './ratelimit'
import { translateWithGoogle, type GoogleTargetLang } from './providers/googleTranslate'
import {
  callConverse,
  callExplain,
  callKoreanPhonetic,
  callSimulate,
  callSimulationFeedback,
  callSuggestions,
  type SimulateTurnDto,
} from './providers/deepseek'

type Bindings = {
  RATE_LIMIT_KV: KVNamespace
  GOOGLE_API_KEY: string
  DEEPSEEK_API_KEY: string
}

type AppContext = Context<{ Bindings: Bindings }>

const app = new Hono<{ Bindings: Bindings }>()

/**
 * Resolves the caller's IP for rate limiting. Cloudflare always sets
 * CF-Connecting-IP for requests that reach a Worker.
 */
function clientIp(c: AppContext): string {
  return c.req.header('CF-Connecting-IP') ?? 'unknown'
}

async function withRateLimit(
  c: AppContext,
  handler: () => Promise<Response>,
): Promise<Response> {
  const ip = clientIp(c)
  const allowed = await checkAndIncrementRateLimit(c.env.RATE_LIMIT_KV, ip)
  if (!allowed) {
    return c.json({ error: 'rate_limit_exceeded' }, 429)
  }
  return handler()
}

/**
 * POST /translate
 * Body: { text: string, target: "ja" | "ko" | "en" | "zh" | "es", source?: same }
 * Fast path (Google Cloud Translation). No reply suggestions — used for the
 * non-live translate flow and the "TU hablas espanol" turn inside Live
 * Conversation mode. Korean uses /translate-ko-phonetic instead (Google
 * gives raw Hangul, not phonetic Spanish).
 *
 * `source` is optional and defaults to "es" so older clients that never sent
 * it keep working unchanged; newer clients (multi-directional Traductor:
 * ES<->EN, EN<->JA, etc.) can now pass any of the 5 supported languages.
 */
app.post('/translate', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{
        text?: string
        target?: GoogleTargetLang
        source?: GoogleTargetLang
      }>()
      const { text, target, source } = body
      if (!text || !target) {
        return c.json({ error: 'missing_text_or_target' }, 400)
      }
      const mainTranslation = await translateWithGoogle(
        c.env.GOOGLE_API_KEY,
        text,
        target,
        source ?? 'es',
      )
      return c.json({ mainTranslation })
    } catch {
      console.error({ route: '/translate', status: 500 })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

/**
 * POST /translate-ko-phonetic
 * Body: { text: string }
 * Spanish -> Korean phonetic-in-Spanish translation (DeepSeek). Used for the
 * normal (non-live) Korean translate flow — this is what makes "Coreano
 * Fonético" actually phonetic instead of raw Hangul.
 */
app.post('/translate-ko-phonetic', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{ text?: string }>()
      const { text } = body
      if (!text) {
        return c.json({ error: 'missing_text' }, 400)
      }
      const result = await callKoreanPhonetic(c.env.DEEPSEEK_API_KEY, text)
      return c.json(result)
    } catch (e) {
      // Only the error message (never text/foreignLang/body) is logged.
      console.error({ route: '/translate-ko-phonetic', status: 500, error: e instanceof Error ? e.message : String(e) })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

/**
 * POST /converse
 * Body: { text: string, foreignLang: string, meaningLang?: "es" | "en" }
 * Translation + reply suggestions for every THEM turn in Live Conversation
 * mode (DeepSeek). `meaningLang` picks the language the translation/meaning
 * comes back in — driven by the app's UI language (Origen selector), not
 * just hardcoded Spanish; defaults to "es" for older clients.
 */
app.post('/converse', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{ text?: string; foreignLang?: string; meaningLang?: string }>()
      const { text, foreignLang, meaningLang } = body
      if (!text || !foreignLang) {
        return c.json({ error: 'missing_text_or_foreignLang' }, 400)
      }
      const result = await callConverse(c.env.DEEPSEEK_API_KEY, text, foreignLang, meaningLang)
      return c.json(result)
    } catch (e) {
      console.error({ route: '/converse', status: 500, error: e instanceof Error ? e.message : String(e) })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

/**
 * POST /explain
 * Body: { text: string, foreignLang: string }
 * Optional on-demand cultural/nuance explanation (DeepSeek). Not wired into
 * the Android client's UI yet — reserved for a future "explicar" button.
 */
app.post('/explain', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{ text?: string; foreignLang?: string }>()
      const { text, foreignLang } = body
      if (!text || !foreignLang) {
        return c.json({ error: 'missing_text_or_foreignLang' }, 400)
      }
      const result = await callExplain(c.env.DEEPSEEK_API_KEY, text, foreignLang)
      return c.json(result)
    } catch (e) {
      console.error({ route: '/explain', status: 500, error: e instanceof Error ? e.message : String(e) })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

/**
 * POST /simulate
 * Body: { scenario: string, language: "ja" | "ko", history?: {role, text}[], message: string, meaningLang?: "es" | "en" }
 * Standalone "Modo Simulación" — the AI roleplays as a native speaker inside
 * the chosen scenario and always replies in the target language (never as a
 * translator). Fully independent from /translate, /converse and /explain,
 * which power Live Conversation / Subtitles / Fraseario and must stay untouched.
 * `meaningLang` (defaults to "es") picks the language of the "replySpanish"/
 * "correction" fields, driven by the app's UI language.
 *
 * Fix: response trimmed from 7 fields (~500-600 output tokens) to 4
 * (~150-280 tokens) — see callSimulate/systemSimulate's fix notes — so the
 * combined input+output latency stays well under the client's timeout even
 * once the conversation history has grown past 8-9 turns. Suggestions moved
 * to the separate POST /suggestions below.
 */
app.post('/simulate', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{
        scenario?: string
        language?: string
        history?: { role?: string; text?: string }[]
        message?: string
        meaningLang?: string
      }>()
      const { scenario, language, message, meaningLang } = body
      if (!scenario || !language || !message) {
        return c.json({ error: 'missing_scenario_language_or_message' }, 400)
      }
      const history: SimulateTurnDto[] = (body.history ?? [])
        .filter((turn) => !!turn.text)
        .map((turn) => ({
          role: turn.role === 'ai' ? 'ai' : 'user',
          text: turn.text as string,
        }))
      const result = await callSimulate(c.env.DEEPSEEK_API_KEY, scenario, language, history, message, meaningLang)
      return c.json(result)
    } catch (e) {
      console.error({ route: '/simulate', status: 500, error: e instanceof Error ? e.message : String(e) })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

/**
 * POST /suggestions
 * Body: { scenario: string, language: string, history?: {role, text}[], meaningLang?: "es" | "en" }
 * On-demand-only: 3 contextual "what could I say next" suggestions for
 * Simulation Mode, called ONLY when the user taps "¿No sabes cómo decirlo?
 * Escríbelo en español" — replaces the old behavior of generating 2-3
 * suggestions inside EVERY /simulate call (the main cause of the 9th-message
 * token/latency blowup — see /simulate's fix note above).
 */
app.post('/suggestions', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{
        scenario?: string
        language?: string
        history?: { role?: string; text?: string }[]
        meaningLang?: string
      }>()
      const { scenario, language, meaningLang } = body
      if (!scenario || !language) {
        return c.json({ error: 'missing_scenario_or_language' }, 400)
      }
      const history: SimulateTurnDto[] = (body.history ?? [])
        .filter((turn) => !!turn.text)
        .map((turn) => ({
          role: turn.role === 'ai' ? 'ai' : 'user',
          text: turn.text as string,
        }))
      const result = await callSuggestions(c.env.DEEPSEEK_API_KEY, scenario, language, history, meaningLang)
      return c.json(result)
    } catch (e) {
      console.error({ route: '/suggestions', status: 500, error: e instanceof Error ? e.message : String(e) })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

/**
 * POST /simulate-feedback
 * Body: { transcript: string }
 * End-of-session level assessment for the "Terminar y dar feedback" button in
 * Simulation Mode.
 */
app.post('/simulate-feedback', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{ transcript?: string }>()
      const { transcript } = body
      if (!transcript) {
        return c.json({ error: 'missing_transcript' }, 400)
      }
      const result = await callSimulationFeedback(c.env.DEEPSEEK_API_KEY, transcript)
      return c.json(result)
    } catch (e) {
      console.error({ route: '/simulate-feedback', status: 500, error: e instanceof Error ? e.message : String(e) })
      return c.json({ error: 'internal_error' }, 500)
    }
  }),
)

app.get('/health', (c) => c.json({ status: 'ok' }))

export default app
