/**
 * Simple per-IP daily rate limit backed by Workers KV.
 *
 * NOTE: KV is eventually consistent and has no atomic increment, so under
 * concurrent requests from the same IP this can slightly overcount past the
 * limit (a known, accepted tradeoff for a 30/day cap on a low-volume app).
 * For a hard guarantee, migrate to a Durable Object counter instead.
 */
export async function checkAndIncrementRateLimit(
  kv: KVNamespace,
  ip: string,
  limitPerDay: number = 30,
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
