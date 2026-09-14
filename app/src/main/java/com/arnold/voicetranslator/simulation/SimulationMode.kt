package com.arnold.voicetranslator.simulation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnold.voicetranslator.ui.localization.UiLanguage

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
    // App-wide UI language (see MainActivity/TranslatorUiState.uiLanguage),
    // driven by the Traductor's Origen selector: when Origen = Inglés, this
    // whole screen's own copy (buttons, hints, empty states…) switches to
    // English too, on top of the existing "practicing English" immersion
    // mode below — so "Origen = Inglés" really does localize EVERY screen,
    // not just Traductor/Fraseario.
    appUiLanguage: UiLanguage = UiLanguage.ES,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Keeps the ViewModel's copy of the app-wide UI language in sync so
    // /simulate calls know which language to return the "meaning" fields in
    // (see SimulationViewModel.onAppUiLanguageChanged / dispatchToBackend).
    LaunchedEffect(appUiLanguage) {
        viewModel.onAppUiLanguageChanged(appUiLanguage)
    }

    // Fix: selecting 🇺🇸 English as the practiced language now flips this
    // whole screen's own copy (buttons, hints, empty states…) to English too
    // — an "immersion" mode. Any other language (JA/KO/ZH) keeps the app's
    // native Spanish/Mexican UI, matching what a Mexican Spanish speaker
    // practicing those languages would expect. Also flips to English
    // whenever the app-wide Origen selector is English, regardless of which
    // language is being practiced.
    // Fix: this used to ALSO flip to English whenever the practiced language
    // (state.language) was English — i.e. tapping "English" as what you want
    // to practice made the whole Simulation screen's own chrome (buttons,
    // scenario/language picker labels, error toasts, mic hint) switch to
    // English too, exactly like changing the app-wide Origen/UI language from
    // Ajustes. That's wrong: Simulation Mode is for a Spanish-speaking user
    // practicing conversations in ja/ko/zh/en — the language being practiced
    // must be fully independent from which language the screen's own UI is
    // drawn in. Now `englishUi` (this screen's chrome language) follows ONLY
    // the app's real UI language (appUiLanguage, driven by the Traductor's
    // Origen selector), never the scenario/practice language chip.
    val englishUi = appUiLanguage == UiLanguage.EN
    val texts = simCopy(englishUi)

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
    // transient Snackbar that auto-dismisses after a few seconds — and, when
    // [SimulationUiState.canRetry] is true (a failed send that can be
    // re-sent as-is, e.g. "Servidor ocupado"), it also shows a "Reintentar"
    // action button instead of forcing the user to retype the message.
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (!message.isNullOrBlank()) {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (state.canRetry) texts.retry else null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onRetryLastMessage()
            } else {
                viewModel.onDismissError()
            }
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
                englishUi = englishUi,
                onSelectScenario = viewModel::onSelectScenario,
                onSelectLanguage = viewModel::onSelectLanguage,
            )

            Spacer(Modifier.size(10.dp))

            SimulationChatArea(
                messages = state.messages,
                isSending = state.isSending,
                showSlowSendHint = state.showSlowSendHint,
                onRetrySlowRequest = viewModel::onCancelAndRetrySlowRequest,
                language = state.language,
                englishUi = englishUi,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Spacer(Modifier.size(8.dp))

            SimulationSuggestions(
                visible = state.suggestionsVisible,
                // Fix: suggestions used to stay fixed the whole conversation —
                // dynamicSuggestions was only ever populated by manually
                // tapping the Spanish helper, so the same 3 chips sat there
                // the rest of the chat. Now SimulationViewModel.fetchSuggestions()
                // is called again after every AI reply (see dispatchToBackend),
                // so this refreshes each turn; the static per-scenario list
                // only ever covers the very first message, before any AI turn.
                suggestions = state.dynamicSuggestions.ifEmpty {
                    scenarioSuggestions(state.scenario, state.language)
                },
                englishUi = englishUi,
                onSuggestionClick = viewModel::onSendSuggestion,
                onToggleVisible = viewModel::onToggleSuggestions,
            )

            Spacer(Modifier.size(10.dp))

            SimulationInputBar(
                inputText = state.inputText,
                isSending = state.isSending,
                isListening = state.isListening,
                hasMicPermission = state.hasMicPermission,
                language = state.language,
                englishUi = englishUi,
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

            // Fix: beginners who don't know how to say it yet were forced to
            // leave Simulación and go to Traductor. This collapsible helper
            // lets them type in Spanish right here; /simulate translates it
            // into the practiced language automatically (same flow as the
            // main input — see SimulationViewModel.onSendSpanishHelperMessage).
            SimulationSpanishHelper(
                expanded = state.spanishHelperExpanded,
                text = state.spanishHelperText,
                isSending = state.isSending,
                englishUi = englishUi,
                onToggleExpanded = viewModel::onToggleSpanishHelper,
                onTextChange = viewModel::onSpanishHelperTextChange,
                onSend = viewModel::onSendSpanishHelperMessage,
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
                    Text(texts.analyzingLevel)
                } else {
                    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(texts.finishAndFeedback)
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
            title = { Text("${texts.yourLevelIn} ${state.language.label(englishUi)}") },
            text = { Text(feedback, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissFeedback) { Text(texts.close) }
            },
        )
    }
}

