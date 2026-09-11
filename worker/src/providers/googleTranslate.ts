export type GoogleTargetLang = 'ja' | 'ko' | 'en'

/**
 * Calls Google Cloud Translation API (v2, simple REST + API key) to translate
 * Spanish text into the given target language. Used for the fast, non-live
 * translation path (no reply suggestions, no cultural nuance — just speed).
 *
 * IMPORTANT: this function never logs `text` or the response body, only
 * throws typed errors the caller can log safely (status codes only).
 */
export async function translateWithGoogle(
  apiKey: string,
  text: string,
  target: GoogleTargetLang,
): Promise<string> {
  const res = await fetch(
    `https://translation.googleapis.com/language/translate/v2?key=${apiKey}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        q: text,
        source: 'es',
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
