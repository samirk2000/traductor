package com.arnold.voicetranslator.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportTextTest {

    @Test
    fun bodyIncludesReasonCommentVersionAndTruncatedAiText() {
        val aiText = "あ".repeat(AI_REPORT_TEXT_LIMIT + 40)
        val body = aiReportBody(
            reason = "Ofensiva o inapropiada",
            comment = "  demasiado fuerte  ",
            versionName = "1.0",
            aiText = aiText,
        )
        assertTrue(body.startsWith("Motivo: Ofensiva o inapropiada\n"))
        assertTrue(body.contains("Comentario: demasiado fuerte\n"))
        assertTrue(body.contains("Versión: 1.0\n"))
        assertTrue(body.contains("\n\nRespuesta:\n"))
        val reported = body.substringAfter("Respuesta:\n")
        assertEquals(AI_REPORT_TEXT_LIMIT, reported.length)
        assertFalse(body.contains("usuario dijo"))
    }

    @Test
    fun emptyCommentUsesAPlaceholder() {
        val body = aiReportBody("Incorrecta", "   ", "1.0", "respuesta")
        assertTrue(body.contains("Comentario: (sin comentario)"))
        assertTrue(body.endsWith("respuesta"))
        assertEquals("Reporte de respuesta IA - TalkAsia", AI_REPORT_SUBJECT)
    }
}