// ===========================================================================
// UI copy — Spanish (default) / English ("immersion" when practicing English)
// ===========================================================================

/**
 * All the Simulation Mode screen's own static UI copy, in one place, so it
 * can switch language wholesale via [simCopy]. Doesn't cover
 * [WorkerApiClient]-level error messages (shared with the rest of the app,
 * out of scope here) nor scenario/language names (see
 * [SimulationScenario.label]/[SimulationLanguage.label], already bilingual).
 */
private data class SimTexts(
    val scenarioPrefix: String,
    val languagePrefix: String,
    val collapse: String,
    val expand: String,
    val showSuggestions: String,
    val hideSuggestions: String,
    val suggestionsHeader: String,
    val emptyChatPrefix: String,
    val emptyChatSuffix: String,
    val typing: String,
    val stopMic: String,
    val speakIn: String,
    val send: String,
    val spanishHelperCollapsedLabel: String,
    val spanishHelperExpandedLabel: String,
    val spanishHelperPlaceholder: String,
    val spanishHelperSend: String,
    val analyzingLevel: String,
    val finishAndFeedback: String,
    val yourLevelIn: String,
    val close: String,
    val retry: String,
    /** Prefix for the "💡 Mejor: ..." correction badge — see [CorrectionHintBadge]. */
    val betterSay: String,
)

private val ES_SIM_TEXTS = SimTexts(
    scenarioPrefix = "Escenario:",
    languagePrefix = "Idioma:",
    collapse = "Colapsar",
    expand = "Expandir",
    showSuggestions = "Mostrar sugerencias",
    hideSuggestions = "Ocultar sugerencias",
    suggestionsHeader = "💡 Sugerencias",
    emptyChatPrefix = "Escribe (o habla) en",
    emptyChatSuffix = "para empezar a practicar en este escenario.",
    typing = "Escribiendo",
    stopMic = "Detener micrófono",
    speakIn = "Hablar en",
    send = "Enviar",
    spanishHelperCollapsedLabel = "¿No sabes cómo decirlo? Escríbelo en español 👉",
    spanishHelperExpandedLabel = "🙈 Ocultar ayuda en español",
    spanishHelperPlaceholder = "Escribe en español y te lo traduzco…",
    spanishHelperSend = "Traducir y enviar",
    analyzingLevel = "Analizando tu nivel…",
    finishAndFeedback = "Terminar y dar feedback",
    yourLevelIn = "Tu nivel en",
    close = "Cerrar",
    retry = "Reintentar",
    betterSay = "Mejor",
)

