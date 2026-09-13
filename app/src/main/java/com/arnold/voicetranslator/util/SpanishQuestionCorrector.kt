package com.arnold.voicetranslator.util

import java.text.Normalizer

/**
 * Lightweight corrector for Spanish speech-to-text output before it gets
 * translated. Android's on-device/network STT frequently drops accents and
 * question marks (e.g. "como estas" instead of "¿Cómo estás?"), which then
 * gets translated literally, wrong or dry. This reconstructs the intended
 * question: restores missing tildes on interrogative words, and wraps
 * detected questions in "¿ ... ?" — never dropping the interrogative word
 * even if the STT text came in incomplete.
 *
 * This is intentionally a heuristic, not a full grammar engine: it covers
 * the common conversational questions a traveler asks, plus a general rule
 * (any sentence starting with a known interrogative word becomes a
 * question), rather than trying to parse arbitrary Spanish.
 */
object SpanishQuestionCorrector {

    /** Interrogative words STT commonly returns without their accent. */
    private val ACCENT_FIXES = mapOf(
        "que" to "qué",
        "quien" to "quién",
        "quienes" to "quiénes",
        "como" to "cómo",
        "cuando" to "cuándo",
        "cuanto" to "cuánto",
        "cuanta" to "cuánta",
        "cuantos" to "cuántos",
        "cuantas" to "cuántas",
        "cual" to "cuál",
        "cuales" to "cuáles",
        "donde" to "dónde",
        "adonde" to "adónde",
    )

    /**
     * Full-sentence templates for common incomplete questions, matched
     * accent-/case-insensitively against the whole trimmed input. Always
     * reconstructed to the complete, natural question — including any
     * interrogative or verb the STT might have swallowed.
     */
    private val TEMPLATES: List<Pair<String, String>> = listOf(
        "como estas" to "¿Cómo estás?",
        "como andas" to "¿Cómo andas?",
        "como te va" to "¿Cómo te va?",
        "como vas" to "¿Cómo vas?",
        "que tal" to "¿Qué tal?",
        "que onda" to "¿Qué onda?",
        "que hora es" to "¿Qué hora es?",
        "que hora tienes" to "¿Qué hora tienes?",
        "cuantos anos tienes" to "¿Cuántos años tienes?",
        "cuantos anos" to "¿Cuántos años tienes?",
        "cuanto cuesta" to "¿Cuánto cuesta?",
        "cuanto es" to "¿Cuánto es?",
        "cuanto vale" to "¿Cuánto vale?",
        "quien eres" to "¿Quién eres?",
        "quien es" to "¿Quién es?",
        "como te llamas" to "¿Cómo te llamas?",
        "cual es tu nombre" to "¿Cuál es tu nombre?",
        "donde estas" to "¿Dónde estás?",
        "donde esta" to "¿Dónde está?",
        "de donde eres" to "¿De dónde eres?",
        "donde esta el bano" to "¿Dónde está el baño?",
        "donde queda esto" to "¿Dónde queda esto?",
    )

    /**
     * Corrects [input]: reconstructs it as a properly-accented question with
     * "¿ ... ?" if it matches a known question pattern, otherwise fixes
     * missing accents on any interrogative word and wraps as a question if
     * the sentence starts with one. Text that isn't a question (or is
     * already correctly formatted) is returned unchanged.
     */
    fun correct(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return input

        val normalizedKey = normalize(trimmed)
        TEMPLATES.firstOrNull { normalize(it.first) == normalizedKey }?.let { return it.second }

        val words = trimmed.split(Regex("\\s+")).toMutableList()
        if (words.isEmpty()) return input

        var startsWithInterrogative = false
        for (i in words.indices) {
            val rawWord = words[i]
            val core = rawWord.trim { ch -> !ch.isLetter() }
            if (core.isEmpty()) continue
            val bareLower = stripAccents(core.lowercase())
            val accented = ACCENT_FIXES[bareLower] ?: continue
            val capitalized = if (core[0].isUpperCase()) {
                accented.replaceFirstChar { it.uppercase() }
            } else {
                accented
            }
            words[i] = rawWord.replaceFirst(core, capitalized)
            if (i == 0) startsWithInterrogative = true
        }

        var result = words.joinToString(" ")
        val alreadyMarked = result.trimStart().startsWith("¿") || result.trimEnd().endsWith("?")
        if (startsWithInterrogative && !alreadyMarked) {
            val body = result.trim().trimEnd('.', '!')
            val capitalizedBody = body.replaceFirstChar { it.uppercase() }
            result = "¿$capitalizedBody?"
        }
        return result
    }

    private fun normalize(text: String): String =
        stripAccents(text.lowercase().trim()).trimEnd('?', '¿', '.', '!').trim()

    private fun stripAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "")
    }
}
