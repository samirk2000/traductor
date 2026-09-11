import { Hono } from 'hono'
import { checkAndIncrementRateLimit } from './ratelimit'
import { translateWithGoogle, type GoogleTargetLang } from './providers/googleTranslate'
import { callDeepSeek, type ExplainMode } from './providers/deepseek'

type Bindings = {
  RATE_LIMIT_KV: KVNamespace
  GOOGLE_API_KEY: string
  DEEPSEEK_API_KEY: string
}

const app = new Hono<{ Bindings: Bindings }>()

/**
 * Resolves the caller's IP for rate limiting. Cloudflare always sets
 * CF-Connecting-IP for requests that reach a Worker.
 */
function clientIp(c: { req: { header: (name: string) => string | undefined } }): string {
  return c.req.header('CF-Connecting-IP') ?? 'unknown'
}

/**
 * POST /translate
 * Body: { text: string, target: "ja" | "ko" | "en" }
 * Fast path (Google Cloud Translation). No reply suggestions — used for the
 * non-live translate flow and for the "TU hablas espanol" turn inside Live
 * Conversation mode.
 */
app.post('/translate', async (c) => {
  const ip = clientIp(c)
  const allowed = await checkAndIncrementRateLimit(c.env.RATE_LIMIT_KV, ip)
  if (!allowed) {
    return c.json({ error: 'rate_limit_exceeded' }, 429)
  }

  try {
    const body = await c.req.json<{ text?: string; target?: GoogleTargetLang }>()
    const { text, target } = body
    if (!text || !target) {
      return c.json({ error: 'missing_text_or_target' }, 400)
    }

    const mainTranslation = await translateWithGoogle(c.env.GOOGLE_API_KEY, text, target)
    return c.json({ mainTranslation })
  } catch {
    // Never log request/response bodies — only the fact that this route failed.
    console.error({ route: '/translate', status: 500 })
    return c.json({ error: 'internal_error' }, 500)
  }
})

/**
 * POST /explain
 * Body: { mode: "converse" | "explain", text: string, foreignLang: string }
 * DeepSeek path. "converse" = translation + reply suggestions (Live mode THEM
 * turn). "explain" = optional on-demand cultural nuance explanation.
 */
app.post('/explain', async (c) => {
  const ip = clientIp(c)
  const allowed = await checkAndIncrementRateLimit(c.env.RATE_LIMIT_KV, ip)
  if (!allowed) {
    return c.json({ error: 'rate_limit_exceeded' }, 429)
  }

  try {
    const body = await c.req.json<{
      mode?: ExplainMode
      text?: string
      foreignLang?: string
    }>()
    const { mode, text, foreignLang } = body
    if (!mode || !text || !foreignLang) {
      return c.json({ error: 'missing_mode_text_or_foreignLang' }, 400)
    }
    if (mode !== 'converse' && mode !== 'explain') {
      return c.json({ error: 'invalid_mode' }, 400)
    }

    const raw = await callDeepSeek(c.env.DEEPSEEK_API_KEY, mode, text, foreignLang)
    // The raw DeepSeek content (possibly fenced JSON) is returned as-is; the
    // Android client already has tolerant parsing for this exact shape.
    return c.json({ raw })
  } catch {
    console.error({ route: '/explain', status: 500 })
    return c.json({ error: 'internal_error' }, 500)
  }
})

app.get('/health', (c) => c.json({ status: 'ok' }))

export default app
