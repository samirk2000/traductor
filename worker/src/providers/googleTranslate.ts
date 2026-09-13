/** Now includes "zh" and "es" — five languages supported end-to-end
 *  (ES/EN/JA/KO/ZH), not just the original ja/ko/en trio. */
export type GoogleTargetLang = 'ja' | 'ko' | 'en' | 'zh' | 'es'

/**
 * Calls Google Cloud Translation API (v2, simple REST + API key) to translate
 * `text` from [source] into [target]. Used for the fast, non-live
 * translation path (no reply suggestions, no cultural nuance — just speed).
 *
 * @param source defaults to "es" for backwards compatibility with the
 *   original Spanish-only client, but any of the 5 supported languages can
 *   now be passed (e.g. "en" -> "ja" for the Traductor's EN<->JA direction).
 *
 * IMPORTANT: this function never logs `text` or the response body, only
 * throws typed errors the caller can log safely (status codes only).
 */
export async function translateWithGoogle(
  apiKey: string,
  text: string,
  target: GoogleTargetLang,
  source: GoogleTargetLang = 'es',
): Promise<string> {
  const res = await fetch(
    `https://translation.googleapis.com/language/translate/v2?key=${apiKey}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        q: text,
        source,
        target,
        format: 'text',
      }),
    },
  )

  if (!res.ok) {
    throw new Error(`google_translate_error_${res.status}`)
  }

  const data = await res.json<{
    data: { translations: { translatedText: string }[] }
  }>()

  return data.data.translations[0]?.translatedText ?? ''
}
