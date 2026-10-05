/**
 * Server-side check for the one-time Play product `premium_unlock`.
 *
 * The Worker signs a JWT with the Play service-account private key
 * (WebCrypto RS256), exchanges it for an OAuth access token, and calls
 * purchases.products.get. Premium is confirmed only when Google reports
 * purchaseState == 0 and the package/product are on the allowlist.
 *
 * If PLAY_SERVICE_ACCOUNT_JSON is empty, the route returns
 * play_verifier_not_configured. The Android app treats that as "verifier
 * not set up yet" and keeps the previous Play-Billing-only unlock.
 */

export const ALLOWED_PACKAGE = 'com.arnold.voicetranslator'
export const ALLOWED_PRODUCT = 'premium_unlock'
export const ANDROID_PUBLISHER_SCOPE = 'https://www.googleapis.com/auth/androidpublisher'
export const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const PURCHASE_CACHE_TTL_SECONDS = 10 * 60
const OAUTH_CACHE_KEY = 'play_oauth_token'

export type ServiceAccount = {
  client_email: string
  private_key: string
  token_uri?: string
}

export type VerifyPurchaseBody = {
  configured: boolean
  owned: boolean
  definitive: boolean
  error?: string
}

export type VerifyPurchaseResult = {
  status: 200 | 400 | 500 | 502 | 503
  body: VerifyPurchaseBody
}

type PurchaseEnv = {
  RATE_LIMIT_KV: KVNamespace
  PLAY_SERVICE_ACCOUNT_JSON?: string
}

export function isAllowlisted(packageName: string, productId: string): boolean {
  return packageName === ALLOWED_PACKAGE && productId === ALLOWED_PRODUCT
}

/**
 * purchaseState 0 is purchased. 1 (canceled) and 2 (pending) are definitive
 * "not owned". A missing state is not definitive, so the app can keep a
 * recent verification instead of revoking on a bad payload.
 */
export function evaluateProductPurchase(
  packageName: string,
  productId: string,
  purchaseState: number | undefined,
): { owned: boolean; definitive: boolean } {
  if (!isAllowlisted(packageName, productId)) {
    return { owned: false, definitive: true }
  }
  if (purchaseState === 0) return { owned: true, definitive: true }
  if (purchaseState === undefined || Number.isNaN(purchaseState)) {
    return { owned: false, definitive: false }
  }
  return { owned: false, definitive: true }
}

export function publisherUrl(packageName: string, productId: string, token: string): string {
  const base = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications'
  return `${base}/${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`
}

export async function purchaseCacheKey(packageName: string, productId: string, token: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(`${packageName}\n${productId}\n${token}`),
  )
  const hex = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return `purchase:${hex}`
}

export function pemToPkcs8(pem: string): ArrayBuffer {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, '')
    .replace(/-----END PRIVATE KEY-----/g, '')
    .replace(/\s/g, '')
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

function base64url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

function base64urlJson(value: unknown): string {
  return base64url(new TextEncoder().encode(JSON.stringify(value)))
}

