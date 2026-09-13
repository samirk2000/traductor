package com.arnold.voicetranslator.simulation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *  1. Scenario selector + language-to-practice selector (JA/KO).
 *  2. Chat area: user bubbles in plain Spanish; AI bubbles ALWAYS show the
 *     native script (kana/hangul) large — the "regla de oro visual" — with
 *     the romanization small/gray below and the Spanish meaning smaller still.
 *  3. Text input (+ optional mic) at the bottom.
 *  4. "Terminar y dar feedback" button that requests a level assessment.
 */
@Composable
fun SimulationModeScreen(
    viewModel: SimulationViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

    Column(
        modifier = modifier
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

        Spacer(Modifier.size(12.dp))

        SimulationChatArea(
            messages = state.messages,
            isSending = state.isSending,
            language = state.language,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

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

        Spacer(Modifier.size(10.dp))

        SimulationInputBar(
            inputText = state.inputText,
            isSending = state.isSending,
            isListening = state.isListening,
            hasMicPermission = state.hasMicPermission,
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
// Scenario + language selectors
// ===========================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SimulationSelectors(
    selectedScenario: SimulationScenario,
    selectedLanguage: SimulationLanguage,
    onSelectScenario: (SimulationScenario) -> Unit,
    onSelectLanguage: (SimulationLanguage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Escenario",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
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
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        Text(
            "Idioma a practicar",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationLanguage.entries.forEach { language ->
                FilterChip(
                    selected = language == selectedLanguage,
                    onClick = { onSelectLanguage(language) },
                    label = { Text("${language.flagEmoji} ${language.displayName}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
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
                    "Escribe (o habla) en español para empezar a practicar " +
                        "${language.displayName} en este escenario.",
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

/** User bubble: right-aligned, plain Spanish only — never any target-language text. */
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
                        message.spanishText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
            placeholder = { Text("Escribe en español…") },
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
                contentDescription = if (isListening) "Detener micrófono" else "Hablar en español",
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
