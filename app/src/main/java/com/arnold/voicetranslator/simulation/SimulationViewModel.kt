package com.arnold.voicetranslator.simulation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arnold.voicetranslator.audio.SpeechRecognitionManager
import com.arnold.voicetranslator.audio.TtsManager
import com.arnold.voicetranslator.data.remote.SimulateTurnDto
import com.arnold.voicetranslator.data.remote.WorkerApiClient
import com.arnold.voicetranslator.data.remote.WorkerApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
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

    private val _uiState = MutableStateFlow(SimulationUiState())
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
    }

    // ---- User actions --------------------------------------------------

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = granted) }
    }

    fun onSelectScenario(scenario: SimulationScenario) {
        if (scenario == _uiState.value.scenario) return
        resetConversation()
        _uiState.update { it.copy(scenario = scenario) }
    }

    fun onSelectLanguage(language: SimulationLanguage) {
        if (language == _uiState.value.language) return
        resetConversation()
        _uiState.update { it.copy(language = language) }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text, errorMessage = null) }
    }

    /** Press-to-talk mic for the user's Spanish turn (optional; typing always works). */
    fun onMicToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrófono.") }
            return
        }
        if (state.isListening) {
            speechManager.stop()
            _uiState.update { it.copy(isListening = false) }
        } else {
            ttsManager.stop()
            speechManager.listenInSpanish()
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
            )
        }

        viewModelScope.launch {
            try {
                // Memory of the last N messages (both sides) for context.
                val history = _uiState.value.messages
                    .takeLast(SIMULATION_MEMORY_SIZE)
                    .map { msg ->
                        SimulateTurnDto(
                            role = if (msg.sender == SimulationSender.USER) "user" else "ai",
                            text = if (msg.sender == SimulationSender.USER) {
                                msg.spanishText
                            } else {
                                msg.nativeScript.ifBlank { msg.spanishMeaning }
                            },
                        )
                    }
                val currentScenario = state.scenario
                val currentLanguage = state.language
                val response = workerApi.simulate(
                    scenario = currentScenario.id,
                    language = currentLanguage.id,
                    history = history,
                    message = text,
                )
                val aiMessage = SimulationMessage(
                    id = UUID.randomUUID().toString(),
                    sender = SimulationSender.AI,
                    nativeScript = response.native,
                    romanized = response.romanized,
                    spanishMeaning = response.spanish,
                )
                _uiState.update {
                    it.copy(messages = it.messages + aiMessage, isSending = false)
                }
                speakAiReply(response.native, currentLanguage)
            } catch (e: WorkerApiException) {
                Log.e(TAG, "onSendMessage WorkerApiException", e)
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = e.message ?: "Error al generar la respuesta.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "onSendMessage unexpected error", e)
                _uiState.update {
                    it.copy(isSending = false, errorMessage = "Ocurrió un error inesperado.")
                }
            }
        }
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
                _uiState.update {
                    it.copy(
                        isGeneratingFeedback = false,
                        feedback = feedback.ifBlank {
                            "No se pudo generar el feedback esta vez. Intenta de nuevo."
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "onFinishAndGetFeedback error", e)
                _uiState.update {
                    it.copy(
                        isGeneratingFeedback = false,
                        errorMessage = (e as? WorkerApiException)?.message
                            ?: "No se pudo generar el feedback. Intenta de nuevo.",
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

    // ---- Internal --------------------------------------------------------

    private fun resetConversation() {
        speechManager.cancel()
        ttsManager.stop()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                inputText = "",
                isListening = false,
                isSending = false,
                errorMessage = null,
                feedback = null,
            )
        }
    }

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
        val locale = when (language) {
            SimulationLanguage.JAPANESE -> Locale.JAPAN
            SimulationLanguage.KOREAN -> Locale.KOREA
        }
        ttsManager.setLocale(locale)
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
    }
}