export async function signServiceAccountJwt(
  account: ServiceAccount,
  iatSeconds: number,
): Promise<string> {
  const header = base64urlJson({ alg: 'RS256', typ: 'JWT' })
  const payload = base64urlJson({
    iss: account.client_email,
    scope: ANDROID_PUBLISHER_SCOPE,
    aud: account.token_uri || TOKEN_URL,
    iat: iatSeconds,
    exp: iatSeconds + 3600,
  })
  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToPkcs8(account.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  const signature = new Uint8Array(
    await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${payload}`)),
  )
  return `${header}.${payload}.${base64url(signature)}`
}

async function getAccessToken(kv: KVNamespace, account: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000)
  const cached = await kv.get(OAUTH_CACHE_KEY)
  if (cached) {
    const parsed = JSON.parse(cached) as { access_token?: string; exp?: number }
    if (parsed.access_token && (parsed.exp ?? 0) > now + 60) return parsed.access_token
  }

  const jwt = await signServiceAccountJwt(account, now)
  const tokenUrl = account.token_uri || TOKEN_URL
  const response = await fetch(tokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt,
    }),
  })
  if (!response.ok) throw new Error(`oauth_${response.status}`)
  const json = (await response.json()) as { access_token?: string; expires_in?: number }
  if (!json.access_token) throw new Error('oauth_no_token')

  const expiresIn = json.expires_in ?? 3600
  const ttl = Math.max(60, Math.min(expiresIn - 120, 50 * 60))
  await kv.put(
    OAUTH_CACHE_KEY,
    JSON.stringify({ access_token: json.access_token, exp: now + ttl }),
    { expirationTtl: ttl },
  )
  return json.access_token
}

function parseServiceAccount(raw: string): ServiceAccount | null {
  try {
    const parsed = JSON.parse(raw) as Partial<ServiceAccount>
    if (!parsed.client_email || !parsed.private_key) return null
    return {
      client_email: parsed.client_email,
      private_key: parsed.private_key,
      token_uri: parsed.token_uri,
    }
  } catch {
    return null
  }
}

export async function handleVerifyPurchase(
  env: PurchaseEnv,
  body: { packageName?: string; productId?: string; purchaseToken?: string },
): Promise<VerifyPurchaseResult> {
  const packageName = body.packageName?.trim() ?? ''
  const productId = body.productId?.trim() ?? ''
  const purchaseToken = body.purchaseToken?.trim() ?? ''
  if (!packageName || !productId || !purchaseToken) {
    return {
      status: 400,
      body: { configured: true, owned: false, definitive: true, error: 'missing_fields' },
    }
  }
  if (!isAllowlisted(packageName, productId)) {
    return {
      status: 200,
      body: { configured: true, owned: false, definitive: true, error: 'not_allowlisted' },
    }
  }

  const secret = env.PLAY_SERVICE_ACCOUNT_JSON?.trim() ?? ''
  if (!secret) {
    // The app falls back to Play Billing alone when it sees this error.
    return {
      status: 503,
      body: {
        configured: false,
        owned: false,
        definitive: false,
        error: 'play_verifier_not_configured',
      },
    }
  }

  const account = parseServiceAccount(secret)
  if (!account) {
    return {
      status: 500,
      body: { configured: true, owned: false, definitive: false, error: 'play_verifier_misconfigured' },
    }
  }

  const cacheKey = await purchaseCacheKey(packageName, productId, purchaseToken)
  const cached = await env.RATE_LIMIT_KV.get(cacheKey)
  if (cached) {
    const parsed = JSON.parse(cached) as { owned?: boolean }
    return {
      status: 200,
      body: { configured: true, owned: parsed.owned === true, definitive: true },
    }
  }

  let accessToken: string
  try {
    accessToken = await getAccessToken(env.RATE_LIMIT_KV, account)
  } catch {
    return {
      status: 502,
      body: { configured: true, owned: false, definitive: false, error: 'oauth_failed' },
    }
  }

  let response: Response
  try {
    response = await fetch(publisherUrl(packageName, productId, purchaseToken), {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
  } catch {
    return {
      status: 502,
      body: { configured: true, owned: false, definitive: false, error: 'play_api_unavailable' },
    }
  }

  if (response.status >= 500 || response.status === 401 || response.status === 403) {
    return {
      status: 502,
      body: { configured: true, owned: false, definitive: false, error: 'play_api_unavailable' },
    }
  }

  if (response.status === 404) {
    await env.RATE_LIMIT_KV.put(cacheKey, JSON.stringify({ owned: false }), {
      expirationTtl: PURCHASE_CACHE_TTL_SECONDS,
    })
    return { status: 200, body: { configured: true, owned: false, definitive: true } }
  }

  if (!response.ok) {
    return {
      status: 502,
      body: { configured: true, owned: false, definitive: false, error: 'play_api_unavailable' },
    }
  }

  const payload = (await response.json()) as { purchaseState?: number }
  const decision = evaluateProductPurchase(packageName, productId, payload.purchaseState)
  if (decision.definitive) {
    await env.RATE_LIMIT_KV.put(cacheKey, JSON.stringify({ owned: decision.owned }), {
      expirationTtl: PURCHASE_CACHE_TTL_SECONDS,
    })
  }
  return {
    status: 200,
    body: { configured: true, owned: decision.owned, definitive: decision.definitive },
  }
}
