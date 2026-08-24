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
import com.arnold.voicetranslator.ui.state.LiveChatEntry
import com.arnold.voicetranslator.ui.state.LiveTurn
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
            // In live conversation mode, after the user's reply finishes
            // playing aloud, automatically listen for the foreign speaker.
            // NOTE: onSpeakFinished runs on the TTS binder thread, so hop onto
            // the main thread first — SpeechRecognizer must be created from the
            // application's main thread or it throws a RuntimeException.
            viewModelScope.launch {
                if (_uiState.value.isLiveConversation &&
                    _uiState.value.liveTurn == LiveTurn.YOU
                ) {
                    startLiveForeignTurn()
                }
            }
        }
    }

    private fun wireAudioCallbacks() {
        speechManager.onPartialResult = { partial ->
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    // Surface a live transcript while in a live conversation so
                    // the user can see the foreign speaker (or their own) words
                    // as they are being heard.
                    liveTranscript = if (it.isLiveConversation) partial else "",
                )
            }
        }

        speechManager.onResult = { text ->
            lastSpeechText = null
            Log.d(TAG, "speech onResult: \"$text\"")
            _uiState.update {
                it.copy(
                    isListening = false,
                    liveTranscript = "",
                )
            }
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
                    isForeignSpeech = _uiState.value.isListeningForeign,
                )
            }
        }

        speechManager.onError = { message ->
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isListening = false,
                    errorMessage = message,
                    liveTranscript = "",
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
        _uiState.update {
            it.copy(
                isLiveConversation = false,
                liveTurn = null,
                liveMessages = emptyList(),
            )
        }
        applyTargetLanguage(target)
    }

    /** Toggles offline mode on/off and refreshes the download state. */
    fun onToggleOfflineMode(offline: Boolean) {
        stopEverything()
        _uiState.update {
            it.copy(
                isOfflineMode = offline,
                // Clear any previous translation/history: on-device (ML Kit)
                // results are raw kana/Hangul and would otherwise stay stuck on
                // screen after switching modes.
                result = null,
                historyList = emptyList(),
            )
        }
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
        _uiState.update { it.copy(isListeningToJapanese = false, isListeningForeign = false) }
        translateAndGetOptions(
            text = input.trim(),
            targetLang = _uiState.value.targetLanguage,
            isForeignSpeech = false,
        )
    }

    /**
     * Writes the foreign speaker's language manually (Japanese or Korean,
     * per targetLanguage) instead of relying on the speech recognizer, useful
     * when the recognizer mishears a question or the user prefers to type.
     * Routes through the same conversation pipeline.
     */
    fun onTranslateJapaneseText(input: String) {
        if (input.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Escribe algo para traducir.") }
            return
        }
        speechManager.cancel()
        ttsManager.stop()
        val foreign = _uiState.value.targetLanguage == TargetLanguage.JAPANESE || _uiState.value.targetLanguage == TargetLanguage.KOREAN
        _uiState.update {
            it.copy(
                isListeningToJapanese = _uiState.value.targetLanguage == TargetLanguage.JAPANESE,
                isListeningForeign = foreign,
            )
        }
        translateAndGetOptions(
            text = input.trim(),
            targetLang = _uiState.value.targetLanguage,
            isForeignSpeech = true,
        )
    }

    /**
     * Mic #2 — "Escuchar [Idioma nativo]": transcribes the active foreign
     * language (Japanese or Korean, per targetLanguage), translates it to
     * Mexican Spanish (shown + read aloud), and returns short transliterated
     * reply suggestions the user can tap to answer back.
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
            startListeningForeignLanguage()
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
        speakInForeign(romajiSuggestion)
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

    /** Toggles whether reply suggestions also display Japanese kana/kanji. */
    fun onToggleShowKana(enabled: Boolean) {
        _uiState.update { it.copy(isShowKana = enabled) }
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

    // ---- Live conversation mode -------------------------------------------

    /**
     * Toggles the live (continuous) two-way conversation mode. When enabled, the
     * app begins listening for the foreign speaker (ELLOS); when disabled it
     * tears down the loop and returns to the standard mic flow.
     */
    fun onToggleLiveConversation() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrófono.") }
            return
        }
        if (state.isLiveConversation) {
            stopEverything()
            _uiState.update {
                it.copy(
                    isLiveConversation = false,
                    liveTurn = null,
                    liveTranscript = "",
                )
            }
        } else {
            stopEverything()
            _uiState.update {
                it.copy(
                    isLiveConversation = true,
                    liveTurn = null,
                    liveTranscript = "",
                )
            }
            startLiveForeignTurn()
        }
    }

    /**
     * Elects a suggested Romaji reply as the user's (TÚ) answer: speaks it out
     * loud in the foreign language, records it in the chat, and — once the TTS
     * finishes — automatically returns to listen for the foreign speaker (ELLOS).
     */
    fun onLiveSuggestionTapped(romajiSuggestion: String) {
        if (romajiSuggestion.isBlank()) return
        val state = _uiState.value
        val spanish = liveSuggestions[romajiSuggestion] ?: ""
        // Stop the continuous foreign listening so the reply can be spoken
        // aloud without mic competition, and mark this as the user's turn.
        speechManager.cancel()
        _uiState.update {
            it.copy(
                status = PipelineStatus.Speaking,
                liveTurn = LiveTurn.YOU,
                isListening = false,
                liveTranscript = "",
                liveMessages = it.liveMessages + LiveChatEntry(
                    turn = LiveTurn.YOU,
                    text = romajiSuggestion,
                    translation = spanish.ifBlank { romajiSuggestion },
                ),
            )
        }
        speakInForeign(romajiSuggestion)
    }

    /** Holds the last estimated meaning for each suggested reply, so the chat
     *  bubble for a chosen suggestion can show its Spanish meaning. */
    private val liveSuggestions = HashMap<String, String>()

    /**
     * Listens to the user speaking in Spanish (TÚ), translates it to the target
     * language, and speaks it. When the TTS finishes, [ttsManager.onSpeakFinished]
     * re-listens for the foreign speaker.
     */
    fun onLiveSpeakSpanish() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrófono.") }
            return
        }
        _uiState.update {
            it.copy(
                liveTurn = LiveTurn.YOU,
                liveTranscript = "",
            )
        }
        startSpeakingSpanish()
    }

    /** Begins a foreign (ELLOS) listening turn within the live conversation. */
    private fun startLiveForeignTurn() {
        _uiState.update {
            it.copy(
                liveTurn = LiveTurn.THEM,
                liveTranscript = "",
                errorMessage = null,
            )
        }
        startListeningForeignLanguage()
    }

    // ---- Internal pipeline --------------------------------------------------

    private fun applyTargetLanguage(target: TargetLanguage) {
        _uiState.value = _uiState.value.copy(targetLanguage = target)
        ttsManager.setLocale(localeFor(target))
        refreshDownloadState()
    }

    private fun startSpeakingSpanish() {
        speechManager.listenInSpanish()
        beginListening(isForeignSpeech = false)
    }

    private fun startListeningForeignLanguage() {
        val target = _uiState.value.targetLanguage
        when (target) {
            TargetLanguage.JAPANESE -> speechManager.listenInJapanese()
            TargetLanguage.KOREAN -> speechManager.listenInKorean()
            TargetLanguage.ENGLISH -> speechManager.listenInEnglish()
        }
        beginListening(isForeignSpeech = true)
    }

    private fun beginListening(isForeignSpeech: Boolean) {
        _uiState.update {
            it.copy(
                status = PipelineStatus.Listening,
                isListening = true,
                isListeningForeign = isForeignSpeech,
                // The secondary "listen" mic always listens for the active
                // foreign speaker's language (Japanese or Korean, per
                // targetLanguage). "isListeningToJapanese" is kept as the
                // signal the UI uses to show foreign-language reply cards.
                isListeningToJapanese = if (isForeignSpeech) {
                    _uiState.value.targetLanguage == TargetLanguage.JAPANESE
                } else {
                    false
                },
                errorMessage = null,
                isTranslating = false,
                isSpeaking = false,
                sourceText = null,
                sourceRomaji = null,
                liveTranscript = "",
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
        isForeignSpeech: Boolean,
    ) {
        lastSpeechText = text
        val currentTarget = targetLang
        Log.d(TAG, "translate: text=\"$text\" target=$currentTarget isForeignSpeech=$isForeignSpeech offline=${_uiState.value.isOfflineMode}")

        _uiState.update {
            it.copy(
                status = PipelineStatus.Translating,
                isTranslating = true,
                isListening = false,
                errorMessage = null,
                isSpeaking = false,
                sourceText = text,
                sourceRomaji = if (isForeignSpeech && targetLang == TargetLanguage.JAPANESE) {
                    KanaRomaji.toRomajiIfKana(text)
                } else {
                    null
                },
            )
        }

        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (_uiState.value.isOfflineMode) {
                        offlineTranslate(text, currentTarget)
                    } else if (isForeignSpeech) {
                        conversationTranslate(text, targetLang)
                    } else {
                        // Spanish heard: normal translate to target language.
                        onlineTranslate(text, currentTarget)
                    }
                }
                Log.d(TAG, "translate result: main=\"${result.mainTranslation}\" alternatives=${result.alternatives} replySuggestions=${result.replySuggestions}")
                onTranslationReady(result, currentTarget, isForeignSpeech)
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
     * Two-Way Conversation path used when the recognizer heard the foreign
     * speaker's language (Japanese or Korean, per [foreignLang]): the assistant
     * returns a main translation into Mexican Spanish plus short transliterated
     * reply suggestions the user can tap to answer back.
     */
    private suspend fun conversationTranslate(
        text: String,
        foreignLang: TargetLanguage,
    ): TranslationResult = deepSeekApi.translateConversation(text, foreignLang)

    private suspend fun offlineTranslate(text: String, target: TargetLanguage): TranslationResult =
        offlineTranslator.translate(text, target)

    private fun onTranslationReady(
        result: TranslationResult,
        target: TargetLanguage,
        isForeignSpeech: Boolean,
    ) {
        lastSpeechText = null
        val state = _uiState.value

        // Live conversation path: render a chat bubble and, on the user's turn,
        // play the reply aloud. The auto-return to the foreign listener happens
        // in ttsManager.onSpeakFinished.
        if (state.isLiveConversation) {
            result.replySuggestions.forEach { it.spanish.takeIf { s -> s.isNotBlank() }
                ?.let { s -> liveSuggestions[it.romaji] = s } }
            _uiState.update {
                it.copy(
                    result = result,
                    isTranslating = false,
                    isSpeaking = false,
                    errorMessage = null,
                    liveTurn = if (isForeignSpeech) LiveTurn.THEM else LiveTurn.YOU,
                    liveMessages = it.liveMessages + LiveChatEntry(
                        turn = if (isForeignSpeech) LiveTurn.THEM else LiveTurn.YOU,
                        text = state.sourceText ?: "",
                        translation = result.mainTranslation,
                        sourceRomaji = if (isForeignSpeech && target == TargetLanguage.JAPANESE) {
                            state.sourceRomaji
                        } else {
                            null
                        },
                    ),
                )
            }
            // On the user's spoken (Spanish) turn, read the translation aloud in
            // the foreign language; foreign (ELLOS) turns are never spoken here.
            if (!isForeignSpeech && state.isAutoSpeakEnabled) {
                speakInForeign(result.mainTranslation)
            }
            // Continuous live mode: after capturing a foreign phrase, immediately
            // keep listening for the next thing the foreign speaker says, so the
            // conversation flows without requiring the user to tap anything.
            // The user can interrupt at any time by speaking Spanish or tapping a
            // suggestion. (onTranslationReady runs on the main thread, so this is safe.)
            if (isForeignSpeech && !speechManager.isListening) {
                startLiveForeignTurn()
            }
            return
        }

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
                    isJapaneseInput = isForeignSpeech,
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
        val targetLocale = if (state.isListeningForeign) {
            // The user is listening to the foreign speaker; read the Spanish
            // translation out for the user.
            Locale("es", "MX")
        } else {
            localeFor(state.targetLanguage)
        }
        ttsManager.setLocale(targetLocale)
        ttsManager.speak(text)
        applySpeakFallback()
    }

    /** Speaks a transliterated reply suggestion in the foreign language. */
    private fun speakInForeign(text: String) {
        ttsManager.setLocale(localeFor(_uiState.value.targetLanguage))
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
        liveSuggestions.clear()
        _uiState.update {
            it.copy(
                status = PipelineStatus.Idle,
                isListening = false,
                isListeningToJapanese = false,
                isListeningForeign = false,
                isTranslating = false,
                isSpeaking = false,
                sourceText = null,
                sourceRomaji = null,
                liveTranscript = "",
            )
        }
    }

    private fun localeFor(target: TargetLanguage): Locale = when (target) {
        TargetLanguage.JAPANESE -> Locale.JAPAN
        TargetLanguage.KOREAN -> Locale.KOREA
        TargetLanguage.ENGLISH -> Locale.ENGLISH
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
