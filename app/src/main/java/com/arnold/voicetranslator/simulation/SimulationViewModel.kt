package com.arnold.voicetranslator.simulation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arnold.voicetranslator.audio.SpeechRecognitionManager
import com.arnold.voicetranslator.audio.TtsManager
import com.arnold.voicetranslator.data.remote.SimulateResponse
import com.arnold.voicetranslator.data.remote.SimulateTurnDto
import com.arnold.voicetranslator.data.remote.WorkerApiClient
import com.arnold.voicetranslator.data.remote.WorkerApiException
import com.arnold.voicetranslator.ui.localization.UiLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Immutable UI state for Simulation Mode. Lives entirely on its own — never
 *  merged with [com.arnold.voicetranslator.ui.state.TranslatorUiState]. */
data class SimulationUiState(
    val scenario: SimulationScenario = SimulationScenario.STORE,
    val language: SimulationLanguage = SimulationLanguage.JAPANESE,
    val messages: List<SimulationMessage> = emptyList(),
    val inputText: String = "",
    val hasMicPermission: Boolean = false,
    val isListening: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val feedback: String? = null,
    val isGeneratingFeedback: Boolean = false,
    /** Whether the "Sugerencias" chip row is shown. Persisted in SharedPreferences
     *  ([PREFS_NAME]) so advanced users who hide it don't see it again. */
    val suggestionsVisible: Boolean = true,
    /**
     * Whether the secondary "Escríbelo en español" helper input is expanded.
     * Starts collapsed, but auto-expands (and stays that way, persisted) once
     * the user has relied on it [ES_HELPER_AUTO_EXPAND_THRESHOLD] times — see
     * [SimulationViewModel.onSendSpanishHelperMessage].
     */
    val spanishHelperExpanded: Boolean = false,
    /** Current text typed into the secondary Spanish-input helper field. */
    val spanishHelperText: String = "",
    /**
     * Contextual "what could I say next" suggestions, fetched on-demand from
     * POST /suggestions only when the user taps "¿No sabes cómo decirlo?"
     * (see [SimulationViewModel.fetchSuggestions]) — falls back to the
     * static per-scenario list ([scenarioSuggestions]) until then.
     *
     * Fix: these used to come back inside EVERY /simulate response (2-3 full
     * suggestion objects, ~150+ extra output tokens per turn) — now they're
     * only generated when actually needed, which is most of the token
     * savings behind the 9th-message "Servidor ocupado" hang fix.
     */
    val dynamicSuggestions: List<ScenarioSuggestion> = emptyList(),
    /** Whether a POST /suggestions call is in flight (disables the helper's tap-to-fetch while loading). */
    val isFetchingSuggestions: Boolean = false,
    /**
     * Whether [errorMessage] came from a failed send that can be retried
     * as-is (the last user message is still the one to resend) — drives the
     * Snackbar's "Reintentar" action button in SimulationMode.kt.
     */
    val canRetry: Boolean = false,
    /**
     * True once a /simulate call has been "Escribiendo…" for more than
     * [SimulationViewModel.SLOW_SEND_HINT_DELAY_MS] — drives a small inline
     * "Reintentar" button next to the typing bubble so a slow/stuck request
     * never leaves the user just staring at a spinner with no way out
     * short of restarting the scenario.
     */
    val showSlowSendHint: Boolean = false,
)

/**
 * Fully independent [AndroidViewModel] for Simulation Mode. Owns its own
 * [WorkerApiClient] (LLM/translation backend), its own [SpeechRecognitionManager]
 * and [TtsManager] instances, and its own [SimulationUiState] — none of these
 * are shared with [com.arnold.voicetranslator.ui.MainViewModel], so Live
 * Conversation, Subtitles, and Fraseario are never touched by this module.
 */
class SimulationViewModel(application: Application) : AndroidViewModel(application) {

    // Reuses the existing Worker/DeepSeek LLM pipeline (POST /simulate,
    // /simulate-feedback) — the same "función existente de LLM/traducción"
    // the app already uses for /translate, /converse, /explain. A separate
    // instance is created here (rather than injecting the one from
    // MainViewModel) purely to keep this module 100% independent/removable.
    private val workerApi = WorkerApiClient()

    // Own audio stack, independent from MainViewModel's — lets the user
    // speak their Spanish turn instead of typing it.
    private val speechManager = SpeechRecognitionManager(application.applicationContext)
    private val ttsManager = TtsManager(application.applicationContext)

    private val prefs = application.applicationContext
        .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /**
     * In-memory cache of the very last successful /simulate call — if the
     * user sends the exact same text twice in a row (same scenario +
     * language + message), we reuse this instead of hitting DeepSeek again.
     * Intentionally NOT persisted (cleared automatically once a *different*
     * message is sent, since it's only ever compared against, never grows).
     */
    private var lastSimulateCache: SimulateCacheEntry? = null

    /**
     * App-wide UI language (Traductor's Origen selector), kept in sync from
     * [SimulationModeScreen]'s `appUiLanguage` param via [onAppUiLanguageChanged].
     * Drives which language `/simulate`'s "replySpanish"/"correction" meaning
     * fields come back in — see [dispatchToBackend]. Fix: this used to be
     * hardcoded to Spanish server-side regardless of Origen, so the AI's
     * reply meaning and the user's own translated meaning always showed
     * Spanish even under an English UI.
     */
    private var appUiLanguage: UiLanguage = UiLanguage.ES

