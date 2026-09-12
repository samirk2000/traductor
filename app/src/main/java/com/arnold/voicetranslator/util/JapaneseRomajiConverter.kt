package com.arnold.voicetranslator.util

import android.util.Log
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Full Japanese -> Romaji conversion using Kuromoji's IPADIC morphological
 * analyzer, so kanji is properly romanized instead of passed through
 * unconverted (the limitation [KanaRomaji] has on its own: it only maps
 * kana, kanji falls through as-is).
 *
 * Flow per token: Kuromoji tokenizes the sentence and gives each token's
 * dictionary [reading][com.atilika.kuromoji.ipadic.Token.getReading] in
 * Katakana (works for kanji, kana, and most vocabulary) -> that reading is
 * romanized with [KanaRomaji.toRomaji], which already normalizes Katakana to
 * Hiragana internally -> tokens are joined with spaces into one romaji
 * sentence.
 */
object JapaneseRomajiConverter {

    private const val TAG = "JA_ROMAJI_DEBUG"

    // Tokenizer construction loads the IPADIC dictionary (a few hundred KB)
    // into memory; done once and reused for every call, not per-translation.
    private val tokenizer: Tokenizer by lazy {
        Log.d(TAG, "Initializing Kuromoji Tokenizer (one-time cost)")
        Tokenizer()
    }

    /**
     * Converts Japanese text (kanji, kana, or a mix) into a best-effort
     * Romaji transcription. Falls back to [KanaRomaji.toRomajiIfKana] on the
     * raw input if Kuromoji itself throws for any reason, so a tokenizer
     * failure never crashes the translation flow — it just degrades to the
     * old kana-only behavior.
     */
    fun kanjiToRomaji(japaneseText: String): String {
        if (japaneseText.isBlank()) return japaneseText

        return try {
            val tokens = tokenizer.tokenize(japaneseText)
            val romajiTokens = tokens.map { token ->
                // `reading` is Kuromoji's dictionary reading in Katakana. It can
                // be null/"*" for symbols, punctuation, or out-of-vocabulary
                // words (e.g. names) — fall back to the raw surface form so we
                // never drop content, even if it stays unromanized in that case.
                val isParticle = token.partOfSpeechLevel1 == "助詞"
                val reading = when {
                    // Grammatical exception: は/へ as topic/direction particles are
                    // pronounced "wa"/"e", not their literal kana reading
                    // "ha"/"he" — only applies when Kuromoji tags them as 助詞.
                    isParticle && token.surface == "は" -> "ワ"
                    isParticle && token.surface == "へ" -> "エ"
                    else -> token.reading?.takeIf { it.isNotBlank() && it != "*" }
                }
                val romajiPiece = if (reading != null) {
                    // KanaRomaji.toRomaji already normalizes Katakana -> Hiragana
                    // per-character internally, so the Katakana reading can be
                    // passed straight in.
                    KanaRomaji.toRomaji(reading)
                } else {
                    KanaRomaji.toRomajiIfKana(token.surface)
                }
                Log.d(TAG, "token surface=\"${token.surface}\" reading=$reading -> romaji=\"$romajiPiece\"")
                romajiPiece
            }
            val result = romajiTokens.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            Log.d(TAG, "kanjiToRomaji input=\"$japaneseText\" -> output=\"$result\"")
            result.ifBlank { japaneseText }
        } catch (e: Exception) {
            Log.e(TAG, "Kuromoji tokenize failed, falling back to kana-only conversion", e)
            KanaRomaji.toRomajiIfKana(japaneseText).ifBlank { japaneseText }
        }
    }
}
