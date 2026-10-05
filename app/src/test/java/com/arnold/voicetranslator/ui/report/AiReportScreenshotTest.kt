package com.arnold.voicetranslator.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.arnold.voicetranslator.VoiceTranslatorTheme
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import org.junit.Rule
import org.junit.Test

/** Renders the in-app report dialog used for generative AI replies. */
class AiReportScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "es"),
        showSystemUi = false,
    )

    @Test
    fun reportDialog() {
        paparazzi.snapshot(name = "report-dialog") {
            VoiceTranslatorTheme {
                val reasons = listOf(
                    localized(UiLanguage.ES, com.arnold.voicetranslator.R.string.ai_report_reason_offensive),
                    localized(UiLanguage.ES, com.arnold.voicetranslator.R.string.ai_report_reason_incorrect),
                    localized(UiLanguage.ES, com.arnold.voicetranslator.R.string.ai_report_reason_other),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(24.dp)
                            .clip(RoundedCornerShape(28.dp)),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                            AiReportDialogBody(
                                reasons = reasons,
                                selected = reasons.first(),
                                onSelect = {},
                                comment = "",
                                onComment = {},
                                uiLanguage = UiLanguage.ES,
                                onDismiss = {},
                                onSend = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
