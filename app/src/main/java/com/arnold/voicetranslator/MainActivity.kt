package com.arnold.voicetranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arnold.voicetranslator.data.model.ReplySuggestion
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.simulation.SimulationModeScreen
import com.arnold.voicetranslator.simulation.SimulationViewModel
import com.arnold.voicetranslator.ui.MainViewModel
import com.arnold.voicetranslator.ui.phrasebook.PhrasebookScreen
import com.arnold.voicetranslator.ui.state.LiveChatEntry
import com.arnold.voicetranslator.ui.state.LiveTurn
import com.arnold.voicetranslator.ui.state.ModelDownloadStatus
import com.arnold.voicetranslator.ui.state.OfflineModelInfo
import com.arnold.voicetranslator.ui.state.PipelineStatus
import com.arnold.voicetranslator.ui.state.TranslationHistoryItem
import com.arnold.voicetranslator.ui.state.TranslatorUiState
import com.arnold.voicetranslator.ui.state.statusText

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.onPermissionResult(hasMicPermission())

        setContent {
            VoiceTranslatorTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                // Top-level tab switcher: "Traductor" (existing en vivo /
                // subtítulos flow, untouched below) vs the new, fully
                // independent "Fraseario" module. This wrapper only decides
                // WHICH screen is drawn — it does not alter TranslatorScreen
                // or any of its internal logic.
                var selectedTab by remember { mutableStateOf(AppTab.TRANSLATOR) }

                // Consume the status-bar inset here (once) so it isn't
                // applied a second time by TranslatorScreen's own internal
                // `.statusBarsPadding()` call below — keeps the existing
                // "en vivo"/"subtítulos" layout pixel-identical to before.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .consumeWindowInsets(WindowInsets.statusBars),
                ) {
                    AppTabBar(selected = selectedTab, onSelect = { selectedTab = it })

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            AppTab.TRANSLATOR -> TranslatorScreen(
                                state = state,
                                onPermissionResult = viewModel::onPermissionResult,
                                onSpeakSpanish = viewModel::onSpeakSpanishToggle,
                                onListenJapanese = viewModel::onListenJapaneseToggle,
                                onSelectLanguage = viewModel::onSelectLanguage,
                                onToggleOffline = viewModel::onToggleOfflineMode,
                                onDownloadModel = viewModel::onDownloadModel,
                                onReplay = viewModel::onReplay,
                                onSuggestionTapped = viewModel::onSuggestionTapped,
                                onToggleSubtitles = viewModel::onToggleSubtitles,
                                onClearHistory = viewModel::onClearHistory,
                                onToggleAutoSpeak = viewModel::onToggleAutoSpeak,
                                onTranslateSpanishText = viewModel::onTranslateSpanishText,
                                onToggleShowKana = viewModel::onToggleShowKana,
                                onTranslateJapaneseText = viewModel::onTranslateJapaneseText,
                                onToggleLiveConversation = viewModel::onToggleLiveConversation,
                                onLiveSpeakSpanish = viewModel::onLiveSpeakSpanish,
                                onLiveSuggestionTapped = viewModel::onLiveSuggestionTapped,
                                onToggleLiveListeningPause = viewModel::onToggleLiveListeningPause,
                                onDownloadOfflineModelFor = viewModel::downloadOfflineModelFor,
                                onRefreshOfflineModels = viewModel::refreshAllOfflineModelStates,
                                onConfirmDownloadMissingModel = viewModel::onConfirmDownloadMissingModel,
                                onDismissMissingModelPrompt = viewModel::onDismissMissingModelPrompt,
                                onDismissVoiceOfflineGuidance = viewModel::onDismissVoiceOfflineGuidance,
                            )

                            AppTab.PHRASEBOOK -> PhrasebookScreen(
                                modifier = Modifier.fillMaxSize(),
                            )

                            AppTab.SIMULATION -> {
                                val simulationViewModel: SimulationViewModel = viewModel()
                                SimulationModeScreen(
                                    viewModel = simulationViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

// ===========================================================================
// Theme
// ===========================================================================

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    primaryContainer = Color(0xFF1B3A5A),
    onPrimaryContainer = Color(0xFFD5E5FF),
    secondary = Color(0xFF7EE0DE),
    secondaryContainer = Color(0xFF14494A),
    onSecondaryContainer = Color(0xFFC9F5F3),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF21252E),
    onSurface = Color(0xFFE2E6EC),
    onSurfaceVariant = Color(0xFFA4A9B3),
    error = Color(0xFFFF6B6B),
)

@Composable
fun VoiceTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}

// ===========================================================================
// Top-level app tabs (new "Fraseario" module lives alongside, never inside,
// the existing Traductor/En vivo/Subtítulos flow below).
// ===========================================================================

private enum class AppTab(val label: String) {
    TRANSLATOR("Traductor"),
    PHRASEBOOK("Fraseario"),
    SIMULATION("Modo Simulación"),
}

