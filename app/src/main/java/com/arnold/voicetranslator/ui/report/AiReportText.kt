package com.arnold.voicetranslator.ui.report

/** Email used for in-app reports of generative AI output. */
const val AI_REPORT_EMAIL = "samirblancohernandez@gmail.com"

/** Fixed subject. Play reviewers and the inbox both look for this wording. */
const val AI_REPORT_SUBJECT = "Reporte de respuesta IA - TalkAsia"

const val AI_REPORT_TEXT_LIMIT = 1500

/**
 * Body of a report email. Includes the reason, optional comment, app version,
 * and the AI text only (truncated). Caller must not pass the user's own words.
 */
fun aiReportBody(
    reason: String,
    comment: String,
    versionName: String,
    aiText: String,
): String {
    val commentLine = comment.trim().ifEmpty { "(sin comentario)" }
    val reported = aiText.trim().take(AI_REPORT_TEXT_LIMIT)
    return buildString {
        append("Motivo: ")
        append(reason.trim())
        append('\n')
        append("Comentario: ")
        append(commentLine)
        append('\n')
        append("Versión: ")
        append(versionName.trim())
        append("\n\nRespuesta:\n")
        append(reported)
    }
}
