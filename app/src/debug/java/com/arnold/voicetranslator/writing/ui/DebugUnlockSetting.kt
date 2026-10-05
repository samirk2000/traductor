package com.arnold.voicetranslator.writing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import com.arnold.voicetranslator.writing.WritingUiState

/** Developer switch. Present only in the debug source set. */
@Composable
fun DebugUnlockSetting(
    state: WritingUiState,
    uiLanguage: UiLanguage,
    onDebugUnlock: (Boolean) -> Unit,
) {
    if (!state.debugBuild) return
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = localized(uiLanguage, R.string.writing_debug_unlock),
                color = WritingPalette.secondary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = localized(uiLanguage, R.string.writing_debug_unlock_desc),
                color = WritingPalette.muted,
                fontSize = 13.sp,
            )
        }
        androidx.compose.material3.Switch(
            checked = state.debugUnlock,
            onCheckedChange = onDebugUnlock,
        )
    }
}
