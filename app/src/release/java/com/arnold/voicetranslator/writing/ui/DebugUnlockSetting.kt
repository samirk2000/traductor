package com.arnold.voicetranslator.writing.ui

import androidx.compose.runtime.Composable
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.writing.WritingUiState

/**
 * Release builds have no developer unlock. The debug source set supplies the
 * switch and its strings, so they are absent from the Play APK.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugUnlockSetting(
    state: WritingUiState,
    uiLanguage: UiLanguage,
    onDebugUnlock: (Boolean) -> Unit,
) = Unit