private val EN_SIM_TEXTS = SimTexts(
    scenarioPrefix = "Scenario:",
    languagePrefix = "Language:",
    collapse = "Collapse",
    expand = "Expand",
    showSuggestions = "Show suggestions",
    hideSuggestions = "Hide suggestions",
    suggestionsHeader = "💡 Suggestions",
    emptyChatPrefix = "Write (or speak) in",
    emptyChatSuffix = "to start practicing this scenario.",
    typing = "Typing",
    stopMic = "Stop microphone",
    speakIn = "Speak in",
    send = "Send",
    // Fix: the bridge-language helper stays useful even in English immersion
    // mode (you may still not know how to phrase something in English) — only
    // its surrounding label switches to English, "español" stays as the name
    // of the language you'd actually type into that secondary field.
    spanishHelperCollapsedLabel = "Don't know how to say it? Write it in Spanish 👉",
    spanishHelperExpandedLabel = "🙈 Hide Spanish help",
    spanishHelperPlaceholder = "Write in Spanish and I'll translate it…",
    spanishHelperSend = "Translate and send",
    analyzingLevel = "Analyzing your level…",
    finishAndFeedback = "Finish & get feedback",
    yourLevelIn = "Your level in",
    close = "Close",
    retry = "Retry",
    betterSay = "Better",
)

private fun simCopy(englishUi: Boolean): SimTexts = if (englishUi) EN_SIM_TEXTS else ES_SIM_TEXTS