    /**
     * The currently in-flight /simulate coroutine [Job] (if any) — cancelled
     * by [onCancelAndRetrySlowRequest] when the "Reintentar" hint is tapped
     * mid-request, instead of leaving it to eventually time out on its own.
     */
    private var currentSendJob: Job? = null
    private var currentSuggestionsJob: Job? = null

    /**
     * How many /simulate calls in a row have failed (timeout or any other
     * [WorkerApiException]/[Exception]) — reset to 0 on any success. Once
     * it hits [CONSECUTIVE_FAILURES_BEFORE_AUTO_RESET], [dispatchToBackend]
     * auto-clears the conversation (keeping scenario/language) instead of
     * showing another "Servidor ocupado": a long, DeepSeek-unfriendly
     * history is a common root cause of repeated failures, so starting
     * fresh is more likely to actually get the user unstuck.
     */
    private var consecutiveFailures = 0

    private val _uiState = MutableStateFlow(
        SimulationUiState(
            suggestionsVisible = prefs.getBoolean(KEY_SUGGESTIONS_VISIBLE, true),
            spanishHelperExpanded = prefs.getBoolean(KEY_ES_HELPER_AUTO_EXPAND, false),
        ),
    )
    val uiState: StateFlow<SimulationUiState> = _uiState.asStateFlow()

    init {
        speechManager.onResult = { text ->
            _uiState.update { it.copy(isListening = false) }
            if (text.isNotBlank()) {
                _uiState.update { it.copy(inputText = text) }
                onSendMessage()
            }
        }
        speechManager.onError = { message ->
            _uiState.update { it.copy(isListening = false, errorMessage = message) }
        }
        speechManager.onEnd = {
            _uiState.update { it.copy(isListening = false) }
        }
        // If the Spanish helper auto-expanded from a persisted preference
        // (see spanishHelperExpanded's KDoc), fetch fresh suggestions right
        // away too — otherwise it would only refresh on the next manual tap.
        if (_uiState.value.spanishHelperExpanded) fetchSuggestions()
    }

