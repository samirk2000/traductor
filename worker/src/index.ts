import { Context, Hono } from 'hono'
import { checkAndIncrementRateLimit } from './ratelimit'
import { translateWithGoogle, type GoogleTargetLang } from './providers/googleTranslate'
import { callConverse, callExplain, callKoreanPhonetic } from './providers/deepseek'

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
 * Body: { text: string, target: "ja" | "ko" | "en" }
 * Fast path (Google Cloud Translation). No reply suggestions — used for the
 * non-live translate flow (Japanese/English) and the "TU hablas espanol"
 * turn inside Live Conversation mode. Korean uses /translate-ko-phonetic
 * instead (Google gives raw Hangul, not phonetic Spanish).
 */
app.post('/translate', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{ text?: string; target?: GoogleTargetLang }>()
      const { text, target } = body
      if (!text || !target) {
        return c.json({ error: 'missing_text_or_target' }, 400)
      }
      const mainTranslation = await translateWithGoogle(c.env.GOOGLE_API_KEY, text, target)
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
 * Body: { text: string, foreignLang: string }
 * Translation + reply suggestions for every THEM turn in Live Conversation
 * mode (DeepSeek).
 */
app.post('/converse', (c) =>
  withRateLimit(c, async () => {
    try {
      const body = await c.req.json<{ text?: string; foreignLang?: string }>()
      const { text, foreignLang } = body
      if (!text || !foreignLang) {
        return c.json({ error: 'missing_text_or_foreignLang' }, 400)
      }
      const result = await callConverse(c.env.DEEPSEEK_API_KEY, text, foreignLang)
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

app.get('/health', (c) => c.json({ status: 'ok' }))

export default app
