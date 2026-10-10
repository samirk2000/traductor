package com.arnold.voicetranslator.writing.ui

import androidx.compose.runtime.Composable
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.writing.WritingUiState

/**
 * The personal sideload has no developer unlock switch. Premium comes from
 * BuildConfig.FORCE_PREMIUM. The debug source set supplies the switch.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugUnlockSetting(
    state: WritingUiState,
    uiLanguage: UiLanguage,
    onDebugUnlock: (Boolean) -> Unit,
) = Unit
