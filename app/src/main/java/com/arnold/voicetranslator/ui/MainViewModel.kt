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
import com.arnold.voicetranslator.data.offline.ModelNotDownloadedException
import com.arnold.voicetranslator.data.remote.WorkerApiClient
import com.arnold.voicetranslator.data.remote.WorkerApiException
import com.arnold.voicetranslator.ui.state.PipelineStatus
import com.arnold.voicetranslator.ui.state.LiveChatEntry
import com.arnold.voicetranslator.ui.state.LiveTurn
import com.arnold.voicetranslator.ui.state.ModelDownloadStatus
import com.arnold.voicetranslator.ui.state.OfflineModelInfo
import com.arnold.voicetranslator.ui.state.TranslationHistoryItem
import com.arnold.voicetranslator.ui.state.TranslatorUiState
import com.arnold.voicetranslator.util.JapaneseRomajiConverter
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
 *  - [WorkerApiClient] (online translation, incl. two-way conversation, via
 *    our Cloudflare Worker proxy â€” no API key lives in this app),
 *  - [MlKitOfflineTranslator] (offline fallback),
 * and exposes every meaningful flag as a single immutable [StateFlow].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Every online translation path (Spanish->target, Korean phonetic, and
    // the live-conversation reply-suggestion flow) now goes through our
    // Cloudflare Worker proxy. No API key of any kind lives in this app
    // anymore â€” the Worker holds the Google/DeepSeek secrets server-side.
    private val workerApi = WorkerApiClient()
    private val offlineTranslator = MlKitOfflineTranslator()

    private val speechManager = SpeechRecognitionManager(application.applicationContext)
    private val ttsManager = TtsManager(application.applicationContext)

    private val _uiState = MutableStateFlow(TranslatorUiState())
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null
    private var lastSpeechText: String? = null

    // Counts consecutive ERROR_LANGUAGE_UNAVAILABLE/ERROR_CLIENT/ERROR_LANGUAGE_NOT_SUPPORTED
    // failures in Live mode's continuous foreign-listening loop. When a
    // language's offline voice pack genuinely isn't available on this device
    // (confirmed real-device case: OnePlus only offers Chino/Coreano offline,
    // not Español/Japonés), every restart attempt fails again instantly —
    // capped so we don't spin forever draining battery, while still not
    // requiring the user to press "Reanudar" for the first several retries.
    private var offlineVoiceRetryCount = 0

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
            // the main thread first â€” SpeechRecognizer must be created from the
            // application's main thread or it throws a RuntimeException.
            viewModelScope.launch {
                if (_uiState.value.isLiveConversation &&
                    _uiState.value.liveTurn == LiveTurn.YOU &&
                    !_uiState.value.isLiveListeningPaused
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
            offlineVoiceRetryCount = 0
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
                        errorMessage = "No se capturÃ³ ningÃºn texto.",
                    )
                }
            } else {
                val isForeignSpeech = _uiState.value.isListeningForeign
                // Keep the microphone continuously open in live mode: immediately
                // restart foreign listening on the main thread as soon as a foreign
                // phrase is captured, so the mic never closes and the conversation
                // flows even if the speaker keeps talking while we translate.
                // (onResult runs on a binder thread, so hop to the main thread for
                // SpeechRecognizer.start().)
                if (_uiState.value.isLiveConversation &&
                    isForeignSpeech &&
                    !_uiState.value.isLiveListeningPaused
                ) {
                    viewModelScope.launch { startListeningForeignLanguage() }
                }
                translateAndGetOptions(
                    text = text,
                    targetLang = _uiState.value.targetLanguage,
                    isForeignSpeech = isForeignSpeech,
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
            // In live mode, transient silence (no speech / speech timeout) while
            // waiting for the foreign speaker should keep the mic open so the
            // conversation stays continuous. Restart listening on the main thread.
            val isSilence = message.contains("No se detectÃ³ habla") ||
                message.contains("Tiempo de escucha agotado")
            if (_uiState.value.isLiveConversation &&
                !_uiState.value.isLiveListeningPaused &&
                _uiState.value.liveTurn == LiveTurn.THEM &&
                isSilence
            ) {
                viewModelScope.launch { startListeningForeignLanguage() }
            }
        }

        speechManager.onEnd = {
            _uiState.update { it.copy(isListening = false) }
        }

        // "modo avión + modo sin conexión": the recognizer couldn't find an
        // installed offline voice pack for the requested language and there's
        // no network to fall back to. Show precise, actionable guidance
        // (Ajustes > Voz offline) instead of a generic error, which the user
        // reasonably reads as an app bug.
        speechManager.onOfflineVoiceUnavailable = {
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isListening = false,
                    liveTranscript = "",
                    // Some devices/idiomas simplemente no ofrecen paquete de
                    // voz offline para descargar (p. ej. solo Chino/Coreano
                    // en ciertos OnePlus) — en ese caso ningún ajuste lo
                    // arregla; hay que usar texto o desactivar modo avión.
                    voiceOfflineGuidance = "Este idioma no tiene reconocimiento de voz sin conexión " +
                        "en tu teléfono. Ve a Ajustes > Voz offline y descarga el paquete si aparece " +
                        "disponible; si no aparece, tu equipo no lo soporta offline — usa el modo de " +
                        "texto o desactiva el modo avión para reconocer por voz.",
                )
            }
            // Same as the "silence" case below: in Live mode, this shouldn't
            // feel like the mic "closed" — it should only stop listening when
            // the user explicitly pauses (Pausar button) or switches to the
            // other turn (habla Español). So keep the continuous foreign
            // listening loop going instead of requiring "Reanudar" every time
            // — but cap it: if the device's offline voice pack for this
            // language genuinely doesn't exist (confirmed real case: some
            // OnePlus models only offer Chino/Coreano offline, not
            // Español/Japonés), every retry fails again instantly, so an
            // uncapped loop would just burn battery forever.
            val live = _uiState.value
            if (live.isLiveConversation && !live.isLiveListeningPaused) {
                when (live.liveTurn) {
                    LiveTurn.THEM -> {
                        if (offlineVoiceRetryCount < MAX_OFFLINE_VOICE_RETRIES) {
                            offlineVoiceRetryCount++
                            // Small delay so a voice pack that's genuinely
                            // missing (and will error again instantly)
                            // doesn't spin in a tight retry loop — mirrors
                            // SpeechRecognitionManager's own retry delay.
                            viewModelScope.launch {
                                delay(600)
                                startListeningForeignLanguage()
                            }
                        }
                    }
                    LiveTurn.YOU -> {
                        // Pressing "hablar Español" failed (e.g. this device
                        // has no offline Spanish pack) — don't leave the
                        // conversation stuck on a dead YOU turn forever
                        // (previously the mic would never turn back on).
                        // Fall back to listening for the foreign speaker
                        // instead, since retrying Spanish would just fail
                        // again the same way.
                        viewModelScope.launch {
                            delay(600)
                            startLiveForeignTurn()
                        }
                    }
                    null -> Unit
                }
            }
        }
    }

    /** Dismisses the "voz offline" guidance banner shown after onOfflineVoiceUnavailable. */
    fun onDismissVoiceOfflineGuidance() {
        _uiState.update { it.copy(voiceOfflineGuidance = null) }
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
                isLiveListeningPaused = false,
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
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrÃ³fono.") }
            return
        }
        if (state.isListening) {
            speechManager.stop()
        } else {
            startSpeakingSpanish()
        }
    }

    /**
     * Mic #1 â€” "Hablar en EspaÃ±ol": transcribes Mexican Spanish and translates
     * it to the configured target language, then speaks it out (TTS in JA/KO).
     */
    fun onSpeakSpanishToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrÃ³fono.") }
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
     * Mic #2 â€” "Escuchar [Idioma nativo]": transcribes the active foreign
     * language (Japanese or Korean, per targetLanguage), translates it to
     * Mexican Spanish (shown + read aloud), and returns short transliterated
     * reply suggestions the user can tap to answer back.
     */
    fun onListenJapaneseToggle() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrÃ³fono.") }
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
     * hear it. Only enabled in "Escuchar JaponÃ©s" mode.
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
        refreshAllOfflineModelStates()
    }

    /**
     * Refreshes the downloaded/not-downloaded status of all three languages'
     * offline models, for the Settings screen's "Modelos Offline" section.
     */
    fun refreshAllOfflineModelStates() {
        viewModelScope.launch {
            TargetLanguage.entries.forEach { target ->
                val downloaded = withContext(Dispatchers.IO) {
                    offlineTranslator.isModelDownloaded(target)
                }
                _uiState.update { state ->
                    // Don't clobber a download in progress with a stale check.
                    val current = state.offlineModels[target]
                    if (current?.status == ModelDownloadStatus.DOWNLOADING) return@update state
                    state.copy(
                        offlineModels = state.offlineModels + (
                            target to OfflineModelInfo(
                                target = target,
                                status = if (downloaded) ModelDownloadStatus.DOWNLOADED else ModelDownloadStatus.NOT_DOWNLOADED,
                            )
                        ),
                    )
                }
            }
        }
    }

    /** Downloads the offline model for [target] (used by the 3-card "Modelos Offline" section). */
    fun downloadOfflineModelFor(target: TargetLanguage) {
        val current = _uiState.value.offlineModels[target]
        if (current?.status == ModelDownloadStatus.DOWNLOADED || current?.status == ModelDownloadStatus.DOWNLOADING) {
            return
        }
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    offlineModels = state.offlineModels + (
                        target to OfflineModelInfo(target, ModelDownloadStatus.DOWNLOADING, 0f)
                    ),
                )
            }
            val success = offlineTranslator.downloadModel(target) { progress ->
                _uiState.update { state ->
                    state.copy(
                        offlineModels = state.offlineModels + (
                            target to OfflineModelInfo(target, ModelDownloadStatus.DOWNLOADING, progress)
                        ),
                    )
                }
            }
            _uiState.update { state ->
                state.copy(
                    offlineModels = state.offlineModels + (
                        target to OfflineModelInfo(
                            target = target,
                            status = if (success) ModelDownloadStatus.DOWNLOADED else ModelDownloadStatus.NOT_DOWNLOADED,
                            progress = if (success) 1f else 0f,
                        )
                    ),
                    errorMessage = if (success) state.errorMessage else "No se pudo descargar el modelo de ${target.displayName}.",
                    // Keep the legacy single-language indicator in sync too,
                    // since it's still used by the quick "Descargar modelo"
                    // menu item for the currently selected language.
                    isModelDownloaded = if (target == state.targetLanguage) success else state.isModelDownloaded,
                )
            }
        }
    }

    /** Confirms the "falta el modelo offline de X" prompt and starts downloading it. */
    fun onConfirmDownloadMissingModel() {
        val target = _uiState.value.missingOfflineModelPrompt ?: return
        _uiState.update { it.copy(missingOfflineModelPrompt = null) }
        downloadOfflineModelFor(target)
    }

    /** Dismisses the "falta el modelo offline de X" prompt without downloading. */
    fun onDismissMissingModelPrompt() {
        _uiState.update { it.copy(missingOfflineModelPrompt = null) }
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
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrÃ³fono.") }
            return
        }
        if (state.isLiveConversation) {
            stopEverything()
            _uiState.update {
                it.copy(
                    isLiveConversation = false,
                    liveTurn = null,
                    liveTranscript = "",
                    isLiveListeningPaused = false,
                )
            }
        } else {
            stopEverything()
            _uiState.update {
                it.copy(
                    isLiveConversation = true,
                    liveTurn = null,
                    liveTranscript = "",
                    isLiveListeningPaused = false,
                )
            }
            startLiveForeignTurn()
        }
    }

    /**
     * Elects a suggested Romaji reply as the user's (TÃš) answer: speaks it out
     * loud in the foreign language, records it in the chat, and â€” once the TTS
     * finishes â€” automatically returns to listen for the foreign speaker (ELLOS).
     */
    fun onLiveSuggestionTapped(romajiSuggestion: String) {
        if (romajiSuggestion.isBlank()) return
        val state = _uiState.value
        val spanish = liveSuggestions[romajiSuggestion] ?: ""
        val kana = liveSuggestionsKana[romajiSuggestion]
        // Stop the continuous foreign listening so the reply can be spoken
        // aloud without mic competition, and mark this as the user's turn.
        speechManager.cancel()
        _uiState.update {
            it.copy(
                status = PipelineStatus.Speaking,
                liveTurn = LiveTurn.YOU,
                isListening = false,
                // Clear the previous ELLOS suggestions so they don't linger on
                // screen after the user has already replied.
                result = null,
                liveTranscript = "",
                liveMessages = it.liveMessages + LiveChatEntry(
                    turn = LiveTurn.YOU,
                    text = romajiSuggestion,
                    translation = spanish.ifBlank { romajiSuggestion },
                    textKana = kana,
                ),
            )
        }
        speakInForeign(romajiSuggestion)
    }

    /** Holds the last estimated meaning for each suggested reply, so the chat
     *  bubble for a chosen suggestion can show its Spanish meaning. */
    private val liveSuggestions = HashMap<String, String>()

    /** Holds the native kana/kanji script for each suggested reply (keyed by
     *  its romaji), so a tapped-suggestion bubble can also show the kana. */
    private val liveSuggestionsKana = HashMap<String, String>()

    /**
     * Listens to the user speaking in Spanish (TÃš), translates it to the target
     * language, and speaks it. When the TTS finishes, [ttsManager.onSpeakFinished]
     * re-listens for the foreign speaker.
     */
    fun onLiveSpeakSpanish() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = "Se requiere permiso del micrÃ³fono.") }
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

    /**
     * Pauses/resumes the continuous foreign listening in live mode. When paused,
     * the app stops listening for the foreign speaker (so it won't pick up
     * random sounds or alternate turns by itself) and only captures when the
     * user resumes. Resuming immediately re-starts listening for the foreign
     * speaker, so their next words are picked up automatically.
     */
    fun onToggleLiveListeningPause() {
        val state = _uiState.value
        if (!state.isLiveConversation || !state.hasMicPermission) return
        if (state.isLiveListeningPaused) {
            // Resume listening.
            _uiState.update { it.copy(isLiveListeningPaused = false, liveTranscript = "") }
            startLiveForeignTurn()
        } else {
            // Pause listening.
            speechManager.cancel()
            _uiState.update {
                it.copy(
                    isLiveListeningPaused = true,
                    isListening = false,
                    liveTurn = LiveTurn.THEM,
                    liveTranscript = "",
                )
            }
        }
    }

    /** Begins a foreign (ELLOS) listening turn within the live conversation. */
    private fun startLiveForeignTurn() {
        // A fresh, user/flow-initiated turn (not an error retry) — reset the
        // offline-voice-unavailable retry cap so a manual restart (turn
        // switch, resume from pause, etc.) always gets a full set of retries.
        offlineVoiceRetryCount = 0
        // Drop the previous turn's romaji->spanish lookup map too, not just the
        // on-screen cards, so a stale suggestion never resolves to an old
        // meaning if it somehow lingered (e.g. a duplicate romaji string from a
        // prior turn).
        liveSuggestions.clear()
        liveSuggestionsKana.clear()
        _uiState.update {
            it.copy(
                liveTurn = LiveTurn.THEM,
                // Clear stale suggestion cards while listening for the next
                // foreign phrase, so old replies don't linger after a response.
                result = null,
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
        // Kept in sync right before every start(): without this, the system
        // recognizer defaults to online recognition even with a downloaded
        // offline language pack, so "Modo sin conexión" + airplane mode would
        // still fail for a language whose pack IS installed (e.g. Coreano).
        speechManager.preferOfflineRecognition = _uiState.value.isOfflineMode
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
                // Uses the Kuromoji-backed converter (not the old kana-only
                // KanaRomaji) so kanji in what the foreign speaker said is
                // actually romanized too, instead of leaking through
                // unconverted (e.g. "本当nisumimasen" instead of "hontou ni
                // sumimasen") — the exact bug reported in the romaji field.
                sourceRomaji = if (isForeignSpeech && targetLang == TargetLanguage.JAPANESE) {
                    JapaneseRomajiConverter.kanjiToRomaji(text)
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
            } catch (e: ModelNotDownloadedException) {
                // Specific "modo avión" case: don't just show an error banner,
                // prompt to download the exact missing model.
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        missingOfflineModelPrompt = e.target,
                    )
                }
            } catch (e: MlKitOfflineException) {
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = e.message ?: "Error de traducciÃ³n offline.",
                    )
                }
            } catch (e: WorkerApiException) {
                Log.e(TAG, "translate WorkerApiException", e)
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = e.message ?: "Error al traducir.",
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = "OcurriÃ³ un error inesperado.",
                    )
                }
            }
        }
    }

    /**
     * Simple Spanish -> target translation for the standard (non-live) flow.
     * Korean goes through the Worker's /translate-ko-phonetic (DeepSeek under
     * the hood â€” needs the Spanish-phonetic transliteration prompt, not raw
     * Hangul). Japanese/English go through /translate (Google Translate);
     * Japanese output is additionally romanized client-side via
     * [JapaneseRomajiConverter] (Kuromoji) since Google returns kana/kanji,
     * not romaji. Unlike the old [KanaRomaji]-only fallback, this fully
     * romanizes kanji too (not just kana) via morphological analysis.
     */
    private suspend fun onlineTranslate(text: String, target: TargetLanguage): TranslationResult =
        when (target) {
            TargetLanguage.KOREAN -> workerApi.translateKoreanPhonetic(text)
            TargetLanguage.JAPANESE, TargetLanguage.ENGLISH -> {
                val translated = workerApi.translate(text, target.id)
                val mainTranslation = if (target == TargetLanguage.JAPANESE) {
                    val romaji = JapaneseRomajiConverter.kanjiToRomaji(translated)
                    Log.d(
                        TAG,
                        "JA_ROMAJI_DEBUG onlineTranslate: input=\"$text\" googleRaw=\"$translated\" romaji=\"$romaji\"",
                    )
                    romaji
                } else {
                    translated
                }
                TranslationResult(
                    mainTranslation = mainTranslation.ifBlank { text },
                    // Keep the raw Google output (kana/kanji for Japanese) so
                    // the UI can show the native script alongside the Romaji
                    // for native speakers to read too.
                    nativeScript = if (target == TargetLanguage.JAPANESE) translated else null,
                )
            }
        }

    /**
     * Two-Way Conversation path used when the recognizer heard the foreign
     * speaker's language (Japanese, Korean, or English, per [foreignLang]):
     * the Worker's /converse (DeepSeek under the hood) returns a main
     * translation into Mexican Spanish plus short transliterated reply
     * suggestions the user can tap to answer back.
     */
    private suspend fun conversationTranslate(
        text: String,
        foreignLang: TargetLanguage,
    ): TranslationResult {
        val result = workerApi.converse(text, foreignLang.id)
        if (foreignLang != TargetLanguage.JAPANESE) return result
        // Defensive guard: DeepSeek's /converse occasionally leaks raw
        // kana/kanji into a suggestion's "romaji" field instead of an actual
        // Latin-script transliteration ("se bugea" â€” kana shows where romaji
        // should be). Detect Japanese script and re-romanize via Kuromoji so
        // the romaji slot is always guaranteed to be Latin text.
        return result.copy(
            replySuggestions = result.replySuggestions.map { suggestion ->
                val fixedRomaji = sanitizeJapaneseRomaji(suggestion.romaji)
                if (fixedRomaji != suggestion.romaji) {
                    Log.d(
                        TAG,
                        "JA_ROMAJI_DEBUG conversationTranslate: fixed leaked kana in suggestion romaji: " +
                            "\"${suggestion.romaji}\" -> \"$fixedRomaji\"",
                    )
                }
                suggestion.copy(romaji = fixedRomaji)
            },
        )
    }

    /** True if [text] contains Hiragana, Katakana, or Kanji characters. */
    private fun containsJapaneseScript(text: String): Boolean =
        text.any { ch -> ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' }

    /**
     * Ensures a "romaji" string is actually Latin-script romaji. If it
     * contains kana/kanji (a leaked model mistake), re-romanizes it via
     * [JapaneseRomajiConverter]; otherwise returns it unchanged.
     */
    private fun sanitizeJapaneseRomaji(text: String): String =
        if (containsJapaneseScript(text)) JapaneseRomajiConverter.kanjiToRomaji(text) else text

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
            result.replySuggestions.forEach {
                it.spanish.takeIf { s -> s.isNotBlank() }
                    ?.let { s -> liveSuggestions[it.romaji] = s }
                it.kana.takeIf { k -> k.isNotBlank() }
                    ?.let { k -> liveSuggestionsKana[it.romaji] = k }
            }
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
                        // TÃš turn translated into Japanese: also carry the
                        // native kana/kanji script so it can be shown next to
                        // the Romaji for a native speaker to read.
                        translationKana = if (!isForeignSpeech && target == TargetLanguage.JAPANESE) {
                            result.nativeScript?.takeIf { kana -> kana != result.mainTranslation }
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
            _uiState.update { it.copy(errorMessage = "El modelo ya estÃ¡ descargado.") }
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
        liveSuggestionsKana.clear()
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
        workerApi.close()
    }

    private companion object {
        const val TAG = "VoiceTranslator"
        /** Max consecutive auto-retries when the offline voice pack is missing. */
        const val MAX_OFFLINE_VOICE_RETRIES = 5
        const val SPEAK_FALLBACK_MILLIS = 15_000L
    }
}
