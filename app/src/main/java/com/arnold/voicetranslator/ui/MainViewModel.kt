package com.arnold.voicetranslator.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arnold.voicetranslator.audio.SpeechRecognitionManager
import com.arnold.voicetranslator.audio.TtsManager
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult
import com.arnold.voicetranslator.data.offline.MlKitOfflineException
import com.arnold.voicetranslator.data.offline.MlKitOfflineTranslator
import com.arnold.voicetranslator.data.remote.DeepSeekApiClient
import com.arnold.voicetranslator.data.remote.DeepSeekException
import com.arnold.voicetranslator.ui.state.PipelineStatus
import com.arnold.voicetranslator.ui.state.TranslationHistoryItem
import com.arnold.voicetranslator.ui.state.TranslatorUiState
import com.arnold.voicetranslator.util.KanaRomaji
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Central state holder for the voice-to-voice translation pipeline.
 *
 * Wires together:
 *  - native [SpeechRecognitionManager] (switchable Spanish / Japanese STT),
 *  - [TtsManager] (Spanish / Japanese / Korean playback),
 *  - [DeepSeekApiClient] (online translation, incl. two-way conversation),
 *  - [MlKitOfflineTranslator] (offline fallback),
 * and exposes every meaningful flag as a single immutable [StateFlow].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val deepSeekApi = DeepSeekApiClient()
    private val offlineTranslator = MlKitOfflineTranslator()

    private val speechManager = SpeechRecognitionManager(application.applicationContext)
    private val ttsManager = TtsManager(application.applicationContext)

    private val _uiState = MutableStateFlow(TranslatorUiState())
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null
    private var lastSpeechText: String? = null

    init {
        wireAudioCallbacks()
        checkInitialModelState()

        // Auto-talk once the TTS engine reports ready.
        ttsManager.onReady = {
            _uiState.update { it.copy(isTtsAvailable = true) }
            val pending = lastSpeechText
            if (pending != null && _uiState.value.isSpeaking) {
                speakSpeech(pending)
            }
        }
        ttsManager.onSpeakFinished = {
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isSpeaking = false,
                    errorMessage = null,
                )
            }
        }
    }

    private fun wireAudioCallbacks() {
        speechManager.onPartialResult = { partial ->
            _uiState.update { it.copy(errorMessage = null) }
        }

        speechManager.onResult = { text ->
            lastSpeechText = null
            Log.d(TAG, "speech onResult: \"$text\"")
            _uiState.update { it.copy(isListening = false) }
            if (text.isBlank()) {
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        errorMessage = "No se capturó ningún texto.",
                    )
                }
            } else {
                translateAndGetOptions(
                    text = text,
                    targetLang = _uiState.value.targetLanguage,
                    isJapaneseInput = _uiState.value.isListeningToJapanese,
                )
            }
        }

        speechManager.onError = { message ->
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isListening = false,
                    errorMessage = message,
                )
            }
        }

        speechManager.onEnd = {
            _uiState.update { it.copy(isListening = false) }
        }
    }

    private fun checkInitialModelState() {
        viewModelScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                offlineTranslator.isModelDownloaded(_uiState.value.targetLanguage)
            }
            _uiState.update {
                it.copy(isModelDownloaded = downloaded)
            }
        }
    }

    // ---- User actions -------------------------------------------------------

    /** Records whether the app holds record-audio permission. */
    fun onPermissionResult(hasMicPermission: Boolean) {
        _uiState.update { it.copy(hasMicPermission = hasMicPermission) }
    }

    /** Toggles between Japanese and Korean for the standard translate mode. */
    fun onSelectLanguage(target: TargetLanguage) {
        if (target == _uiState.value.targetLanguage) return
        stopEverything()
        applyTargetLanguage(target)
    }

    /** Toggles offline mode on/off and refreshes the download state. */
    fun onToggleOfflineMode(offline: Boolean) {
        stopEverything()
        _uiState.update { it.copy(isOfflineMode = offline) }
        if (offline) refreshDownloadState()
    }

    /**
     * Legacy single-button toggle (kept for back-compat): starts/stops the
     * Spanish -> target standard translation flow.
     */
    fun onMicToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrófono.") }
            return
        }
        if (state.isListening) {
            speechManager.stop()
        } else {
            startSpeakingSpanish()
        }
    }

    /**
     * Mic #1 — "Hablar en Español": transcribes Mexican Spanish and translates
     * it to the configured target language, then speaks it out (TTS in JA/KO).
     */
    fun onSpeakSpanishToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrófono.") }
            return
        }
        if (state.isListening) {
            speechManager.stop()
        } else {
            startSpeakingSpanish()
        }
    }

    /**
     * Writes a Spanish phrase manually instead of relying on speech-to-text,
     * useful when the recognizer mishears a question. Routes through the same
     * pipeline (translate + auto-speak).
     */
    fun onTranslateSpanishText(input: String) {
        if (input.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Escribe algo para traducir.") }
            return
        }
        speechManager.cancel()
        ttsManager.stop()
        _uiState.update { it.copy(isListeningToJapanese = false) }
        translateAndGetOptions(
            text = input.trim(),
            targetLang = _uiState.value.targetLanguage,
            isJapaneseInput = false,
        )
    }

    /**
     * Mic #2 — "Escuchar Japonés": transcribes Japanese speech, translates it
     * to Mexican Spanish (shown + read aloud), and returns short Romaji reply
     * suggestions the user can tap to answer back.
     */
    fun onListenJapaneseToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrófono.") }
            return
        }
        if (state.isListening) {
            speechManager.stop()
        } else {
            startListeningJapanese()
        }
    }

    /**
     * Reads a suggested Romaji reply out loud so the local Japanese speaker can
     * hear it. Only enabled in "Escuchar Japonés" mode.
     */
    fun onSuggestionTapped(romajiSuggestion: String) {
        if (romajiSuggestion.isBlank()) return
        _uiState.update {
            it.copy(
                status = PipelineStatus.Speaking,
                isSpeaking = true,
                errorMessage = null,
            )
        }
        speakInJapanese(romajiSuggestion)
    }

    /** Downloads (or re-downloads) the model for the active target language. */
    fun onDownloadModel() {
        startDownload(_uiState.value.targetLanguage)
    }

    /** Refreshes the "model downloaded" indicator from disk. */
    fun refreshDownloadState() {
        val target = _uiState.value.targetLanguage
        viewModelScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                offlineTranslator.isModelDownloaded(target)
            }
            _uiState.update { it.copy(isModelDownloaded = downloaded) }
        }
    }

    /** Replays the current main translation with the appropriate TTS locale. */
    fun onReplay() {
        val result = _uiState.value.result ?: return
        replay(result)
    }

    /** Toggles the fullscreen "Anime Subtitles" overlay. */
    fun onToggleSubtitles() {
        _uiState.update { it.copy(isSubtitlesMode = !it.isSubtitlesMode) }
    }

    /** Toggles whether a finished translation is read aloud automatically. */
    fun onToggleAutoSpeak(enabled: Boolean) {
        _uiState.update { it.copy(isAutoSpeakEnabled = enabled) }
        if (!enabled) {
            ttsManager.stop()
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isSpeaking = false,
                )
            }
        }
    }

    /** Clears the conversation history feed. */
    fun onClearHistory() {
        _uiState.update { it.copy(historyList = emptyList()) }
    }

    // ---- Internal pipeline --------------------------------------------------

    private fun applyTargetLanguage(target: TargetLanguage) {
        _uiState.value = _uiState.value.copy(targetLanguage = target)
        ttsManager.setLocale(localeFor(target))
        refreshDownloadState()
    }

    private fun startSpeakingSpanish() {
        speechManager.listenInSpanish()
        beginListening(isJapaneseInput = false)
    }

    private fun startListeningJapanese() {
        speechManager.listenInJapanese()
        beginListening(isJapaneseInput = true)
    }

    private fun beginListening(isJapaneseInput: Boolean) {
        _uiState.update {
            it.copy(
                status = PipelineStatus.Listening,
                isListening = true,
                isListeningToJapanese = isJapaneseInput,
                errorMessage = null,
                isTranslating = false,
                isSpeaking = false,
                sourceText = null,
                sourceRomaji = null,
            )
        }
        ttsManager.stop()
        speechManager.start()
    }

    /**
     * Entry point for any recognized speech. Forwards to the conversation
     * translator when a language switch is involved; otherwise falls back to
     * the classic one-directional translate+speak path.
     */
    fun translateAndGetOptions(
        text: String,
        targetLang: TargetLanguage,
        isJapaneseInput: Boolean,
    ) {
        lastSpeechText = text
        val currentTarget = targetLang
        Log.d(TAG, "translate: text=\"$text\" target=$currentTarget isJapaneseInput=$isJapaneseInput offline=${_uiState.value.isOfflineMode}")

        _uiState.update {
            it.copy(
                status = PipelineStatus.Translating,
                isTranslating = true,
                isListening = false,
                errorMessage = null,
                isSpeaking = false,
                sourceText = text,
                sourceRomaji = if (isJapaneseInput) KanaRomaji.toRomajiIfKana(text) else null,
            )
        }

        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (_uiState.value.isOfflineMode) {
                        offlineTranslate(text, currentTarget)
                    } else if (isJapaneseInput) {
                        conversationTranslate(text, isJapaneseInput = true)
                    } else {
                        // Spanish heard: normal translate to target language.
                        onlineTranslate(text, currentTarget)
                    }
                }
                Log.d(TAG, "translate result: main=\"${result.mainTranslation}\" alternatives=${result.alternatives} replySuggestions=${result.replySuggestions}")
                onTranslationReady(result, currentTarget, isJapaneseInput)
            } catch (e: DeepSeekException) {
                Log.e(TAG, "translate DeepSeekException", e)
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = e.message ?: "Error al traducir.",
                    )
                }
            } catch (e: MlKitOfflineException) {
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = e.message ?: "Error de traducción offline.",
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = "Ocurrió un error inesperado.",
                    )
                }
            }
        }
    }

    private suspend fun onlineTranslate(text: String, target: TargetLanguage): TranslationResult =
        deepSeekApi.translate(text, target)

    /**
     * Two-Way Conversation path used when the recognizer heard Japanese: the
     * assistant returns a main translation into Mexican Spanish plus short
     * Romaji reply suggestions in `alternatives`.
     */
    private suspend fun conversationTranslate(
        text: String,
        isJapaneseInput: Boolean,
    ): TranslationResult = deepSeekApi.translateConversation(text, isJapaneseInput)

    private suspend fun offlineTranslate(text: String, target: TargetLanguage): TranslationResult =
        offlineTranslator.translate(text, target)

    private fun onTranslationReady(
        result: TranslationResult,
        target: TargetLanguage,
        isJapaneseInput: Boolean,
    ) {
        lastSpeechText = null
        val state = _uiState.value
        val sourceText = state.sourceText
        val autoSourceText = sourceText ?: ""
        val autoSpeak = state.isAutoSpeakEnabled
        _uiState.update {
            it.copy(
                result = result,
                status = if (autoSpeak) PipelineStatus.Speaking else PipelineStatus.Idle,
                isTranslating = false,
                isSpeaking = autoSpeak,
                errorMessage = null,
                historyList = it.historyList + TranslationHistoryItem(
                    sourceText = autoSourceText,
                    sourceRomaji = state.sourceRomaji,
                    translation = result.mainTranslation,
                    isJapaneseInput = isJapaneseInput,
                ),
            )
        }
        // Only the *automatic* read-out respects the auto-speak toggle. Tapping
        // "Reproducir" or a Romaji suggestion always speaks explicitly.
        if (autoSpeak) {
            speakSpeech(result.mainTranslation)
        }
    }

    private fun speakSpeech(text: String) {
        val state = _uiState.value
        val targetLocale = if (state.isListeningToJapanese) {
            // The user is listening to a Japanese speaker; read the translation
            // out in Mexican Spanish for the user.
            Locale("es", "MX")
        } else {
            localeFor(state.targetLanguage)
        }
        ttsManager.setLocale(targetLocale)
        ttsManager.speak(text)
        applySpeakFallback()
    }

    /** Speaks a Romaji suggestion out loud in Japanese. */
    private fun speakInJapanese(text: String) {
        ttsManager.setLocale(Locale.JAPAN)
        ttsManager.speak(text)
        applySpeakFallback()
    }

    private fun applySpeakFallback() {
        viewModelScope.launch {
            delay(SPEAK_FALLBACK_MILLIS)
            if (_uiState.value.isSpeaking) {
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isSpeaking = false,
                    )
                }
            }
        }
    }

    private fun replay(result: TranslationResult) {
        _uiState.update {
            it.copy(
                status = PipelineStatus.Speaking,
                isSpeaking = true,
                errorMessage = null,
            )
        }
        speakSpeech(result.mainTranslation)
    }

    private fun startDownload(target: TargetLanguage) = viewModelScope.launch {
        // Already downloaded for this language, do nothing.
        if (_uiState.value.isModelDownloaded) {
            _uiState.update { it.copy(errorMessage = "El modelo ya está descargado.") }
            return@launch
        }

        _uiState.update { it.copy(isDownloadingModel = true, downloadProgress = 0f) }
        val success = offlineTranslator.downloadModel(target) { progress ->
            _uiState.update { it.copy(downloadProgress = progress) }
        }
        _uiState.update {
            it.copy(
                isDownloadingModel = false,
                isModelDownloaded = success,
                downloadProgress = if (success) 1f else 0f,
                errorMessage = if (success) null else "No se pudo descargar el modelo.",
            )
        }
    }

    private fun stopEverything() {
        translateJob?.cancel()
        lastSpeechText = null
        speechManager.cancel()
        ttsManager.stop()
        _uiState.update {
            it.copy(
                status = PipelineStatus.Idle,
                isListening = false,
                isListeningToJapanese = false,
                isTranslating = false,
                isSpeaking = false,
                sourceText = null,
                sourceRomaji = null,
            )
        }
    }

    private fun localeFor(target: TargetLanguage): Locale = when (target) {
        TargetLanguage.JAPANESE -> Locale.JAPAN
        TargetLanguage.KOREAN -> Locale.KOREA
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.release()
        ttsManager.release()
        deepSeekApi.close()
    }

    private companion object {
        const val TAG = "VoiceTranslator"
        const val SPEAK_FALLBACK_MILLIS = 15_000L
    }
}