@Composable
private fun AppTabBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppTab.entries.forEach { tab ->
            val isSelected = tab == selected
            FilledTonalButton(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(tab.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ===========================================================================
// Main Screen
// ===========================================================================

@Composable
private fun TranslatorScreen(
    state: TranslatorUiState,
    onPermissionResult: (Boolean) -> Unit,
    onSpeakSpanish: () -> Unit,
    onListenJapanese: () -> Unit,
    onSelectLanguage: (TargetLanguage) -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onDownloadModel: () -> Unit,
    onReplay: () -> Unit,
    onSuggestionTapped: (String) -> Unit,
    onToggleSubtitles: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onTranslateSpanishText: (String) -> Unit,
    onToggleShowKana: (Boolean) -> Unit,
    onTranslateJapaneseText: (String) -> Unit,
    onToggleLiveConversation: () -> Unit,
    onLiveSpeakSpanish: () -> Unit,
    onLiveSuggestionTapped: (String) -> Unit,
    onToggleLiveListeningPause: () -> Unit,
    onDownloadOfflineModelFor: (TargetLanguage) -> Unit,
    onRefreshOfflineModels: () -> Unit,
    onConfirmDownloadMissingModel: () -> Unit,
    onDismissMissingModelPrompt: () -> Unit,
    onDismissVoiceOfflineGuidance: () -> Unit,
) {
    val context = LocalContext.current
    var permissionRequestStarted by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onPermissionResult(granted)
    }

    // Request recording permission automatically on first launch.
    LaunchedEffect(Unit) {
        if (!state.hasMicPermission && !permissionRequestStarted) {
            permissionRequestStarted = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val listeningSpanish = state.isListening && !state.isListeningForeign
    val listeningForeign = state.isListening && state.isListeningForeign

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeaderBar(
                selected = state.targetLanguage,
                onSelect = onSelectLanguage,
                offlineMode = state.isOfflineMode,
                modelDownloaded = state.isModelDownloaded,
                downloading = state.isDownloadingModel,
                downloadProgress = state.downloadProgress,
                subtitlesActive = state.isSubtitlesMode,
                hasHistory = state.historyList.isNotEmpty(),
                autoSpeakEnabled = state.isAutoSpeakEnabled,
                showKana = state.isShowKana,
                liveActive = state.isLiveConversation,
                onToggleLive = onToggleLiveConversation,
                onToggleOffline = onToggleOffline,
                onDownloadModel = onDownloadModel,
                onToggleSubtitles = onToggleSubtitles,
                onClearHistory = onClearHistory,
                onToggleAutoSpeak = onToggleAutoSpeak,
                onToggleShowKana = onToggleShowKana,
                offlineModels = state.offlineModels,
                onDownloadOfflineModelFor = onDownloadOfflineModelFor,
                onRefreshOfflineModels = onRefreshOfflineModels,
            )

            // Guidance banner for the "modo avión + voz offline no descargada"
            // case — replaces the confusing generic "idioma no reconocido"
            // error with an actionable message and a button straight to the
            // system's offline voice settings.
            state.voiceOfflineGuidance?.let { guidance ->
                Spacer(Modifier.size(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            guidance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.size(8.dp))
                        Row {
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                onDismissVoiceOfflineGuidance()
                            }) {
                                Text("Abrir Ajustes")
                            }
                            TextButton(onClick = onDismissVoiceOfflineGuidance) {
                                Text("Cerrar")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.size(16.dp))

            if (state.isLiveConversation) {
                // Live chat view: bubbles for TÚ/ELLOS plus the controls.
                LiveConversationView(
                    state = state,
                    onLiveSpeakSpanish = onLiveSpeakSpanish,
                    onLiveSuggestionTapped = onLiveSuggestionTapped,
                    onToggleLiveListeningPause = onToggleLiveListeningPause,
                    onTranslateSpanishText = onTranslateSpanishText,
                    onTranslateForeignText = onTranslateJapaneseText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                StatusIndicator(status = state.status)

                Spacer(Modifier.size(12.dp))

                TranslationDisplay(
                    state = state,
                    onReplay = onReplay,
                    onSuggestionTapped = onSuggestionTapped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (state.historyList.isNotEmpty()) 0.62f else 1f),
                )

                if (state.historyList.isNotEmpty()) {
                    Spacer(Modifier.size(12.dp))
                    HistoryFeed(
                        history = state.historyList,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.38f),
                    )
                }

                Spacer(Modifier.size(12.dp))

                ConversationMicRow(
                    enabled = state.hasMicPermission,
                    isBusy = state.isTranslating || state.isSpeaking,
                    listeningSpanish = listeningSpanish,
                    listeningForeign = listeningForeign,
                    targetLanguage = state.targetLanguage,
                    onSpeakSpanish = onSpeakSpanish,
                    onListenJapanese = onListenJapanese,
                    onTranslateSpanishText = onTranslateSpanishText,
                    onTranslateJapaneseText = onTranslateJapaneseText,
                )
            }
        }

        // "Anime Subtitles" fullscreen overlay, drawn on top of the standard UI.
        if (state.isSubtitlesMode) {
            SubtitlesOverlay(
                history = state.historyList,
                pendingResult = state.result?.mainTranslation,
                pendingRomaji = state.sourceRomaji ?: state.sourceText,
                isTranslating = state.isTranslating,
                onExit = onToggleSubtitles,
            )
        }
    }

    // "Falta el modelo offline de X. ¿Descargar ~30 MB?" — shown when a
    // translation was attempted in modo sin conexión before the model for
    // that language was ever downloaded (the exact "modo avión, idioma no
    // reconocido" case, but now named explicitly instead of failing silently).
    state.missingOfflineModelPrompt?.let { target ->
        AlertDialog(
            onDismissRequest = onDismissMissingModelPrompt,
            title = { Text("Falta el modelo offline") },
            text = {
                Text(
                    "Falta el modelo offline de ${target.displayName} " +
                        "(~${com.arnold.voicetranslator.data.offline.OfflineModelSize.approxMbFor(target)} MB). " +
                        "¿Descargarlo ahora para poder traducir sin conexión?",
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDownloadMissingModel) {
                    Text("Descargar")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissMissingModelPrompt) {
                    Text("Cancelar")
                }
            },
        )
    }
}

// ===========================================================================
// Controls
// ===========================================================================

/**
 * Compact top bar: action buttons (Subtítulos, clear history, settings) plus
 * a settings menu containing the language selector, offline mode and model
 * download options. Keeps the translation area as large as possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderBar(
    selected: TargetLanguage,
    onSelect: (TargetLanguage) -> Unit,
    offlineMode: Boolean,
    modelDownloaded: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    subtitlesActive: Boolean,
    hasHistory: Boolean,
    autoSpeakEnabled: Boolean,
    showKana: Boolean,
    liveActive: Boolean,
    onToggleLive: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onDownloadModel: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onToggleShowKana: (Boolean) -> Unit,
    offlineModels: Map<TargetLanguage, OfflineModelInfo>,
    onDownloadOfflineModelFor: (TargetLanguage) -> Unit,
    onRefreshOfflineModels: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Refresh the per-language download status whenever the menu is opened,
    // so "Modelos Offline" always reflects what's actually on disk.
    LaunchedEffect(menuOpen) {
        if (menuOpen) onRefreshOfflineModels()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Subtítulos toggle
            FilledTonalButton(
                onClick = onToggleSubtitles,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (subtitlesActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.0f)
                    },
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("Subtítulos")
            }

            Spacer(Modifier.width(4.dp))

            // Live conversation toggle
            FilledTonalButton(
                onClick = onToggleLive,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (liveActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.0f)
                    },
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (liveActive) "• En vivo" else "En vivo")
            }

            Spacer(Modifier.width(4.dp))

            // Clear history (only shown if there is history)
            if (hasHistory) {
                IconButton(
                    onClick = onClearHistory,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Borrar historial",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Settings menu (language + offline + download)
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    // Language selector
                    Text(
                        "Idioma de salida",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    TargetLanguage.entries.forEach { language ->
                        val isSelected = language == selected
                        DropdownMenuItem(
                            text = { Text("${language.flagEmoji} ${language.displayName}") },
                            leadingIcon = {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                onSelect(language)
                                menuOpen = false
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Hablar al traducir", Modifier.weight(1f))
                                Switch(
                                    checked = autoSpeakEnabled,
                                    onCheckedChange = onToggleAutoSpeak,
                                )
                            }
                        },
                        onClick = { menuOpen = false },
                    )
                    if (selected != TargetLanguage.ENGLISH) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (selected) {
                                            TargetLanguage.KOREAN -> "Mostrar coreano (hangul)"
                                            else -> "Mostrar japonés (kana)"
                                        },
                                        Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = showKana,
                                        onCheckedChange = onToggleShowKana,
                                    )
                                }
                            },
                            onClick = { menuOpen = false },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Modo sin conexión", Modifier.weight(1f))
                                Switch(
                                    checked = offlineMode,
                                    onCheckedChange = onToggleOffline,
                                )
                            }
                        },
                        onClick = { menuOpen = false },
                    )
                    val downloadEnabled = !modelDownloaded && !offlineMode
                    if (downloading) {
                        DropdownMenuItem(
                            text = {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("Descargando modelo…")
                                    Spacer(Modifier.size(6.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                            onClick = {},
                            enabled = false,
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Descargar modelo en línea") },
                            leadingIcon = {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onDownloadModel()
                            },
                            enabled = downloadEnabled,
                        )
                    }
                    if (modelDownloaded) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                            text = { Text("Modelo descargado") },
                            onClick = {},
                            enabled = false,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "Modelos Offline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Column(
                        modifier = Modifier
                            .width(280.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TargetLanguage.entries.forEach { lang ->
                            val info = offlineModels[lang] ?: OfflineModelInfo(lang)
                            OfflineModelCard(
                                language = lang,
                                info = info,
                                onDownload = { onDownloadOfflineModelFor(lang) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One card in the "Modelos Offline" settings section: language + flag,
 * download status ("Descargado" / "No descargado" / "Descargando… NN%"), and
 * a download button/progress bar.
 */
@Composable
private fun OfflineModelCard(
    language: TargetLanguage,
    info: OfflineModelInfo,
    onDownload: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${language.flagEmoji} ${language.displayName}", modifier = Modifier.weight(1f))
            when (info.status) {
                ModelDownloadStatus.DOWNLOADED -> {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = "Descargado",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Descargado", style = MaterialTheme.typography.labelSmall)
                }
                ModelDownloadStatus.DOWNLOADING -> {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Descargando… ${(info.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.size(4.dp))
                        LinearProgressIndicator(
                            progress = { info.progress },
                            modifier = Modifier.width(80.dp),
                        )
                    }
                }
                ModelDownloadStatus.NOT_DOWNLOADED -> {
                    Text(
                        "No descargado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    IconButton(onClick = onDownload, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Descargar modelo de ${language.displayName}",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: PipelineStatus) {
    val color = when (status) {
        PipelineStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        PipelineStatus.Listening -> Color(0xFFFFC107)
        PipelineStatus.Translating -> MaterialTheme.colorScheme.primary
        PipelineStatus.Speaking -> MaterialTheme.colorScheme.secondary
    }
    AnimatedContent(targetState = status, label = "status") { current ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                current.statusText,
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        }
    }
}

// ===========================================================================
// Translation display
// ===========================================================================

@Composable
private fun TranslationDisplay(
    state: TranslatorUiState,
    onReplay: () -> Unit,
    onSuggestionTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.result

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Top so that overflowing content (long replies + cards) is fully
            // reachable by scrolling; Center would clip the top of long content.
            verticalArrangement = Arrangement.Top,
        ) {
            when {
                state.isTranslating && result == null -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "Traduciendo...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                result == null -> {
                    Text(
                        "Pulsa el micrófono y habla en español.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    // When listening to the foreign speaker's language, echo
                    // what the user heard (kana + romaji for Japanese; hangul
                    // alone for Korean) in gray above the Spanish translation.
                    if (state.isListeningForeign) {
                        val heardText = state.sourceText
                        val heardRomaji = state.sourceRomaji
                        if (heardText != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "Escuchaste:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (heardRomaji != null && heardRomaji != heardText) {
                                        Spacer(Modifier.size(4.dp))
                                        Text(
                                            text = heardText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                        Spacer(Modifier.size(2.dp))
                                        Text(
                                            text = heardRomaji,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                    } else {
                                        Spacer(Modifier.size(4.dp))
                                        Text(
                                            text = heardText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.size(16.dp))
                        }
                    }

                    Text(
                        text = result.mainTranslation,
                        style = MaterialTheme.typography.headlineLarge,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 38.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Spanish -> foreign direction: also show the native
                    // script (kana/kanji for Japanese, Hangul for Korean)
                    // right below the Romaji/phonetic reading, gated by the
                    // "Mostrar japonés (kana)" / "Mostrar coreano (hangul)"
                    // toggle — same script the local speaker would actually
                    // read, instead of only the Spanish-friendly reading.
                    if (!state.isListeningForeign && state.isShowKana) {
                        result.nativeScript
                            ?.takeIf { it.isNotBlank() && it != result.mainTranslation }
                            ?.let { native ->
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = native,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                    }

                    if (state.isListeningForeign) {
                        val structured = result.replySuggestions
                        if (structured.isNotEmpty()) {
                            ReplySuggestionCards(
                                suggestions = structured,
                                showKana = state.isShowKana,
                                onSuggestionTapped = { romaji -> onSuggestionTapped(romaji) },
                                enabled = !state.isSpeaking,
                            )
                        } else {
                            ResultSuggestions(
                                suggestions = result.alternatives,
                                onSuggestionTapped = onSuggestionTapped,
                                enabled = !state.isSpeaking,
                            )
                        }
                    } else if (result.alternatives.isNotEmpty()) {
                        Spacer(Modifier.size(20.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                "Alternativas",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            result.alternatives.forEach { alt ->
                                Text(
                                    "•  $alt",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.size(16.dp))
                    FilledTonalButton(
                        onClick = onReplay,
                        enabled = !state.isSpeaking,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reproducir")
                    }
                }
            }

            state.errorMessage?.let { message ->
                Spacer(Modifier.size(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ===========================================================================
// Suggested Romaji replies (Two-Way Conversation mode)
// ===========================================================================

/**
 * Renders the assistant's suggested Romaji reply chips. Tapping one speaks it
 * out loud in Japanese for the local speaker to hear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSuggestions(
    suggestions: List<String>,
    onSuggestionTapped: (String) -> Unit,
    enabled: Boolean,
) {
    if (suggestions.isEmpty()) return

    Spacer(Modifier.size(16.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Responder en japonés",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.size(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestionTapped(suggestion) },
                    enabled = enabled,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(suggestion, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Leer en japonés",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }
    }
}

/**
 * Renders the suggested replies in "Escuchar Japonés" mode as selectable cards
 * (like a dating-sim dialog choice): the Romaji phrase on top and its Spanish
 * meaning below. Tapping a card reads the Romaji out loud in Japanese.
 */
@Composable
private fun ReplySuggestionCards(
    suggestions: List<ReplySuggestion>,
    showKana: Boolean,
    onSuggestionTapped: (String) -> Unit,
    enabled: Boolean,
) {
    if (suggestions.isEmpty()) return

    Spacer(Modifier.size(16.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Elige cómo responder",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.size(10.dp))
        // No nested verticalScroll here: the surrounding TranslationDisplay panel
        // is already scrollable and would otherwise crash with infinite-height
        // constraints. Cards simply stack and the parent scroll handles overflow.
        Column(modifier = Modifier.fillMaxWidth()) {
            suggestions.forEach { suggestion ->
                val tapKey = suggestion.romaji.ifBlank { suggestion.kana.ifBlank { suggestion.spanish } }
                Surface(
                    onClick = { onSuggestionTapped(tapKey) },
                    enabled = enabled,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        // Japanese kana/kanji first when the toggle is on, so a
                        // native speaker can read it back.
                        if (showKana && suggestion.kana.isNotBlank()) {
                            Text(
                                text = suggestion.kana,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(2.dp))
                        }
                        // Only show the romaji line if non-blank and actually
                        // distinct from the Spanish meaning below — avoids
                        // the "both show Spanish" bug when the model put the
                        // wrong content in the romaji slot.
                        if (suggestion.romaji.isNotBlank() &&
                            !suggestion.romaji.trim().equals(suggestion.spanish.trim(), ignoreCase = true)
                        ) {
                            Text(
                                text = suggestion.romaji,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.size(2.dp))
                        }
                        if (suggestion.spanish.isNotBlank()) {
                            Text(
                                text = suggestion.spanish,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===========================================================================
// Two-way microphone row
// ===========================================================================

/** IME "hint locale" per target language, so multilingual keyboards (Gboard,
 *  etc.) auto-switch to the right language/script (kana for Japanese, hangul
 *  for Korean) as soon as the field is focused, instead of defaulting to
 *  whatever layout was last used. */
private fun imeHintLocaleFor(target: TargetLanguage): LocaleList = when (target) {
    TargetLanguage.JAPANESE -> LocaleList(Locale("ja"))
    TargetLanguage.KOREAN -> LocaleList(Locale("ko"))
    TargetLanguage.ENGLISH -> LocaleList(Locale("en"))
}

private val SPANISH_IME_HINT = LocaleList(Locale("es"))

/**
 * A single "type it instead of speaking" dialog: auto-focuses its text field
 * and opens the keyboard immediately (no extra tap needed), and hints the
 * IME to switch to [hintLocale]'s language/script right away.
 */
@Composable
private fun TypingDialog(
    title: String,
    hint: String,
    label: String,
    hintLocale: LocaleList,
    typedText: String,
    onTypedTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = typedText,
                    onValueChange = onTypedTextChange,
                    label = { Text(label) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(hintLocales = hintLocale),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Traducir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/**
 * Pair of mic buttons for the Two-Way Conversation:
 *  - "Hablar en Español"  -> hears Spanish, outputs to the target language.
 *  - "Escuchar <Idioma>"  -> hears the foreign language (Japanese/Korean,
 *    per [targetLanguage]), outputs Spanish + transliterated reply cards.
 *
 * A small "type it instead" (pen) button next to each mic opens a dialog
 * so the user can write the phrase manually when speech recognition mishears it.
 */
@Composable
private fun ConversationMicRow(
    enabled: Boolean,
    isBusy: Boolean,
    listeningSpanish: Boolean,
    listeningForeign: Boolean,
    targetLanguage: TargetLanguage,
    onSpeakSpanish: () -> Unit,
    onListenJapanese: () -> Unit,
    onTranslateSpanishText: (String) -> Unit,
    onTranslateJapaneseText: (String) -> Unit,
) {
    var showSpanishDialog by remember { mutableStateOf(false) }
    var showForeignDialog by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }

    val listenLabel = when (targetLanguage) {
        TargetLanguage.KOREAN -> "Escuchar Coreano"
        TargetLanguage.ENGLISH -> "Escuchar Inglés"
        TargetLanguage.JAPANESE -> "Escuchar Japonés"
    }
    val foreignLangName = when (targetLanguage) {
        TargetLanguage.KOREAN -> "coreano"
        TargetLanguage.ENGLISH -> "inglés"
        TargetLanguage.JAPANESE -> "japonés"
    }
    val foreignHeading = when (targetLanguage) {
        TargetLanguage.KOREAN -> "Escribir en coreano"
        TargetLanguage.ENGLISH -> "Escribir en inglés"
        TargetLanguage.JAPANESE -> "Escribir en japonés"
    }
    val foreignHint = when (targetLanguage) {
        TargetLanguage.KOREAN -> "El reconocimiento de voz a veces falla. Escribe la frase en coreano (한글) o en fonética."
        TargetLanguage.ENGLISH -> "El reconocimiento de voz a veces falla. Escribe la frase en inglés."
        TargetLanguage.JAPANESE -> "El reconocimiento de voz a veces falla. Escribe la frase, sea en japonés (かな/漢字) o en romaji."
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                ModeMicButton(
                    label = "Hablar en Español",
                    listening = listeningSpanish,
                    icon = { Icons.Default.RecordVoiceOver },
                    color = MaterialTheme.colorScheme.primary,
                    enabled = enabled,
                    isBusy = isBusy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = onSpeakSpanish,
                )
                TextButton(
                    onClick = {
                        if (!isBusy) {
                            typedText = ""
                            showSpanishDialog = true
                        }
                    },
                    enabled = enabled && !isBusy,
                ) {
                    Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Escribir", style = MaterialTheme.typography.labelSmall)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                ModeMicButton(
                    label = listenLabel,
                    listening = listeningForeign,
                    icon = { Icons.Default.Hearing },
                    color = MaterialTheme.colorScheme.tertiary,
                    enabled = enabled,
                    isBusy = isBusy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = onListenJapanese,
                )
                TextButton(
                    onClick = {
                        if (!isBusy) {
                            typedText = ""
                            showForeignDialog = true
                        }
                    },
                    enabled = enabled && !isBusy,
                ) {
                    Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Escribir", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (showSpanishDialog) {
            TypingDialog(
                title = "Escribir en español",
                hint = "El reconocimiento de voz a veces falla. Escribe la frase que quieres traducir.",
                label = "Frase en español",
                hintLocale = SPANISH_IME_HINT,
                typedText = typedText,
                onTypedTextChange = { typedText = it },
                onDismiss = { showSpanishDialog = false },
                onConfirm = {
                    showSpanishDialog = false
                    onTranslateSpanishText(typedText)
                },
            )
        }

        if (showForeignDialog) {
            TypingDialog(
                title = foreignHeading,
                hint = foreignHint,
                label = "Frase en $foreignLangName",
                hintLocale = imeHintLocaleFor(targetLanguage),
                typedText = typedText,
                onTypedTextChange = { typedText = it },
                onDismiss = { showForeignDialog = false },
                onConfirm = {
                    showForeignDialog = false
                    onTranslateJapaneseText(typedText)
                },
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModeMicButton(
    label: String,
    listening: Boolean,
    icon: @Composable () -> ImageVector,
    color: Color,
    enabled: Boolean,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val baseColor = color.copy(alpha = if (enabled) 1f else 0.4f)
        Surface(
            onClick = onClick,
            enabled = enabled && !isBusy,
            shape = CircleShape,
            color = baseColor,
            tonalElevation = 0.dp,
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Crossfade(targetState = listening, label = "micToggle") { isListeningNow ->
                    Icon(
                        imageVector = if (isListeningNow) Icons.Default.Stop else icon(),
                        contentDescription = if (isListeningNow) "Detener $label" else label,
                        modifier = Modifier.size(32.dp),
                        tint = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Surface(
            color = if (listening) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else Color.Transparent,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = if (listening) "Detener" else label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ===========================================================================
// Conversation history feed
// ===========================================================================

/**
 * Scrollable list of previous exchanges, shown below the main translation so
 * the user can look back at what was said.
 */
@Composable
private fun HistoryFeed(
    history: List<TranslationHistoryItem>,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            "Historial",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.size(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history.size, key = { it }) { index ->
                val item = history[history.size - 1 - index] // newest first
                HistoryRow(item)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: TranslationHistoryItem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = item.sourceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.sourceRomaji?.takeIf { it != item.sourceText }?.let { romaji ->
                Text(
                    text = romaji,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = item.translation,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ===========================================================================
// "Anime Subtitles" fullscreen overlay
// ===========================================================================

/**
 * Immersive dark overlay that mimics anime subtitles: a growing scrolling log
 * of every translation said so far (so people don't lose the thread of the
 * conversation), auto-scrolled to the newest line at the bottom. The most
 * recent line is shown large/bright like a live subtitle; earlier lines
 * scroll up, dimmer, as a running transcript.
 */
@Composable
private fun SubtitlesOverlay(
    history: List<TranslationHistoryItem>,
    pendingResult: String?,
    pendingRomaji: String?,
    isTranslating: Boolean,
    onExit: () -> Unit,
) {
    val listState = rememberLazyListState()
    // historyList already includes the latest completed translation the
    // instant it lands (added in the same state update as `result`), so
    // showing history + the in-flight pending item would duplicate the last
    // line. Only show a "…" placeholder while a translation is still running.
    val itemCount = history.size + if (isTranslating) 1 else 0

    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(24.dp),
    ) {
        // Exit button
        Surface(
            onClick = onExit,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Salir de subtítulos",
                tint = Color.White,
                modifier = Modifier.padding(10.dp).size(20.dp),
            )
        }

        // Growing subtitle log, bottom-aligned, newest line largest/brightest.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            if (history.isEmpty() && !isTranslating) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFE066),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(history, key = { index, _ -> index }) { index, item ->
                        val isLatest = index == history.lastIndex && !isTranslating
                        SubtitleLine(
                            text = item.translation,
                            romaji = item.sourceRomaji?.takeIf { it != item.sourceText } ?: item.sourceText,
                            emphasized = isLatest,
                        )
                    }
                    if (isTranslating) {
                        item(key = "pending") {
                            SubtitleLine(text = "…", romaji = pendingRomaji, emphasized = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleLine(text: String, romaji: String?, emphasized: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontSize = if (emphasized) 26.sp else 19.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            color = if (emphasized) Color(0xFFFFE066) else Color(0xFFFFE066).copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            lineHeight = if (emphasized) 34.sp else 26.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!romaji.isNullOrBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = romaji,
                style = MaterialTheme.typography.titleMedium,
                fontSize = if (emphasized) 18.sp else 14.sp,
                color = Color(0xFFB0B6C0).copy(alpha = if (emphasized) 1f else 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ===========================================================================
// Live conversation (continuous two-way chat)
// ===========================================================================

/**
 * The live conversation screen: a WhatsApp-style chat of bubbles (TÚ on the
 * right, ELLOS on the left), a live transcript of whichever side is being
 * heard, tappable suggested replies for the ELLOS turn, and a mic to speak
 * Spanish (TÚ). The loop auto-advances: after the user's reply is read aloud
 * the app returns to listen for the foreign speaker.
 */
@Composable
private fun LiveConversationView(
    state: TranslatorUiState,
    onLiveSpeakSpanish: () -> Unit,
    onLiveSuggestionTapped: (String) -> Unit,
    onToggleLiveListeningPause: () -> Unit,
    onTranslateSpanishText: (String) -> Unit,
    onTranslateForeignText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSpanishDialog by remember { mutableStateOf(false) }
    var showForeignDialog by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    // Lets the user hide the (sometimes wrong, since STT can mishear what
    // was said) reply suggestion cards without needing to wait for them to
    // disappear on their own — a manual escape hatch for bad suggestions.
    var suggestionsHidden by remember { mutableStateOf(false) }

    val foreignLabel = when (state.targetLanguage) {
        TargetLanguage.KOREAN -> "Coreano"
        TargetLanguage.ENGLISH -> "Inglés"
        TargetLanguage.JAPANESE -> "Japonés"
    }
    val foreignHint = when (state.targetLanguage) {
        TargetLanguage.KOREAN -> "Escribe la frase en coreano (한글) o en fonética."
        TargetLanguage.ENGLISH -> "Escribe la frase en inglés."
        TargetLanguage.JAPANESE -> "Escribe la frase, sea en japonés (かな/漢字) o en romaji."
    }

    Column(modifier = modifier) {

        // Turn indicator
        val turnText = when {
            state.isLiveListeningPaused -> "Escucha de $foreignLabel en pausa"
            state.liveTurn == LiveTurn.YOU -> "Estás hablando…"
            state.liveTurn == LiveTurn.THEM -> "Escuchando $foreignLabel…"
            else -> "Conversación en vivo"
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            state.isLiveListeningPaused -> MaterialTheme.colorScheme.onSurfaceVariant
                            state.liveTurn == LiveTurn.THEM -> Color(0xFFFFC107)
                            else -> MaterialTheme.colorScheme.primary
                        },
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                turnText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // Pause/resume the continuous foreign listening.
            FilledTonalButton(
                onClick = onToggleLiveListeningPause,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (state.isLiveListeningPaused) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    if (state.isLiveListeningPaused) "Reanudar" else "Pausar",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // Live transcript while listening/speaking
        if (state.liveTranscript.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(
                state.liveTranscript,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.size(12.dp))

        // Chat bubbles
        if (state.liveMessages.isEmpty() && state.result == null && !state.isTranslating) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Pulsa el botón de abajo para hablar,\no toca una sugerencia para responder.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val listState = rememberLazyListState()
            val messages = state.liveMessages

            // Auto-scroll so the newest message (the user's own speech OR the
            // incoming translation) is always revealed at the bottom, like any
            // normal chat app — old messages get pushed up out of view. Always
            // scrolling (rather than only when "near the bottom") keeps this
            // simple and reliable; a chat this size has no real "scroll up to
            // read history and don't get yanked back" use case yet.
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    // scrollToItem first (instant, guaranteed) then an animated
                    // pass on top — belt-and-suspenders in case the instant
                    // jump alone doesn't get re-measured before the animation
                    // starts (has happened with LazyColumn + fast list growth).
                    listState.scrollToItem(messages.lastIndex)
                    listState.animateScrollToItem(messages.lastIndex)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(messages, key = { it.id }) { entry ->
                    LiveChatBubble(entry, showKana = state.isShowKana)
                }
            }
        }

        // Surfaced here (not just in the non-Live TranslationDisplay) so a
        // failed turn — e.g. the Worker's daily rate limit — is never a
        // silent dead end while in Live mode; previously it only updated
        // state.errorMessage with nothing on screen to show it.
        state.errorMessage?.let { message ->
            Spacer(Modifier.size(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.size(12.dp))

        // Suggested replies for the ELLOS turn
        val suggestions = state.result?.replySuggestions
        if (state.liveTurn == LiveTurn.THEM && suggestions?.isNotEmpty() == true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sugerencias de respuesta",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { suggestionsHidden = !suggestionsHidden }) {
                    Text(
                        if (suggestionsHidden) "Mostrar" else "Ocultar",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (!suggestionsHidden) {
                LiveSuggestionCards(
                    suggestions = suggestions,
                    showKana = state.isShowKana,
                    onSuggestionTapped = onLiveSuggestionTapped,
                    enabled = !state.isSpeaking,
                )
            }
            Spacer(Modifier.size(12.dp))
        }

        // Speak Spanish (TÚ) mic button + manual text-input fallbacks, for
        // when the other person would rather grab the phone and type than
        // rely on speech recognition (mishears, noisy environment, etc.).
        LiveMicBar(
            isListening = state.isListening && state.liveTurn == LiveTurn.YOU,
            busy = state.isTranslating || state.isSpeaking,
            onSpeakSpanish = onLiveSpeakSpanish,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = { typedText = ""; showSpanishDialog = true }) {
                Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Escribir en español", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { typedText = ""; showForeignDialog = true }) {
                Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Escribir en $foreignLabel", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showSpanishDialog) {
        TypingDialog(
            title = "Escribir en español",
            hint = "Útil cuando la otra persona prefiere escribir en tu celular en vez de hablar.",
            label = "Frase en español",
            hintLocale = SPANISH_IME_HINT,
            typedText = typedText,
            onTypedTextChange = { typedText = it },
            onDismiss = { showSpanishDialog = false },
            onConfirm = {
                showSpanishDialog = false
                onTranslateSpanishText(typedText)
            },
        )
    }

    if (showForeignDialog) {
        TypingDialog(
            title = "Escribir en $foreignLabel",
            hint = foreignHint,
            label = "Frase en $foreignLabel",
            hintLocale = imeHintLocaleFor(state.targetLanguage),
            typedText = typedText,
            onTypedTextChange = { typedText = it },
            onDismiss = { showForeignDialog = false },
            onConfirm = {
                showForeignDialog = false
                onTranslateForeignText(typedText)
            },
        )
    }
}

/** A single chat bubble: TÚ right-aligned (primary), ELLOS left-aligned. */
@Composable
private fun LiveChatBubble(entry: LiveChatEntry, showKana: Boolean) {
    val isYou = entry.turn == LiveTurn.YOU
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isYou) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isYou) 16.dp else 4.dp,
                topEnd = if (isYou) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            color = if (isYou) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    (if (isYou) "TÚ" else "ELLOS"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Kana/kanji companion for `text` (used by the tapped-suggestion
                // bubble, where `text` holds the Japanese Romaji being sent).
                if (showKana) {
                    entry.textKana?.takeIf { it.isNotBlank() && it != entry.text }?.let { kana ->
                        Text(
                            kana,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                entry.sourceRomaji?.takeIf { it != entry.text }?.let { romaji ->
                    Spacer(Modifier.size(2.dp))
                    Text(
                        romaji,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    entry.translation,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Kana/kanji companion for `translation` — shown for the TÚ
                // turn's own JP output (Google/Kuromoji Romaji) so a native
                // speaker can read the original script too, gated by the same
                // "Mostrar kana" toggle used for ELLOS's reply suggestions.
                if (showKana) {
                    entry.translationKana?.takeIf { it.isNotBlank() && it != entry.translation }?.let { kana ->
                        Spacer(Modifier.size(2.dp))
                        Text(
                            kana,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vertical stack of tappable reply suggestions shown during the ELLOS turn.
 * Top = foreign phrase (romaji, + kana/kanji if toggled), bottom = Spanish meaning.
 */
@Composable
private fun LiveSuggestionCards(
    suggestions: List<com.arnold.voicetranslator.data.model.ReplySuggestion>,
    showKana: Boolean,
    onSuggestionTapped: (String) -> Unit,
    enabled: Boolean,
) {
    suggestions.take(3).forEach { suggestion ->
        // Defensive fallback in the UI too: if the romaji field ended up
        // blank (the ViewModel couldn't regenerate a valid Latin-script
        // reading), never leave the card with nothing tappable/speakable —
        // fall back to the kana/native script, then the Spanish meaning.
        val tapKey = suggestion.romaji.ifBlank { suggestion.kana.ifBlank { suggestion.spanish } }
        Surface(
            onClick = { onSuggestionTapped(tapKey) },
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (showKana && suggestion.kana.isNotBlank()) {
                    Text(
                        suggestion.kana,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(2.dp))
                }
                // Only show the romaji line if it's non-blank and actually
                // distinct from the Spanish meaning below — otherwise it's
                // the "both cards show Spanish" bug (romaji slot ended up
                // wrong/duplicated), and showing it would just repeat the
                // Spanish text twice.
                if (suggestion.romaji.isNotBlank() &&
                    !suggestion.romaji.trim().equals(suggestion.spanish.trim(), ignoreCase = true)
                ) {
                    Text(
                        suggestion.romaji,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(2.dp))
                }
                if (suggestion.spanish.isNotBlank()) {
                    Text(
                        suggestion.spanish,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Bottom bar with the "Hablar en Español" mic (TÚ turn). */
@Composable
private fun LiveMicBar(
    isListening: Boolean,
    busy: Boolean,
    onSpeakSpanish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeMicButton(
            label = "Hablar en Español",
            listening = isListening,
            icon = { Icons.Default.RecordVoiceOver },
            color = MaterialTheme.colorScheme.primary,
            enabled = true,
            isBusy = busy,
            onClick = onSpeakSpanish,
        )
    }
}

