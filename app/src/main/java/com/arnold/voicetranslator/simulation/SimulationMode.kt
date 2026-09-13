package com.arnold.voicetranslator.simulation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Standalone screen for "Modo Simulación" (Simulation Mode).
 *
 * 100% independent from the rest of the app's UI: it only reads/writes
 * [SimulationViewModel]'s own state and never touches
 * [com.arnold.voicetranslator.ui.MainViewModel] or
 * [com.arnold.voicetranslator.ui.state.TranslatorUiState] — Live Conversation,
 * Subtitles ("Anime Subtitles"), and Fraseario are guaranteed untouched.
 *
 * Layout, top to bottom (per spec):
 *  1. Collapsible "Escenario" + "Idioma a practicar" selector cards (JA/KO/ZH/EN).
 *  2. Chat area: both user and AI bubbles show native text + romanization +
 *     Spanish meaning — the "regla de oro visual" for the AI (native script
 *     ALWAYS shown large, never romaji-only) and, since the UX fix, the same
 *     3-line layout (mirrored) for the user's own turn.
 *  3. Optional "Sugerencias" chip row for beginners, hideable/persisted.
 *  4. Text input (+ optional mic) at the bottom.
 *  5. "Terminar y dar feedback" button that requests a level assessment.
 *
 * Errors are shown as a transient [SnackbarHost] (auto-dismiss) instead of a
 * permanent red [Text] block that used to stay on screen blocking the layout.
 */
@Composable
fun SimulationModeScreen(
    viewModel: SimulationViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(granted)
    }

    // Fix: errorMessage used to render as a permanent red Text that stayed on
    // screen (covering layout) until the next action cleared it. Now it's a
    // transient Snackbar that auto-dismisses after a few seconds.
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.onDismissError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SimulationSelectors(
                selectedScenario = state.scenario,
                selectedLanguage = state.language,
                onSelectScenario = viewModel::onSelectScenario,
                onSelectLanguage = viewModel::onSelectLanguage,
            )

            Spacer(Modifier.size(10.dp))

            SimulationChatArea(
                messages = state.messages,
                isSending = state.isSending,
                language = state.language,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Spacer(Modifier.size(8.dp))

            SimulationSuggestions(
                visible = state.suggestionsVisible,
                suggestions = state.scenario.starterSuggestions,
                onSuggestionClick = viewModel::onUseSuggestion,
                onToggleVisible = viewModel::onToggleSuggestions,
            )

            Spacer(Modifier.size(10.dp))

            SimulationInputBar(
                inputText = state.inputText,
                isSending = state.isSending,
                isListening = state.isListening,
                hasMicPermission = state.hasMicPermission,
                language = state.language,
                onInputTextChange = viewModel::onInputTextChange,
                onSend = viewModel::onSendMessage,
                onMicToggle = {
                    if (!state.hasMicPermission) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.onMicToggle()
                    }
                },
            )

            Spacer(Modifier.size(10.dp))

            FilledTonalButton(
                onClick = viewModel::onFinishAndGetFeedback,
                enabled = state.messages.isNotEmpty() && !state.isGeneratingFeedback,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.isGeneratingFeedback) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Analizando tu nivel…")
                } else {
                    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Terminar y dar feedback")
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }

    state.feedback?.let { feedback ->
        AlertDialog(
            onDismissRequest = viewModel::onDismissFeedback,
            title = { Text("Tu nivel en ${state.language.displayName}") },
            text = { Text(feedback, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissFeedback) { Text("Cerrar") }
            },
        )
    }
}