    // ---- User actions --------------------------------------------------

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = granted) }
    }

    /** Keeps [appUiLanguage] in sync with the Traductor's Origen selector. */
    fun onAppUiLanguageChanged(language: UiLanguage) {
        appUiLanguage = language
    }

    fun onSelectScenario(scenario: SimulationScenario) {
        if (scenario == _uiState.value.scenario) return
        currentSendJob?.cancel()
        currentSendJob = null
        // Fix: an in-flight /suggestions call from the OLD scenario used to
        // keep isFetchingSuggestions=true after switching (that field is
        // only ever reset from inside fetchSuggestions()'s own coroutine,
        // which resetConversation() doesn't touch) — the guard at the top
        // of fetchSuggestions() would then silently no-op every future call
        // for the REST of the session, so "Sugerencias" would just freeze
        // on stale/empty content. Cancel it explicitly, same as
        // currentSendJob above.
        currentSuggestionsJob?.cancel()
        currentSuggestionsJob = null
        resetConversation()
        _uiState.update { it.copy(scenario = scenario) }
    }

    fun onSelectLanguage(language: SimulationLanguage) {
        if (language == _uiState.value.language) return
        currentSendJob?.cancel()
        currentSendJob = null
        currentSuggestionsJob?.cancel()
        currentSuggestionsJob = null
        resetConversation()
        _uiState.update { it.copy(language = language) }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text, errorMessage = null) }
    }

    /** Press-to-talk mic for the user's turn, in the practiced language (optional; typing always works). */
    fun onMicToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            // Fix: was keyed off the practiced language (state.language ==
            // ENGLISH) instead of the app's real UI language — see the
            // englishUi fix note on SimulationMode.kt's screen-chrome flag.
            val message = if (appUiLanguage == UiLanguage.EN) {
                "Microphone permission is required."
            } else {
                "Se requiere permiso del micrófono."
            }
            _uiState.update { it.copy(errorMessage = message) }
            return
        }
        if (state.isListening) {
            speechManager.stop()
            _uiState.update { it.copy(isListening = false) }
        } else {
            ttsManager.stop()
            // Fix: the recognizer used to always listen in es-MX regardless
            // of the practiced language. Now it listens in whatever language
            // the user is practicing (ja-JP/ko-KR/zh-CN/en-US), matching the
            // chip selected in onSelectLanguage — the user's turn is spoken
            // in the target language, not Spanish.
            speechManager.speechLanguage = state.language.speechLocaleTag
            speechManager.start()
            _uiState.update { it.copy(isListening = true, errorMessage = null) }
        }
    }

    /** Sends the current [SimulationUiState.inputText] as the user's Spanish turn. */
    fun onSendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isBlank() || state.isSending) return

        val userMessage = SimulationMessage(
            id = UUID.randomUUID().toString(),
            sender = SimulationSender.USER,
            spanishText = text,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isSending = true,
                errorMessage = null,
                canRetry = false,
            )
        }
        dispatchToBackend(userMessage, text)
    }

    /**
     * Re-sends the exact same message that just failed (same [SimulationMessage],
     * no new bubble added) — wired to the error Snackbar's "Reintentar" action.
     */
    fun onRetryLastMessage() {
        val state = _uiState.value
        if (state.isSending || !state.canRetry) return
        val lastUserMessage = state.messages.lastOrNull { it.sender == SimulationSender.USER }
            ?: return
        val text = lastUserMessage.spanishText.ifBlank { lastUserMessage.nativeScript }
        if (text.isBlank()) return
        _uiState.update { it.copy(isSending = true, errorMessage = null, canRetry = false) }
        dispatchToBackend(lastUserMessage, text)
    }

    /**
     * Shared network path for both a fresh send and a retry of the last
     * message: checks the in-memory cache first (identical text sent twice
     * in a row skips DeepSeek entirely), otherwise calls /simulate and
     * caches the result.
     */
    private fun dispatchToBackend(userMessage: SimulationMessage, text: String) {
        val state = _uiState.value
        val currentScenario = state.scenario
        val currentLanguage = state.language
        // Fix: right after switching scenario/language (a fresh, empty
        // conversation), the very first reply is exactly the moment a user
        // is most likely to notice a slow/stuck "Escribiendo…" — don't pile
        // an extra /suggestions call on top of that first /simulate call.
        // The static per-scenario scenarioSuggestions() already covers turn
        // 1 fine (see SimulationMode.kt's ifEmpty fallback); auto-refreshing
        // dynamicSuggestions only kicks in from turn 2 onward.
        val isFirstTurn = state.messages.none { it.sender == SimulationSender.AI }
        // Fix: was `currentLanguage == SimulationLanguage.ENGLISH` — meaning
        // error toasts flipped to English whenever the PRACTICED language
        // was English, not when the app's real UI language was English. See
        // the englishUi fix note on SimulationMode.kt's screen-chrome flag.
        val englishUi = appUiLanguage == UiLanguage.EN

        val job = viewModelScope.launch {
            try {
                // Fix: repeating the exact same message right after (e.g. a
                // double-tap, or retrying while DeepSeek was already about
                // to answer) used to always fire a brand-new /simulate call.
                // If scenario+language+text match the last successful call,
                // reuse that response instantly — no network, no tokens.
                val cached = lastSimulateCache
                val response = if (
                    cached != null &&
                    cached.scenario == currentScenario.id &&
                    cached.language == currentLanguage.id &&
                    cached.message == text &&
                    cached.meaningLang == appUiLanguage.localeTag
                ) {
                    Log.d(TAG, "cache hit for repeated message, skipping DeepSeek call")
                    cached.response
                } else {
                    val recentHistory = compressedHistory(state.messages).map { msg ->
                        SimulateTurnDto(
                            role = if (msg.sender == SimulationSender.USER) "user" else "ai",
                            // User turns prefer the already-translated
                            // nativeScript (filled in from a previous
                            // response's userTranscription) so conversation
                            // memory stays in the practiced language once
                            // available; falls back to the raw typed text
                            // for the very first turn, before any response.
                            text = if (msg.sender == SimulationSender.USER) {
                                msg.nativeScript.ifBlank { msg.spanishText }
                            } else {
                                msg.nativeScript.ifBlank { msg.spanishMeaning }
                            },
                        )
                    }
                    Log.d(TAG, "history size: ${recentHistory.size}, tokens aprox: ${recentHistory.joinToString().length}")
                    workerApi.simulate(
                        scenario = currentScenario.id,
                        language = currentLanguage.id,
                        history = recentHistory,
                        message = text,
                        meaningLang = appUiLanguage.localeTag,
                    ).also {
                        lastSimulateCache = SimulateCacheEntry(
                            scenario = currentScenario.id,
                            language = currentLanguage.id,
                            message = text,
                            meaningLang = appUiLanguage.localeTag,
                            response = it,
                        )
                    }
                }
                consecutiveFailures = 0
                val aiMessage = SimulationMessage(
                    id = UUID.randomUUID().toString(),
                    sender = SimulationSender.AI,
                    nativeScript = response.replyNative,
                    romanized = response.replyRomaji,
                    spanishMeaning = response.replySpanish,
                )
                _uiState.update { current ->
                    // Fix: the user's own bubble used to show either the raw
                    // typed text 3x deduped, or (a later attempt) a Spanish-
                    // only "correction" with no romaji/translation of their
                    // OWN message at all. Now back-filled with all 3 forms
                    // (userTranscription/userRomaji/userSpanish) — same call,
                    // no extra tokens vs. the fields it replaces — plus an
                    // optional correction hint (native script to actually
                    // read, explained in the UI language, never in the
                    // practiced language) shown separately in the bubble.
                    val updatedMessages = current.messages.map { msg ->
                        if (msg.id == userMessage.id) {
                            msg.copy(
                                nativeScript = response.userTranscription,
                                romanized = response.userRomaji,
                                spanishMeaning = response.userSpanish,
                                isCorrect = response.isUserCorrect,
                                correctionNative = response.correctionNative,
                                correctionSpanish = response.correctionSpanish,
                            )
                        } else {
                            msg
                        }
                    }
                    current.copy(
                        messages = updatedMessages + aiMessage,
                        isSending = false,
                        showSlowSendHint = false,
                    )
                }
                speakAiReply(response.replyNative, currentLanguage)
                // Fix: dynamicSuggestions used to only ever get populated by
                // the user manually tapping "¿No sabes cómo decirlo?" — after
                // that first fetch, the SAME 3 suggestions sat there for the
                // rest of the conversation (they never reflected how the
                // chat had moved on). Refresh them here, after every AI
                // reply, so the "Sugerencias" row actually tracks the
                // conversation instead of staying static. Fire-and-forget:
                // failure just leaves the previous (still reasonable)
                // suggestions on screen — see fetchSuggestions()'s catch.
                // Skipped on turn 1 (see isFirstTurn above).
                if (!isFirstTurn) fetchSuggestions()
            } catch (e: WorkerApiException) {
                Log.e(TAG, "dispatchToBackend WorkerApiException", e)
                handleDispatchFailure(englishUi, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "dispatchToBackend unexpected error", e)
                handleDispatchFailure(englishUi, null)
            }
        }
        currentSendJob = job

        // Fix: a slow/stuck /simulate call used to just leave "Escribiendo…"
        // on screen with no way out until the full 45s timeout elapsed. If
        // it's still in flight after SLOW_SEND_HINT_DELAY_MS, surface a
        // small inline "Reintentar" next to the typing bubble (see
        // onCancelAndRetrySlowRequest) instead of leaving the user stuck.
        viewModelScope.launch {
            delay(SLOW_SEND_HINT_DELAY_MS)
            if (job.isActive) {
                _uiState.update { it.copy(showSlowSendHint = true) }
            }
        }
    }

    /**
     * Shared failure path for [dispatchToBackend]: on the
     * [CONSECUTIVE_FAILURES_BEFORE_AUTO_RESET]th failure in a row, auto-resets
     * the conversation (a long/broken history is the most common repeat-
     * failure cause) instead of surfacing yet another "Servidor ocupado" —
     * see [consecutiveFailures]'s KDoc.
     */
    private fun handleDispatchFailure(englishUi: Boolean, workerMessage: String?) {
        consecutiveFailures++
        if (consecutiveFailures >= CONSECUTIVE_FAILURES_BEFORE_AUTO_RESET) {
            consecutiveFailures = 0
            resetConversation()
            _uiState.update {
                it.copy(
                    errorMessage = if (englishUi) {
                        "Restarting conversation for smoother replies…"
                    } else {
                        "Reiniciando conversación para fluidez…"
                    },
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isSending = false,
                showSlowSendHint = false,
                errorMessage = workerMessage
                    ?: (if (englishUi) "Couldn't generate a reply." else "Error al generar la respuesta."),
                canRetry = true,
            )
        }
    }

    /**
     * Cancels the in-flight /simulate call and immediately retries with the
     * same last message — wired to the small "Reintentar" button shown next
     * to the typing bubble once [SimulationUiState.showSlowSendHint] is true
     * (10s+), instead of forcing the user to wait out the full 45s timeout.
     */
    fun onCancelAndRetrySlowRequest() {
        val state = _uiState.value
        if (!state.isSending) return
        currentSendJob?.cancel()
        val lastUserMessage = state.messages.lastOrNull { it.sender == SimulationSender.USER }
        val text = lastUserMessage?.spanishText?.ifBlank { lastUserMessage.nativeScript } ?: ""
        if (lastUserMessage == null || text.isBlank()) {
            _uiState.update { it.copy(isSending = false, showSlowSendHint = false) }
            return
        }
        _uiState.update { it.copy(isSending = true, showSlowSendHint = false, errorMessage = null) }
        dispatchToBackend(lastUserMessage, text)
    }

    /**
     * POST /suggestions — fetched after every AI turn in [dispatchToBackend]
     * (so the "Sugerencias" row tracks the conversation instead of staying
     * static) and also when expanding the "¿No sabes cómo decirlo?" helper
     * ([onToggleSpanishHelper]) for an immediate refresh on first open.
     */
    fun fetchSuggestions() {
        val state = _uiState.value
        if (state.isFetchingSuggestions) return
        _uiState.update { it.copy(isFetchingSuggestions = true) }
        currentSuggestionsJob = viewModelScope.launch {
            try {
                val recentHistory = compressedHistory(state.messages).map { msg ->
                    SimulateTurnDto(
                        role = if (msg.sender == SimulationSender.USER) "user" else "ai",
                        text = if (msg.sender == SimulationSender.USER) {
                            msg.nativeScript.ifBlank { msg.spanishText }
                        } else {
                            msg.nativeScript.ifBlank { msg.spanishMeaning }
                        },
                    )
                }
                val response = workerApi.suggestions(
                    scenario = state.scenario.id,
                    language = state.language.id,
                    history = recentHistory,
                    meaningLang = appUiLanguage.localeTag,
                )
                _uiState.update {
                    it.copy(
                        isFetchingSuggestions = false,
                        dynamicSuggestions = response.suggestions.map { s ->
                            ScenarioSuggestion(native = s.native, romanized = s.romanized, spanish = s.spanish)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "fetchSuggestions error", e)
                _uiState.update { it.copy(isFetchingSuggestions = false) }
            }
        }
    }

    /**
     * Fix: with [SIMULATION_MEMORY_SIZE] (4) sent unconditionally, a long
     * conversation still kept "forgetting" everything before the last 2
     * turns — fine for latency, but the AI would lose track of the scenario
     * (e.g. what the user already bought/asked). Once the conversation
     * reaches [HISTORY_COMPRESSION_THRESHOLD] (8) total messages, keep the
     * first 2 (sets the scene) PLUS the last [SIMULATION_MEMORY_SIZE] (4)
     * instead — bounded at 6 messages either way, so it never grows
     * unbounded turn after turn (the original cause of the 9th-message
     * "Servidor ocupado" hang).
     */
    private fun compressedHistory(messages: List<SimulationMessage>): List<SimulationMessage> {
        if (messages.size >= HISTORY_COMPRESSION_THRESHOLD) {
            val compressed = messages.take(2) + messages.takeLast(SIMULATION_MEMORY_SIZE)
            Log.d(TAG, "history compressed from ${messages.size} to ${compressed.size}")
            return compressed
        }
        return messages.takeLast(SIMULATION_MEMORY_SIZE)
    }

    /**
     * "Terminar y dar feedback": sends the full transcript to the Worker's
     * /simulate-feedback route and shows the AI's assessment of the user's
     * level.
     */
    fun onFinishAndGetFeedback() {
        val state = _uiState.value
        val hasUserTurns = state.messages.any { it.sender == SimulationSender.USER }
        if (!hasUserTurns || state.isGeneratingFeedback) return

        _uiState.update { it.copy(isGeneratingFeedback = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val transcript = buildTranscript(state)
                val feedback = workerApi.simulateFeedback(transcript)
                // Fix: chrome/UI-language messaging follows appUiLanguage,
                // never the practiced language — see englishUi fix note above.
                val englishUi = appUiLanguage == UiLanguage.EN
                _uiState.update {
                    it.copy(
                        isGeneratingFeedback = false,
                        feedback = feedback.ifBlank {
                            if (englishUi) {
                                "Couldn't generate feedback this time. Try again."
                            } else {
                                "No se pudo generar el feedback esta vez. Intenta de nuevo."
                            }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "onFinishAndGetFeedback error", e)
                val englishUi = appUiLanguage == UiLanguage.EN
                _uiState.update {
                    it.copy(
                        isGeneratingFeedback = false,
                        errorMessage = (e as? WorkerApiException)?.message
                            ?: (if (englishUi) "Couldn't generate feedback. Try again." else "No se pudo generar el feedback. Intenta de nuevo."),
                    )
                }
            }
        }
    }

    fun onDismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    fun onDismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Sends a tapped starter suggestion immediately (one-tap send): the chip
     * shows romanized + Spanish meaning for a beginner to read, but the text
     * actually sent to /simulate — and shown as the bubble's primary line —
     * is the proper native-script [ScenarioSuggestion.native], so the AI
     * always receives real target-language input.
     */
    fun onSendSuggestion(suggestion: ScenarioSuggestion) {
        if (_uiState.value.isSending) return
        _uiState.update { it.copy(inputText = suggestion.native, errorMessage = null) }
        onSendMessage()
    }

    /**
     * Expands/collapses the secondary "Escríbelo en español" helper input.
     * Fix: expanding it is now also the ONLY trigger for POST /suggestions
     * (see [fetchSuggestions]'s fix note) — suggestions are no longer
     * generated automatically on every turn.
     */
    fun onToggleSpanishHelper() {
        val expanding = !_uiState.value.spanishHelperExpanded
        _uiState.update { it.copy(spanishHelperExpanded = expanding) }
        if (expanding) fetchSuggestions()
    }

    fun onSpanishHelperTextChange(text: String) {
        _uiState.update { it.copy(spanishHelperText = text) }
    }

    /**
     * Sends whatever was typed into the Spanish helper field as the user's
     * turn (same send path as the main input — /simulate already handles
     * Spanish-or-target-language input and returns the real translation).
     * Tracks how often the helper is used so it auto-expands by default next
     * time for users who rely on it a lot (persisted in SharedPreferences).
     */
    fun onSendSpanishHelperMessage() {
        val text = _uiState.value.spanishHelperText.trim()
        if (text.isBlank() || _uiState.value.isSending) return

        val newUseCount = prefs.getInt(KEY_ES_HELPER_USE_COUNT, 0) + 1
        val editor = prefs.edit().putInt(KEY_ES_HELPER_USE_COUNT, newUseCount)
        val shouldAutoExpand = newUseCount >= ES_HELPER_AUTO_EXPAND_THRESHOLD
        if (shouldAutoExpand) editor.putBoolean(KEY_ES_HELPER_AUTO_EXPAND, true)
        editor.apply()

        _uiState.update {
            it.copy(
                inputText = text,
                spanishHelperText = "",
                spanishHelperExpanded = it.spanishHelperExpanded || shouldAutoExpand,
            )
        }
        onSendMessage()
    }

    /** "Ocultar/mostrar sugerencias" — persisted so it stays hidden next time. */
    fun onToggleSuggestions() {
        val newValue = !_uiState.value.suggestionsVisible
        prefs.edit().putBoolean(KEY_SUGGESTIONS_VISIBLE, newValue).apply()
        _uiState.update { it.copy(suggestionsVisible = newValue) }
    }

    // ---- Internal --------------------------------------------------------

    private fun resetConversation() {
        speechManager.cancel()
        ttsManager.stop()
        // Fix: NOT cancelling currentSendJob here on purpose — resetConversation()
        // is also called from inside handleDispatchFailure(), i.e. from the
        // catch block of the very job that job would be. Callers that reset
        // the conversation for a reason OTHER than an in-flight failure
        // (onSelectScenario/onSelectLanguage) cancel it explicitly first.
        lastSimulateCache = null
        consecutiveFailures = 0
        _uiState.update {
            it.copy(
                messages = emptyList(),
                inputText = "",
                isListening = false,
                isSending = false,
                errorMessage = null,
                canRetry = false,
                feedback = null,
                dynamicSuggestions = emptyList(),
                showSlowSendHint = false,
                isFetchingSuggestions = false,
            )
        }
    }

    /** One cached (scenario, language, message) -> response, see [lastSimulateCache]. */
    private data class SimulateCacheEntry(
        val scenario: String,
        val language: String,
        val message: String,
        val meaningLang: String,
        val response: SimulateResponse,
    )

    private fun buildTranscript(state: SimulationUiState): String =
        buildString {
            append("Escenario: ${state.scenario.displayName}. Idioma practicado: ${state.language.displayName}.\n")
            state.messages.forEach { msg ->
                if (msg.sender == SimulationSender.USER) {
                    append("Usuario (español): ${msg.spanishText}\n")
                } else {
                    append(
                        "IA (${state.language.displayName}): ${msg.nativeScript} " +
                            "(${msg.romanized}) — ${msg.spanishMeaning}\n",
                    )
                }
            }
        }

    private fun speakAiReply(text: String, language: SimulationLanguage) {
        if (text.isBlank()) return
        // language.locale now covers all 4 practice languages (ja/ko/zh/en) —
        // see SimulationLanguage.locale, derived from speechLocaleTag.
        ttsManager.setLocale(language.locale)
        ttsManager.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.release()
        ttsManager.release()
        workerApi.close()
    }

    private companion object {
        const val TAG = "SimulationMode"
        const val PREFS_NAME = "simulation_mode_prefs"
        const val KEY_SUGGESTIONS_VISIBLE = "suggestions_visible"
        const val KEY_ES_HELPER_USE_COUNT = "es_helper_use_count"
        const val KEY_ES_HELPER_AUTO_EXPAND = "es_helper_auto_expand"

        /** After this many uses of the Spanish helper, auto-expand it by default. */
        const val ES_HELPER_AUTO_EXPAND_THRESHOLD = 3

        /** See [compressedHistory]'s KDoc. */
        const val HISTORY_COMPRESSION_THRESHOLD = 8

        /** See [SimulationUiState.showSlowSendHint]'s KDoc. */
        const val SLOW_SEND_HINT_DELAY_MS = 10_000L

        /** See [consecutiveFailures]'s KDoc. */
        const val CONSECUTIVE_FAILURES_BEFORE_AUTO_RESET = 2
    }
}

/**
 * A single beginner starter suggestion: [native] is what actually gets sent
 * to /simulate on tap (real target-language text), [romanized] and [spanish]
 * are what the chip displays so a beginner can read/understand it before
 * sending — e.g. chip shows "Kore wa ikura desu ka? (¿Cuánto cuesta esto?)"
 * but taps send "これはいくらですか？".
 *
 * NOTE: colocated here (not in SimulationModels.kt) because this task's
 * allowed file list didn't include that file; it's otherwise pure data,
 * fully independent from [SimulationViewModel].
 */
data class ScenarioSuggestion(
    val native: String,
    val romanized: String,
    val spanish: String,
)

/** 3 hardcoded starter suggestions per (scenario, practiced language) — no AI call needed. */
fun scenarioSuggestions(
    scenario: SimulationScenario,
    language: SimulationLanguage,
): List<ScenarioSuggestion> = when (scenario) {
    SimulationScenario.STORE -> when (language) {
        SimulationLanguage.JAPANESE -> listOf(
            ScenarioSuggestion("これはいくらですか？", "Kore wa ikura desu ka?", "¿Cuánto cuesta esto?"),
            ScenarioSuggestion("他の色はありますか？", "Hoka no iro wa arimasu ka?", "¿Tiene esto en otro color?"),
            ScenarioSuggestion("見ているだけです、ありがとう", "Miteiru dake desu, arigatou", "Solo estoy viendo, gracias"),
        )
        SimulationLanguage.KOREAN -> listOf(
            ScenarioSuggestion("이거 얼마예요?", "Igeo eolmayeyo?", "¿Cuánto cuesta esto?"),
            ScenarioSuggestion("다른 색깔도 있어요?", "Dareun saekkkaldo isseoyo?", "¿Tiene esto en otro color?"),
            ScenarioSuggestion("그냥 구경하는 거예요, 감사합니다", "Geunyang gugyeonghaneun geoyeyo, gamsahamnida", "Solo estoy viendo, gracias"),
        )
        SimulationLanguage.CHINESE -> listOf(
            ScenarioSuggestion("这个多少钱？", "Zhège duōshǎo qián?", "¿Cuánto cuesta esto?"),
            ScenarioSuggestion("这个有别的颜色吗？", "Zhège yǒu bié de yánsè ma?", "¿Tiene esto en otro color?"),
            ScenarioSuggestion("我只是看看，谢谢", "Wǒ zhǐshì kànkan, xièxiè", "Solo estoy viendo, gracias"),
        )
        SimulationLanguage.ENGLISH -> listOf(
            ScenarioSuggestion("How much is this?", "How much is this?", "¿Cuánto cuesta esto?"),
            ScenarioSuggestion("Do you have this in another color?", "Do you have this in another color?", "¿Tiene esto en otro color?"),
            ScenarioSuggestion("I'm just looking, thanks", "I'm just looking, thanks", "Solo estoy viendo, gracias"),
        )
    }
    SimulationScenario.HOTEL -> when (language) {
        SimulationLanguage.JAPANESE -> listOf(
            ScenarioSuggestion("私の名前で予約があります", "Watashi no namae de yoyaku ga arimasu", "Tengo una reservación a mi nombre"),
            ScenarioSuggestion("チェックアウトは何時ですか？", "Chekkuauto wa nanji desu ka?", "¿A qué hora es el check-out?"),
            ScenarioSuggestion("別の部屋をもらえますか？", "Betsu no heya o moraemasu ka?", "¿Me puede dar otra habitación?"),
        )
        SimulationLanguage.KOREAN -> listOf(
            ScenarioSuggestion("제 이름으로 예약이 있어요", "Je ireumeuro yeyagi isseoyo", "Tengo una reservación a mi nombre"),
            ScenarioSuggestion("체크아웃은 몇 시예요?", "Chekeuaus-eun myeot siyeyo?", "¿A qué hora es el check-out?"),
            ScenarioSuggestion("다른 방을 받을 수 있어요?", "Dareun bang-eul badeul su isseoyo?", "¿Me puede dar otra habitación?"),
        )
        SimulationLanguage.CHINESE -> listOf(
            ScenarioSuggestion("我用我的名字预订了房间", "Wǒ yòng wǒ de míngzì yùdìng le fángjiān", "Tengo una reservación a mi nombre"),
            ScenarioSuggestion("退房时间是几点？", "Tuìfáng shíjiān shì jǐ diǎn?", "¿A qué hora es el check-out?"),
            ScenarioSuggestion("可以换一个房间吗？", "Kěyǐ huàn yīgè fángjiān ma?", "¿Me puede dar otra habitación?"),
        )
        SimulationLanguage.ENGLISH -> listOf(
            ScenarioSuggestion("I have a reservation under my name", "I have a reservation under my name", "Tengo una reservación a mi nombre"),
            ScenarioSuggestion("What time is check-out?", "What time is check-out?", "¿A qué hora es el check-out?"),
            ScenarioSuggestion("Can I get a different room?", "Can I get a different room?", "¿Me puede dar otra habitación?"),
        )
    }
    SimulationScenario.RESTAURANT -> when (language) {
        SimulationLanguage.JAPANESE -> listOf(
            ScenarioSuggestion("何がおすすめですか？", "Nani ga osusume desu ka?", "¿Qué me recomienda?"),
            ScenarioSuggestion("お会計お願いします", "Okaikei onegaishimasu", "La cuenta, por favor"),
            ScenarioSuggestion("ベジタリアンのメニューはありますか？", "Bejitarian no menyuu wa arimasu ka?", "¿Tienen opciones vegetarianas?"),
        )
        SimulationLanguage.KOREAN -> listOf(
            ScenarioSuggestion("뭐가 맛있어요?", "Mwoga masisseoyo?", "¿Qué me recomienda?"),
            ScenarioSuggestion("계산서 주세요", "Gyesanseo juseyo", "La cuenta, por favor"),
            ScenarioSuggestion("채식 메뉴 있어요?", "Chaesik menyu isseoyo?", "¿Tienen opciones vegetarianas?"),
        )
        SimulationLanguage.CHINESE -> listOf(
            ScenarioSuggestion("你推荐什么？", "Nǐ tuījiàn shénme?", "¿Qué me recomienda?"),
            ScenarioSuggestion("买单，谢谢", "Mǎidān, xièxiè", "La cuenta, por favor"),
            ScenarioSuggestion("有素食选择吗？", "Yǒu sùshí xuǎnzé ma?", "¿Tienen opciones vegetarianas?"),
        )
        SimulationLanguage.ENGLISH -> listOf(
            ScenarioSuggestion("What do you recommend?", "What do you recommend?", "¿Qué me recomienda?"),
            ScenarioSuggestion("The check, please", "The check, please", "La cuenta, por favor"),
            ScenarioSuggestion("Do you have vegetarian options?", "Do you have vegetarian options?", "¿Tienen opciones vegetarianas?"),
        )
    }
    SimulationScenario.DATE -> when (language) {
        SimulationLanguage.JAPANESE -> listOf(
            ScenarioSuggestion("私もあなたが好きです", "Watashi mo anata ga suki desu", "Me gustas también"),
            ScenarioSuggestion("お名前は何ですか？", "Onamae wa nan desu ka?", "¿Cómo te llamas?"),
            ScenarioSuggestion("またデートしたいですか？", "Mata deeto shitai desu ka?", "¿Quieres salir otra vez?"),
        )
        SimulationLanguage.KOREAN -> listOf(
            ScenarioSuggestion("저도 당신이 좋아요", "Jeodo dangsini joayo", "Me gustas también"),
            ScenarioSuggestion("이름이 뭐예요?", "Ireumi mwoyeyo?", "¿Cómo te llamas?"),
            ScenarioSuggestion("다시 만나고 싶어요?", "Dasi mannago sipeoyo?", "¿Quieres salir otra vez?"),
        )
        SimulationLanguage.CHINESE -> listOf(
            ScenarioSuggestion("我也喜欢你", "Wǒ yě xǐhuān nǐ", "Me gustas también"),
            ScenarioSuggestion("你叫什么名字？", "Nǐ jiào shénme míngzì?", "¿Cómo te llamas?"),
            ScenarioSuggestion("你想再约会一次吗？", "Nǐ xiǎng zài yuēhuì yīcì ma?", "¿Quieres salir otra vez?"),
        )
        SimulationLanguage.ENGLISH -> listOf(
            ScenarioSuggestion("I like you too", "I like you too", "Me gustas también"),
            ScenarioSuggestion("What's your name?", "What's your name?", "¿Cómo te llamas?"),
            ScenarioSuggestion("Do you want to go out again?", "Do you want to go out again?", "¿Quieres salir otra vez?"),
        )
    }
    SimulationScenario.WORK -> when (language) {
        SimulationLanguage.JAPANESE -> listOf(
            ScenarioSuggestion("この仕事について教えてください", "Kono shigoto ni tsuite oshiete kudasai", "Cuénteme sobre el puesto"),
            ScenarioSuggestion("勤務時間はどうなっていますか？", "Kinmu jikan wa dou natte imasu ka?", "¿Cuál es el horario?"),
            ScenarioSuggestion("この分野の経験があります", "Kono bun'ya no keiken ga arimasu", "Tengo experiencia en esta área"),
        )
        SimulationLanguage.KOREAN -> listOf(
            ScenarioSuggestion("이 직무에 대해 말씀해 주세요", "I jikmue daehae malsseumhae juseyo", "Cuénteme sobre el puesto"),
            ScenarioSuggestion("근무 시간이 어떻게 되나요?", "Geunmu sigani eotteoke doenayo?", "¿Cuál es el horario?"),
            ScenarioSuggestion("이 분야에 경험이 있어요", "I bunyae gyeongheomi isseoyo", "Tengo experiencia en esta área"),
        )
        SimulationLanguage.CHINESE -> listOf(
            ScenarioSuggestion("请给我介绍一下这个职位", "Qǐng gěi wǒ jièshào yīxià zhège zhíwèi", "Cuénteme sobre el puesto"),
            ScenarioSuggestion("工作时间是怎样的？", "Gōngzuò shíjiān shì zěnyàng de?", "¿Cuál es el horario?"),
            ScenarioSuggestion("我在这个领域有经验", "Wǒ zài zhège lǐngyù yǒu jīngyàn", "Tengo experiencia en esta área"),
        )
        SimulationLanguage.ENGLISH -> listOf(
            ScenarioSuggestion("Tell me about the position", "Tell me about the position", "Cuénteme sobre el puesto"),
            ScenarioSuggestion("What's the schedule?", "What's the schedule?", "¿Cuál es el horario?"),
            ScenarioSuggestion("I have experience in this field", "I have experience in this field", "Tengo experiencia en esta área"),
        )
    }
    SimulationScenario.CASUAL -> when (language) {
        SimulationLanguage.JAPANESE -> listOf(
            ScenarioSuggestion("今日は何をした？", "Kyou wa nani o shita?", "¿Qué hiciste hoy?"),
            ScenarioSuggestion("週末に予定ある？", "Shuumatsu ni yotei aru?", "¿Tienes planes para el fin de semana?"),
            ScenarioSuggestion("趣味は何？", "Shumi wa nani?", "¿Cuál es tu pasatiempo?"),
        )
        SimulationLanguage.KOREAN -> listOf(
            ScenarioSuggestion("오늘 뭐 했어?", "Oneul mwo haesseo?", "¿Qué hiciste hoy?"),
            ScenarioSuggestion("주말에 계획 있어?", "Jumare gyehoek isseo?", "¿Tienes planes para el fin de semana?"),
            ScenarioSuggestion("취미가 뭐야?", "Chwimiga mwoya?", "¿Cuál es tu pasatiempo?"),
        )
        SimulationLanguage.CHINESE -> listOf(
            ScenarioSuggestion("你今天做了什么？", "Nǐ jīntiān zuò le shénme?", "¿Qué hiciste hoy?"),
            ScenarioSuggestion("周末有什么计划？", "Zhōumò yǒu shénme jìhuà?", "¿Tienes planes para el fin de semana?"),
            ScenarioSuggestion("你有什么爱好？", "Nǐ yǒu shénme àihào?", "¿Cuál es tu pasatiempo?"),
        )
        SimulationLanguage.ENGLISH -> listOf(
            ScenarioSuggestion("What did you do today?", "What did you do today?", "¿Qué hiciste hoy?"),
            ScenarioSuggestion("Do you have plans for the weekend?", "Do you have plans for the weekend?", "¿Tienes planes para el fin de semana?"),
            ScenarioSuggestion("What's your hobby?", "What's your hobby?", "¿Cuál es tu pasatiempo?"),
        )
    }
}
