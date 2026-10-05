import assert from 'node:assert/strict'
import { createPrivateKey, createPublicKey, generateKeyPairSync, verify as verifyJwt } from 'node:crypto'
import test from 'node:test'
import {
  ALLOWED_PACKAGE,
  ALLOWED_PRODUCT,
  ANDROID_PUBLISHER_SCOPE,
  evaluateProductPurchase,
  isAllowlisted,
  publisherUrl,
  purchaseCacheKey,
  signServiceAccountJwt,
} from '../src/playPurchase.ts'
import { resolveDailyLimit } from '../src/ratelimit.ts'

test('daily limit defaults to 200 and ignores non-positive values', () => {
  assert.equal(resolveDailyLimit(undefined), 200)
  assert.equal(resolveDailyLimit(''), 200)
  assert.equal(resolveDailyLimit('0'), 200)
  assert.equal(resolveDailyLimit('-4'), 200)
  assert.equal(resolveDailyLimit('nope'), 200)
  assert.equal(resolveDailyLimit('40'), 40)
})

test('only the release package and premium_unlock are allowlisted', () => {
  assert.equal(isAllowlisted(ALLOWED_PACKAGE, ALLOWED_PRODUCT), true)
  assert.equal(isAllowlisted('com.arnold.voicetranslator.debug', ALLOWED_PRODUCT), false)
  assert.equal(isAllowlisted(ALLOWED_PACKAGE, 'other_sku'), false)
})

test('purchaseState 0 is owned and every other state is not', () => {
  assert.deepEqual(evaluateProductPurchase(ALLOWED_PACKAGE, ALLOWED_PRODUCT, 0), {
    owned: true,
    definitive: true,
  })
  assert.deepEqual(evaluateProductPurchase(ALLOWED_PACKAGE, ALLOWED_PRODUCT, 1), {
    owned: false,
    definitive: true,
  })
  assert.deepEqual(evaluateProductPurchase(ALLOWED_PACKAGE, ALLOWED_PRODUCT, 2), {
    owned: false,
    definitive: true,
  })
  assert.deepEqual(evaluateProductPurchase(ALLOWED_PACKAGE, ALLOWED_PRODUCT, undefined), {
    owned: false,
    definitive: false,
  })
  assert.deepEqual(evaluateProductPurchase('com.arnold.voicetranslator.debug', ALLOWED_PRODUCT, 0), {
    owned: false,
    definitive: true,
  })
})

test('publisher url encodes the token and cache keys do not contain it', async () => {
  const token = 'abc/def+token'
  const url = publisherUrl(ALLOWED_PACKAGE, ALLOWED_PRODUCT, token)
  assert.equal(url.includes(token), false)
  assert.match(url, /purchases\/products\/premium_unlock\/tokens\/abc%2Fdef%2Btoken$/)
  const key = await purchaseCacheKey(ALLOWED_PACKAGE, ALLOWED_PRODUCT, token)
  assert.equal(key.startsWith('purchase:'), true)
  assert.equal(key.includes(token), false)
  assert.equal(key, await purchaseCacheKey(ALLOWED_PACKAGE, ALLOWED_PRODUCT, token))
})

test('service-account JWT is RS256 and carries the androidpublisher scope', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString()
  const iat = 1_700_000_000
  const jwt = await signServiceAccountJwt(
    { client_email: 'play@example.iam.gserviceaccount.com', private_key: pem },
    iat,
  )
  const [headerPart, payloadPart, signaturePart] = jwt.split('.')
  const header = JSON.parse(Buffer.from(headerPart, 'base64url').toString())
  const payload = JSON.parse(Buffer.from(payloadPart, 'base64url').toString())
  assert.equal(header.alg, 'RS256')
  assert.equal(payload.iss, 'play@example.iam.gserviceaccount.com')
  assert.equal(payload.scope, ANDROID_PUBLISHER_SCOPE)
  assert.equal(payload.aud, 'https://oauth2.googleapis.com/token')
  assert.equal(payload.iat, iat)
  assert.equal(payload.exp, iat + 3600)
  const publicKey = createPublicKey(createPrivateKey(pem))
  const ok = verifyJwt(
    'RSA-SHA256',
    Buffer.from(`${headerPart}.${payloadPart}`),
    publicKey,
    Buffer.from(signaturePart, 'base64url'),
  )
  assert.equal(ok, true)
})
