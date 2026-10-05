/**
 * Simple per-IP daily rate limit backed by Workers KV.
 *
 * NOTE: KV is eventually consistent and has no atomic increment, so under
 * concurrent requests from the same IP this can slightly overcount past the
 * limit (a known, accepted tradeoff). For a hard guarantee, migrate to a
 * Durable Object counter instead.
 *
 * The cap comes from the RATE_LIMIT_PER_DAY env var. When that var is missing
 * or not a positive integer, [resolveDailyLimit] falls back to 200 requests
 * per UTC day, which is enough for normal voice use without leaving the
 * proxy open.
 */
export function resolveDailyLimit(raw: string | undefined, fallback = 200): number {
  const parsed = Number.parseInt(raw ?? '', 10)
  if (!Number.isFinite(parsed) || parsed <= 0) return fallback
  return parsed
}

export async function checkAndIncrementRateLimit(
  kv: KVNamespace,
  ip: string,
  limitPerDay: number = 200,
): Promise<boolean> {
  const today = new Date().toISOString().slice(0, 10) // YYYY-MM-DD (UTC)
  const key = `ratelimit:${ip}:${today}`

  const current = parseInt((await kv.get(key)) ?? '0', 10)
  if (current >= limitPerDay) {
    return false
  }

  // 26h TTL: outlives the UTC day boundary and self-cleans without a cron job.
  await kv.put(key, String(current + 1), { expirationTtl: 60 * 60 * 26 })
  return true
}