// ===========================================================================
// Scenario + language selectors (collapsible)
// ===========================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SimulationSelectors(
    selectedScenario: SimulationScenario,
    selectedLanguage: SimulationLanguage,
    englishUi: Boolean,
    onSelectScenario: (SimulationScenario) -> Unit,
    onSelectLanguage: (SimulationLanguage) -> Unit,
) {
    // Fix: Escenario + Idioma used to always take up half the screen with no
    // way to collapse them. Each is now its own collapsible Card (expanded by
    // default), so the chat area gains space once the user picks both.
    var isScenarioExpanded by rememberSaveable { mutableStateOf(true) }
    var isLanguageExpanded by rememberSaveable { mutableStateOf(true) }
    val texts = simCopy(englishUi)

    Column(modifier = Modifier.fillMaxWidth()) {
        CollapsibleSection(
            title = "${texts.scenarioPrefix} ${selectedScenario.emoji} ${selectedScenario.label(englishUi)}",
            expanded = isScenarioExpanded,
            texts = texts,
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
                        label = { Text("${scenario.emoji} ${scenario.label(englishUi)}") },
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
            title = "${texts.languagePrefix} ${selectedLanguage.flagEmoji} ${selectedLanguage.label(englishUi)}",
            expanded = isLanguageExpanded,
            texts = texts,
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
                        label = { Text("${language.flagEmoji} ${language.label(englishUi)}") },
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
    texts: SimTexts,
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
                    contentDescription = if (expanded) texts.collapse else texts.expand,
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
 * Static, per-(scenario, language) starter phrases for beginners who don't
 * know what to say next ("no saben que decir después"). Each chip shows the
 * romanized form + Spanish meaning (readable for a beginner), e.g. "Kore wa
 * ikura desu ka? (¿Cuánto cuesta esto?)", but tapping sends the real
 * native-script text immediately — see [SimulationViewModel.onSendSuggestion].
 * Hideable for advanced users — persisted in SharedPreferences via
 * [SimulationViewModel.onToggleSuggestions] so it stays hidden across sessions.
 */
@Composable
private fun SimulationSuggestions(
    visible: Boolean,
    suggestions: List<ScenarioSuggestion>,
    englishUi: Boolean,
    onSuggestionClick: (ScenarioSuggestion) -> Unit,
    onToggleVisible: () -> Unit,
) {
    val texts = simCopy(englishUi)
    if (!visible) {
        TextButton(onClick = onToggleVisible) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text(texts.showSuggestions, style = MaterialTheme.typography.bodySmall)
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
                texts.suggestionsHeader,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onToggleVisible, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = texts.hideSuggestions,
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
                    label = {
                        Text(
                            "${suggestion.romanized} (${suggestion.spanish})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

/**
 * Collapsible secondary input for beginners who don't know how to say
 * something in the practiced language yet: "¿No sabes cómo decirlo?
 * Escríbelo en español 👉" expands a small Spanish-labeled [OutlinedTextField]
 * whose send button routes through [SimulationViewModel.onSendSpanishHelperMessage]
 * (same /simulate translation flow as the main input).
 */
@Composable
private fun SimulationSpanishHelper(
    expanded: Boolean,
    text: String,
    isSending: Boolean,
    englishUi: Boolean,
    onToggleExpanded: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val texts = simCopy(englishUi)
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (expanded) texts.spanishHelperExpandedLabel else texts.spanishHelperCollapsedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(texts.spanishHelperPlaceholder) },
                    singleLine = true,
                    enabled = !isSending,
                )
                IconButton(
                    onClick = onSend,
                    enabled = !isSending && text.isNotBlank(),
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondary.copy(
                                alpha = if (!isSending && text.isNotBlank()) 1f else 0.4f,
                            ),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = texts.spanishHelperSend,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
    // Fix: a slow/stuck /simulate call used to leave "Escribiendo…" on
    // screen indefinitely (up to the full 45s timeout). Once true (after
    // SimulationViewModel.SLOW_SEND_HINT_DELAY_MS, ~10s), shows a small
    // inline "Reintentar" next to the typing bubble instead.
    showSlowSendHint: Boolean,
    onRetrySlowRequest: () -> Unit,
    language: SimulationLanguage,
    englishUi: Boolean,
    modifier: Modifier = Modifier,
) {
    val texts = simCopy(englishUi)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        if (messages.isEmpty() && !isSending) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "${texts.emptyChatPrefix} ${language.label(englishUi)} ${texts.emptyChatSuffix}",
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
                    SimulationChatBubble(message, englishUi)
                }
                if (isSending) {
                    item(key = "typing") {
                        SimulationTypingBubble(
                            englishUi = englishUi,
                            showRetryHint = showSlowSendHint,
                            onRetry = onRetrySlowRequest,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chat bubble for both senders, showing up to 3 DISTINCT lines: native
 * script (large), romanization (medium, gray), meaning in the app's UI
 * language (small, gray) — in that order, skipping any line that's blank or
 * a duplicate of one already shown. On the USER's own bubble, if their
 * message wasn't quite right, a small amber "💡 Mejor: ..." hint is shown
 * below — see [CorrectionHintBadge].
 *
 * Fix: the user bubble used to show the same string 3 times whenever the
 * Worker had nothing better to return for romanized/spanishMeaning. Now the
 * Worker always echoes the user's message back in 3 distinct forms
 * (`userTranscription`/`userRomaji`/`userSpanish`), and [bubbleLines] below
 * still dedupes for the rare case two of them coincide.
 */
@Composable
private fun SimulationChatBubble(message: SimulationMessage, englishUi: Boolean) {
    val isUser = message.sender == SimulationSender.USER
    val lines = bubbleLines(message, isUser)
    // Fix: a beginner who wrote something wrong/nonsensical in the practiced
    // language used to either see nothing about it, or (an earlier attempt)
    // a correction rendered IN the practiced language they can't read yet.
    // Now: only ever shown on the USER's own bubble, native script (to
    // actually read/copy) + explanation ALWAYS in the UI language.
    val showCorrection = isUser && !message.isCorrect && message.correctionNative.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
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
                    lines.forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.size(4.dp))
                        when (index) {
                            // Regla de oro visual: SIEMPRE escritura nativa grande primero.
                            0 -> Text(
                                text = line,
                                style = if (isUser) {
                                    MaterialTheme.typography.bodyLarge
                                } else {
                                    MaterialTheme.typography.headlineSmall
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            1 -> Text(
                                text = line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            else -> Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                },
                            )
                        }
                    }
                }
            }
            if (showCorrection) {
                Spacer(Modifier.size(4.dp))
                CorrectionHintBadge(message, englishUi)
            }
        }
    }
}

/**
 * "💡 Mejor: <correctionNative> (<correctionSpanish>)" badge shown right
 * below a USER bubble whose message wasn't quite right — see
 * [SimulationChatBubble]'s [showCorrection] check. Amber/yellow so it reads
 * as a helpful tip, not an error.
 */
@Composable
private fun CorrectionHintBadge(message: SimulationMessage, englishUi: Boolean) {
    val texts = simCopy(englishUi)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF7A5B00).copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth(0.85f),
    ) {
        Text(
            text = buildString {
                append("💡 ")
                append(texts.betterSay)
                append(": ")
                append(message.correctionNative)
                if (message.correctionSpanish.isNotBlank()) {
                    append(" (")
                    append(message.correctionSpanish)
                    append(")")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFFD54F),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Builds the deduped list of lines to render for a bubble: native script (or
 * the raw typed text as fallback), then romanization if different, then the
 * translated meaning (in the app's UI language, see
 * [SimulationViewModel.onAppUiLanguageChanged]) if different from both.
 *
 * Fix: the USER bubble's third line used to prefer the raw text the user
 * actually typed/said ([SimulationMessage.spanishText]) over the Worker's
 * real translated meaning ([SimulationMessage.spanishMeaning], filled from
 * `userSpanish`) — so e.g. saying "Ohayou gozaimasu" never showed "Buenos
 * días"/"Good morning" on the user's own side, only kana+romaji (which,
 * since the raw text usually duplicated one of those two lines already, got
 * deduped away entirely). Now both senders prefer the real translated
 * meaning first, falling back to the raw text only if the Worker didn't
 * return one (e.g. a request that failed before the back-fill).
 */
private fun bubbleLines(message: SimulationMessage, isUser: Boolean): List<String> {
    val primary = message.nativeScript.ifBlank { message.spanishText }.ifBlank { "…" }
    val lines = mutableListOf(primary)
    if (message.romanized.isNotBlank() && message.romanized != primary) {
        lines += message.romanized
    }
    val meaning = if (isUser) {
        message.spanishMeaning.ifBlank { message.spanishText }
    } else {
        message.spanishMeaning
    }
    if (meaning.isNotBlank() && meaning !in lines) {
        lines += meaning
    }
    return lines
}

/**
 * "AI is typing" bubble — shown while [SimulationUiState.isSending] is true,
 * which also disables the send button (see [SimulationInputBar]) so the user
 * can't fire a second /simulate call on top of one already in flight.
 *
 * Fix: was a plain spinner + static "Escribiendo…" text; now shows 3 small
 * bouncing dots (like a real chat app's typing indicator) after the label.
 * Fix: once [showRetryHint] is true (request stuck 10s+, see
 * [SimulationUiState.showSlowSendHint]), also shows a small inline
 * "Reintentar" text button right next to it instead of leaving the user
 * stuck staring at the dots until the full 45s timeout.
 */
@Composable
private fun SimulationTypingBubble(
    englishUi: Boolean,
    showRetryHint: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val texts = simCopy(englishUi)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(texts.typing, style = MaterialTheme.typography.bodySmall)
                TypingDots(modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (showRetryHint) {
            Spacer(Modifier.size(6.dp))
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(texts.retry, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 3 small dots, each bouncing in alpha with a staggered delay — "…" but animated. */
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typingDots")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = index * 150,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(5.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
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
    englishUi: Boolean,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicToggle: () -> Unit,
) {
    val texts = simCopy(englishUi)
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
            // of the practiced language. Now it follows language.hint()
            // (ja/ko/zh/en, in Spanish or English depending on englishUi),
            // matching the mic/STT locale below.
            placeholder = { Text(language.hint(englishUi)) },
            singleLine = false,
            // Fix (#1): disabled while a /simulate call is in flight so the
            // user can't queue up a second message on top of one already
            // being answered — same isSending flag that shows the animated
            // "Escribiendo…" bubble below.
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
                    texts.stopMic
                } else {
                    "${texts.speakIn} ${language.label(englishUi)}"
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
            // Fix (#1): send button disabled while sending (isSending) OR
            // while empty — prevents a double /simulate call from a fast
            // double-tap on top of the isSending guard in
            // SimulationViewModel.onSendMessage.
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
                contentDescription = texts.send,
                tint = Color.White,
            )
        }
    }
}
