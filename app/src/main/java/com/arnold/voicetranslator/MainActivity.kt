package com.arnold.voicetranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnold.voicetranslator.data.model.ReplySuggestion
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.ui.MainViewModel
import com.arnold.voicetranslator.ui.state.LiveChatEntry
import com.arnold.voicetranslator.ui.state.LiveTurn
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
                TranslatorScreen(
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
                )
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
) {
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
            )

            Spacer(Modifier.size(16.dp))

            if (state.isLiveConversation) {
                // Live chat view: bubbles for TÚ/ELLOS plus the controls.
                LiveConversationView(
                    state = state,
                    onLiveSpeakSpanish = onLiveSpeakSpanish,
                    onLiveSuggestionTapped = onLiveSuggestionTapped,
                    onToggleLiveListeningPause = onToggleLiveListeningPause,
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
                result = state.result?.mainTranslation,
                romaji = state.sourceRomaji ?: state.sourceText,
                onExit = onToggleSubtitles,
            )
        }
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
) {
    var menuOpen by remember { mutableStateOf(false) }

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
                Surface(
                    onClick = { onSuggestionTapped(suggestion.romaji) },
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
                        Text(
                            text = suggestion.romaji,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (suggestion.spanish.isNotBlank()) {
                            Spacer(Modifier.size(2.dp))
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
            AlertDialog(
                onDismissRequest = { showSpanishDialog = false },
                title = { Text("Escribir en español") },
                text = {
                    Column {
                        Text(
                            "El reconocimiento de voz a veces falla. Escribe la frase que quieres traducir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        OutlinedTextField(
                            value = typedText,
                            onValueChange = { typedText = it },
                            label = { Text("Frase en español") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSpanishDialog = false
                            onTranslateSpanishText(typedText)
                        },
                    ) {
                        Text("Traducir")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSpanishDialog = false }) {
                        Text("Cancelar")
                    }
                },
            )
        }

        if (showForeignDialog) {
            AlertDialog(
                onDismissRequest = { showForeignDialog = false },
                title = { Text(foreignHeading) },
                text = {
                    Column {
                        Text(
                            foreignHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        OutlinedTextField(
                            value = typedText,
                            onValueChange = { typedText = it },
                            label = { Text("Frase en $foreignLangName") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showForeignDialog = false
                            onTranslateJapaneseText(typedText)
                        },
                    ) {
                        Text("Traducir")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showForeignDialog = false }) {
                        Text("Cancelar")
                    }
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
 * Immersive dark overlay that mimics anime subtitles: the Mexican Spanish
 * translation in large bold white/yellow text, with the Romaji reading in
 * smaller gray text below, inside a semi-transparent banner at the bottom.
 */
@Composable
private fun SubtitlesOverlay(
    result: String?,
    romaji: String?,
    onExit: () -> Unit,
) {
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

        // Bottom subtitle banner
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = result ?: "…",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFE066), // anime-style yellow
                textAlign = TextAlign.Center,
                lineHeight = 34.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            if (romaji != null && romaji.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = romaji,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp,
                    color = Color(0xFFB0B6C0), // gray
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val foreignLabel = when (state.targetLanguage) {
            TargetLanguage.KOREAN -> "Coreano"
            TargetLanguage.ENGLISH -> "Inglés"
            TargetLanguage.JAPANESE -> "Japonés"
        }

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.liveMessages.size, key = { it }) { index ->
                    LiveChatBubble(state.liveMessages[index])
                }
            }
        }

        Spacer(Modifier.size(12.dp))

        // Suggested replies for the ELLOS turn
        val suggestions = state.result?.replySuggestions
        if (state.liveTurn == LiveTurn.THEM && suggestions?.isNotEmpty() == true) {
            LiveSuggestionCards(
                suggestions = suggestions,
                showKana = state.isShowKana,
                onSuggestionTapped = onLiveSuggestionTapped,
                enabled = !state.isSpeaking,
            )
            Spacer(Modifier.size(12.dp))
        }

        // Speak Spanish (TÚ) mic button
        LiveMicBar(
            isListening = state.isListening && state.liveTurn == LiveTurn.YOU,
            busy = state.isTranslating || state.isSpeaking,
            onSpeakSpanish = onLiveSpeakSpanish,
        )
    }
}

/** A single chat bubble: TÚ right-aligned (primary), ELLOS left-aligned. */
@Composable
private fun LiveChatBubble(entry: LiveChatEntry) {
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
        Surface(
            onClick = { onSuggestionTapped(suggestion.romaji) },
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
                Text(
                    suggestion.romaji,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (suggestion.spanish.isNotBlank()) {
                    Spacer(Modifier.size(2.dp))
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

