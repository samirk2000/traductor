package com.arnold.voicetranslator.util

/**
 * Converts Hiragana / Katakana text into Romaji (romanized Japanese) so the
 * user can read what was recognized even without knowing Kana.
 *
 * The conversion is a best-effort Hepburn-style syllabary mapping. Katakana is
 * first normalized to its hiragana codepoint, then looked up in the hiragana
 * table (works for all kana that have direct hiragana equivalents). Kanji are
 * left unchanged because they can't be romanized without a dictionary.
 */
object KanaRomaji {

    private val HIRAGANA = table()

    /**
     * Hiragana -> romaji. Only '' vowels row etc. Surrogate "n" ん is included.
     * Small tsu っ is handled separately (sokuon).
     */
    private fun table(): Map<Char, String> = mapOf(
        // vowels
        '\u3042' to "a", '\u3044' to "i", '\u3046' to "u", '\u3048' to "e", '\u304A' to "o",
        // k
        '\u304B' to "ka", '\u304D' to "ki", '\u304F' to "ku", '\u3051' to "ke", '\u3053' to "ko",
        '\u304C' to "ga", '\u304E' to "gi", '\u3050' to "gu", '\u3052' to "ge", '\u3054' to "go",
        // s
        '\u3055' to "sa", '\u3057' to "shi", '\u3059' to "su", '\u305B' to "se", '\u305D' to "so",
        '\u3056' to "za", '\u3058' to "ji", '\u305A' to "zu", '\u305C' to "ze", '\u305E' to "zo",
        // t
        '\u305F' to "ta", '\u3061' to "chi", '\u3064' to "tsu", '\u3066' to "te", '\u3068' to "to",
        '\u3060' to "da", '\u3062' to "ji", '\u3065' to "zu", '\u3067' to "de", '\u3069' to "do",
        // n
        '\u306A' to "na", '\u306B' to "ni", '\u306C' to "nu", '\u306D' to "ne", '\u306E' to "no",
        // h
        '\u306F' to "ha", '\u3072' to "hi", '\u3075' to "fu", '\u3078' to "he", '\u307B' to "ho",
        '\u3070' to "ba", '\u3073' to "bi", '\u3076' to "bu", '\u3079' to "be", '\u307C' to "bo",
        '\u3071' to "pa", '\u3074' to "pi", '\u3077' to "pu", '\u307A' to "pe", '\u307D' to "po",
        // m
        '\u307E' to "ma", '\u307F' to "mi", '\u3080' to "mu", '\u3081' to "me", '\u3082' to "mo",
        // y (small and normal)
        '\u3084' to "ya", '\u3086' to "yu", '\u3088' to "yo",
        '\u3083' to "ya", '\u3085' to "yu", '\u3087' to "yo",
        // r
        '\u3089' to "ra", '\u308A' to "ri", '\u308B' to "ru", '\u308C' to "re", '\u308D' to "ro",
        // w / n
        '\u308F' to "wa", '\u3092' to "wo", '\u3093' to "n",
        '\u3090' to "wi", '\u3091' to "we",
        // small vowels (rare, usually within youon handled specially)
        '\u3041' to "a", '\u3043' to "i", '\u3045' to "u", '\u3047' to "e", '\u3049' to "o",
    )

    /** Small ya/yu/yo for youon contractions. */
    private val YOUN = setOf(
        '\u3083', '\u3085', '\u3087', '\u30E3', '\u30E5', '\u30E7',
    )

    /**
     * Returns a romaji transcription of the given Japanese text.
     */
    fun toRomaji(input: String): String {
        if (input.isBlank()) return input
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val ch = input[i]

            // Normalize katakana to hiragana codepoint so the table works.
            val kana = toHiragana(ch)

            // sokuon (small tsu) -> double next consonant.
            if (kana == '\u3063') {
                val nextKana = input.getOrNull(i + 1)?.let { toHiragana(it) }
                val nextRomaji = nextKana?.let { HIRAGANA[it] }
                if (nextRomaji != null) {
                    sb.append(doubleFirst(nextRomaji))
                } else {
                    sb.append("tsu")
                }
                i++
                continue
            }

            // youon: consonant + small ya/yu/yo -> contracted syllable.
            if (i + 1 < input.length) {
                val next = input[i + 1]
                val nextKana = toHiragana(next)
                if (nextKana in YOUN && HIRAGANA.containsKey(kana)) {
                    val base = HIRAGANA[kana]!!
                    val cons = stripFinalVowel(base)
                    sb.append(combineYouon(cons, HIRAGANA[nextKana]!!))
                    i += 2
                    continue
                }
            }

            val romaji = HIRAGANA[kana]
            if (romaji != null) sb.append(romaji)
            else sb.append(ch) // kanji / latin / punctuation passthrough
            i++
        }
        return sb.toString()
    }

    /**
     * Converts only if the string contains Japanese kana; otherwise returns the
     * original (so pure-romaji input passes through untouched).
     */
    fun toRomajiIfKana(input: String): String {
        val hasKana = input.any { toHiragana(it) != null || it == '\u30C3' }
        return if (hasKana) toRomaji(input) else input
    }

    /** Maps a katakana character to its hiragana equivalent; latin/kanji -> null. */
    private fun toHiragana(ch: Char): Char? = when {
        ch in '\u3041'..'\u3096' -> ch
        ch in '\u30A1'..'\u30F6' -> (ch.code - 0x60).toChar()
        ch == '\u30C3' -> '\u3063' // katakana small tsu
        else -> null
    }

    private fun stripFinalVowel(romaji: String): String = when {
        romaji == "shi" -> "sh"
        romaji == "chi" -> "ch"
        romaji == "tsu" -> "ts"
        romaji == "ji" -> "j"
        romaji.length > 1 && romaji.last() in "aiueo" -> romaji.dropLast(1)
        else -> romaji
    }

    private fun combineYouon(consonant: String, youon: String): String = when (consonant) {
        "sh", "ch", "j" -> consonant + youon
        else -> consonant + youon
    }

    private fun doubleFirst(romaji: String): String = when {
        romaji.startsWith("sh") -> "s" + romaji
        romaji.startsWith("ch") -> "c" + romaji
        romaji.startsWith("ts") -> "t" + romaji
        else -> romaji.first().toString() + romaji
    }
}