// ===========================================================================
// Scenario + language selectors (collapsible)
// ===========================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SimulationSelectors(
    selectedScenario: SimulationScenario,
    selectedLanguage: SimulationLanguage,
    onSelectScenario: (SimulationScenario) -> Unit,
    onSelectLanguage: (SimulationLanguage) -> Unit,
) {
    // Fix: Escenario + Idioma used to always take up half the screen with no
    // way to collapse them. Each is now its own collapsible Card (expanded by
    // default), so the chat area gains space once the user picks both.
    var isScenarioExpanded by rememberSaveable { mutableStateOf(true) }
    var isLanguageExpanded by rememberSaveable { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        CollapsibleSection(
            title = "Escenario: ${selectedScenario.emoji} ${selectedScenario.displayName}",
            expanded = isScenarioExpanded,
            onToggle = { isScenarioExpanded = !isScenarioExpanded },
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SimulationScenario.entries.forEach { scenario ->
                    FilterChip(
                        selected = scenario == selectedScenario,
                        onClick = { onSelectScenario(scenario) },
                        label = { Text("${scenario.emoji} ${scenario.displayName}") },
                        modifier = Modifier.height(36.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.size(8.dp))

        CollapsibleSection(
            title = "Idioma: ${selectedLanguage.flagEmoji} ${selectedLanguage.displayName}",
            expanded = isLanguageExpanded,
            onToggle = { isLanguageExpanded = !isLanguageExpanded },
        ) {
            // Fix: with 4 languages now (JA/KO/ZH/EN), a plain fillMaxWidth
            // Row without a fixed/intrinsic height let the "Inglés" chip
            // stretch to the row's height. height(IntrinsicSize.Min) on the
            // row + an explicit fixed height on every chip keeps them all
            // the same short pill size; horizontalScroll keeps 4 chips from
            // ever overflowing/wrapping awkwardly on narrow screens.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SimulationLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = language == selectedLanguage,
                        onClick = { onSelectLanguage(language) },
                        label = { Text("${language.flagEmoji} ${language.displayName}") },
                        modifier = Modifier.height(36.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

/** Card wrapper with a tappable header (title + expand/collapse arrow). */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Colapsar" else "Expandir",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.size(8.dp))
                content()
            }
        }
    }
}

// ===========================================================================
// Beginner suggestions row
// ===========================================================================

/**
 * Static, per-scenario starter phrases for beginners who don't know what to
 * say next ("no saben que decir después"). Tapping one prefills the input;
 * the user still has to tap send. Hideable for advanced users — persisted in
 * SharedPreferences via [SimulationViewModel.onToggleSuggestions] so it stays
 * hidden across sessions once dismissed.
 */
@Composable
private fun SimulationSuggestions(
    visible: Boolean,
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onToggleVisible: () -> Unit,
) {
    if (!visible) {
        TextButton(onClick = onToggleVisible) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Mostrar sugerencias", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "💡 Sugerencias",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onToggleVisible, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Ocultar sugerencias",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onSuggestionClick(suggestion) },
                    label = { Text(suggestion, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

// ===========================================================================
// Chat area
// ===========================================================================

@Composable
private fun SimulationChatArea(
    messages: List<SimulationMessage>,
    isSending: Boolean,
    language: SimulationLanguage,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        if (messages.isEmpty() && !isSending) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Escribe (o habla) en ${language.displayName} para empezar a " +
                        "practicar en este escenario.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, isSending) {
                val lastIndex = messages.size - 1 + if (isSending) 1 else 0
                if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    SimulationChatBubble(message)
                }
                if (isSending) {
                    item(key = "typing") { SimulationTypingBubble() }
                }
            }
        }
    }
}

/**
 * Chat bubble for both senders. Fix: the user bubble used to show ONLY the
 * raw target-language text with no romaji/translation. Now — mirroring the
 * AI bubble — it also shows [SimulationMessage.romanized] (small, gray) and
 * [SimulationMessage.spanishMeaning] (smaller, gray) once the Worker's
 * `/simulate` response backfills them (see [SimulationViewModel.onSendMessage]).
 */
@Composable
private fun SimulationChatBubble(message: SimulationMessage) {
    val isUser = message.sender == SimulationSender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isUser) {
                    Text(
                        text = message.spanishText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (message.romanized.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = message.romanized,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    if (message.spanishMeaning.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = message.spanishMeaning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                } else {
                    // Regla de oro visual: SIEMPRE kana/hangul grande, nunca solo romaji.
                    Text(
                        text = message.nativeScript.ifBlank { "…" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (message.romanized.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = message.romanized,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (message.spanishMeaning.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = message.spanishMeaning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimulationTypingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Escribiendo…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ===========================================================================
// Input bar
// ===========================================================================

@Composable
private fun SimulationInputBar(
    inputText: String,
    isSending: Boolean,
    isListening: Boolean,
    hasMicPermission: Boolean,
    language: SimulationLanguage,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputTextChange,
            modifier = Modifier.weight(1f),
            // Fix: hint used to always say "Escribe en español…" regardless
            // of the practiced language. Now it follows language.inputHint
            // (ja/ko/zh/en), matching the mic/STT locale below.
            placeholder = { Text(language.inputHint) },
            singleLine = false,
            enabled = !isSending,
        )

        IconButton(
            onClick = onMicToggle,
            enabled = !isSending,
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = if (isListening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) {
                    "Detener micrófono"
                } else {
                    "Hablar en ${language.displayName}"
                },
                tint = if (isListening) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }

        IconButton(
            onClick = onSend,
            enabled = !isSending && inputText.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (!isSending && inputText.isNotBlank()) 1f else 0.4f,
                    ),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Enviar",
                tint = Color.White,
            )
        }
    }
}
