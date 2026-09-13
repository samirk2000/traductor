package com.arnold.voicetranslator.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.arnold.voicetranslator.R
import androidx.lifecycle.viewModelScope
import com.arnold.voicetranslator.audio.SpeechRecognitionManager
import com.arnold.voicetranslator.audio.TtsManager
import com.arnold.voicetranslator.data.model.Language
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult
import com.arnold.voicetranslator.data.offline.MlKitOfflineException
import com.arnold.voicetranslator.data.offline.MlKitOfflineTranslator
import com.arnold.voicetranslator.data.offline.ModelNotDownloadedException
import com.arnold.voicetranslator.data.remote.WorkerApiClient
import com.arnold.voicetranslator.data.remote.WorkerApiException
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localizedNamePlain
import com.arnold.voicetranslator.ui.localization.localizedString
import com.arnold.voicetranslator.ui.localization.toUiLanguage
import com.arnold.voicetranslator.ui.state.PipelineStatus
import com.arnold.voicetranslator.ui.state.LiveChatEntry
import com.arnold.voicetranslator.ui.state.LiveTurn
import com.arnold.voicetranslator.ui.state.ModelDownloadStatus
import com.arnold.voicetranslator.ui.state.OfflineModelInfo
import com.arnold.voicetranslator.ui.state.TranslationHistoryItem
import com.arnold.voicetranslator.ui.state.TranslatorUiState
import com.arnold.voicetranslator.util.JapaneseRomajiConverter
import com.arnold.voicetranslator.util.SpanishQuestionCorrector
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
 *    our Cloudflare Worker proxy — no API key lives in this app),
 *  - [MlKitOfflineTranslator] (offline fallback),
 * and exposes every meaningful flag as a single immutable [StateFlow].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Every online translation path (Spanish->target, Korean phonetic, and
    // the live-conversation reply-suggestion flow) now goes through our
    // Cloudflare Worker proxy. No API key of any kind lives in this app
    // anymore — the Worker holds the Google/DeepSeek secrets server-side.
    private val workerApi = WorkerApiClient()
    private val offlineTranslator = MlKitOfflineTranslator()

    private val speechManager = SpeechRecognitionManager(application.applicationContext)
    private val ttsManager = TtsManager(application.applicationContext)

    // ---- Language persistence -------------------------------------------
    // Remembers the last chosen source/target languages across app restarts
    // (SharedPreferences, same lightweight pattern used by the Fraseario
    // module's favorites) so the app doesn't reset to the default ES->JA
    // pair every time the user reopens it after picking, say, ES->KO.
    private val languagePrefs = application.applicationContext
        .getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadSavedTargetLanguage(): TargetLanguage {
        val savedId = languagePrefs.getString(KEY_TARGET_LANG, TargetLanguage.JAPANESE.id)
        return TargetLanguage.entries.find { it.id == savedId } ?: TargetLanguage.JAPANESE
    }

    /** Origen/Destino selector persistence (Traductor's 2-dropdown pair). */
    private fun loadSavedSourceLanguage(): Language =
        Language.fromId(languagePrefs.getString(KEY_ORIGEN_LANG, null)) ?: Language.DEFAULT_SOURCE

    private fun loadSavedDestinationLanguage(): Language =
        Language.fromId(languagePrefs.getString(KEY_DESTINO_LANG, null)) ?: Language.DEFAULT_DESTINATION

    private fun persistTargetLanguage(target: TargetLanguage) {
        languagePrefs.edit()
            .putString(KEY_SOURCE_LANG, DEFAULT_SOURCE_LANG)
            .putString(KEY_TARGET_LANG, target.id)
            .apply()
    }

    private fun persistLanguagePair(source: Language, destination: Language) {
        languagePrefs.edit()
            .putString(KEY_ORIGEN_LANG, source.id)
            .putString(KEY_DESTINO_LANG, destination.id)
            .apply()
    }

    private val _uiState = MutableStateFlow(
        TranslatorUiState(
            targetLanguage = loadSavedTargetLanguage(),
            sourceLanguage = loadSavedSourceLanguage(),
            destinationLanguage = loadSavedDestinationLanguage(),
        ),
    )
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null
    private var lastSpeechText: String? = null

    /**
     * App-wide UI language, derived from the current Origen selector (see
     * [TranslatorUiState.uiLanguage] — the actual source of truth read by
     * the Compose layer). Exposed here too so this ViewModel's own
     * `errorMessage` strings (set outside any @Composable scope) can be
     * localized the same way, via [localizedText].
     */
    private val uiLanguage: UiLanguage
        get() = _uiState.value.sourceLanguage.toUiLanguage()

    /** Resolves a string resource in the current [uiLanguage], for use in
     *  `errorMessage`/similar plain-String ViewModel state (no Compose
     *  scope available here — see [localizedString]). */
    private fun localizedText(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        localizedString(getApplication(), uiLanguage, id, *args)

    // Counts consecutive ERROR_LANGUAGE_UNAVAILABLE/ERROR_CLIENT/ERROR_LANGUAGE_NOT_SUPPORTED
    // failures in Live mode's continuous foreign-listening loop. When a
    // language's offline voice pack genuinely isn't available on this device
    // (confirmed real-device case: OnePlus only offers Chino/Coreano offline,
    // not Español/Japonés), every restart attempt fails again instantly —
    // capped so we don't spin forever draining battery, while still not
    // requiring the user to press "Reanudar" for the first several retries.
    private var offlineVoiceRetryCount = 0

    // ---- Silent auto-retry (no speech detected in 3s) ----------------------
    // If the recognizer is listening but hasn't reported so much as a partial
    // result within AUTO_RETRY_SILENCE_MILLIS, silently restart it once
    // instead of leaving the user to notice and tap the mic again — helps
    // with the same "primera palabra cortada" flakiness where the engine
    // occasionally fails to pick up anything for the first couple seconds.
    private var autoRetryJob: Job? = null
    private var didAutoRetry = false

    private fun scheduleAutoRetry() {
        autoRetryJob?.cancel()
        didAutoRetry = false
        autoRetryJob = viewModelScope.launch {
            delay(AUTO_RETRY_SILENCE_MILLIS)
            val current = _uiState.value
            if (current.isListening && current.partialTranscript.isBlank() && !didAutoRetry) {
                didAutoRetry = true
                Log.d(TAG, "auto-retry: sin audio detectado en ${AUTO_RETRY_SILENCE_MILLIS}ms, reiniciando escucha")
                speechManager.start()
            }
        }
    }

    private fun cancelAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = null
    }

    init {
        // Restore the persisted target language's TTS locale immediately so
        // the very first speak-out after a fresh app launch already uses the
        // remembered language, not the class-default (Japanese).
        ttsManager.setLocale(localeFor(_uiState.value.targetLanguage))
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
            // Any live audio being heard cancels the "no detectó nada en 3s"
            // auto-retry below — the mic is clearly picking something up.
            cancelAutoRetry()
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    // Surface a live transcript while in a live conversation so
                    // the user can see the foreign speaker (or their own) words
                    // as they are being heard.
                    liveTranscript = if (it.isLiveConversation) partial else "",
                    // Standard (non-live) mic flow: always kept up to date so
                    // the "Escuchando: ..." hint can show it regardless of mode.
                    partialTranscript = partial,
                )
            }
        }

        speechManager.onResult = { text ->
            lastSpeechText = null
            offlineVoiceRetryCount = 0
            cancelAutoRetry()
            Log.d(TAG, "speech onResult: \"$text\"")
            _uiState.update {
                it.copy(
                    isListening = false,
                    liveTranscript = "",
                    partialTranscript = "",
                )
            }
            if (text.isBlank()) {
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        errorMessage = localizedText(R.string.no_text_captured),
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
            cancelAutoRetry()
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isListening = false,
                    errorMessage = message,
                    liveTranscript = "",
                    partialTranscript = "",
                )
            }
            // In live mode, transient silence (no speech / speech timeout) while
            // waiting for the foreign speaker should keep the mic open so the
            // conversation stays continuous. Restart listening on the main thread.
            val isSilence = message.contains("No se detectó habla") ||
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
            cancelAutoRetry()
            _uiState.update { it.copy(isListening = false) }
        }

        // "modo avión + modo sin conexión": the recognizer couldn't find an
        // installed offline voice pack for the requested language and there's
        // no network to fall back to. Show precise, actionable guidance
        // (Ajustes > Voz offline) instead of a generic error, which the user
        // reasonably reads as an app bug.
        speechManager.onOfflineVoiceUnavailable = {
            cancelAutoRetry()
            _uiState.update {
                it.copy(
                    status = PipelineStatus.Idle,
                    isListening = false,
                    liveTranscript = "",
                    partialTranscript = "",
                    // Some devices/idiomas simplemente no ofrecen paquete de
                    // voz offline para descargar (p. ej. solo Chino/Coreano
                    // en ciertos OnePlus) — en ese caso ningún ajuste lo
                    // arregla; hay que usar texto o desactivar modo avión.
                    voiceOfflineGuidance = localizedText(R.string.voice_offline_guidance),
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

    /**
     * Selects the Origen language for the standard (non-live) Traductor flow
     * — see [TranslatorUiState.sourceLanguage]. Independent from
     * [TargetLanguage]/[onSelectLanguage], which still only drives the
     * legacy offline-model / live-conversation / "Escuchar idioma" paths.
     */
    fun onSelectSourceLanguage(language: Language) {
        val state = _uiState.value
        if (language == state.sourceLanguage) return
        _uiState.update { it.copy(sourceLanguage = language) }
        persistLanguagePair(language, state.destinationLanguage)
    }

    /**
     * Selects the Destino language for the standard (non-live) Traductor
     * flow. Also keeps the legacy [TargetLanguage] (offline models, TTS
     * default for "Escuchar idioma", live conversation) roughly in sync
     * whenever the new [Language] maps 1:1 onto one of its 4 entries
     * (ja/ko/zh/en) — Español has no [TargetLanguage] equivalent, so that
     * mapping is simply skipped in that case and the legacy target stays
     * whatever it was before.
     */
    fun onSelectDestinationLanguage(language: Language) {
        val state = _uiState.value
        if (language == state.destinationLanguage) return
        _uiState.update { it.copy(destinationLanguage = language) }
        persistLanguagePair(state.sourceLanguage, language)
        TargetLanguage.entries.find { it.id == language.id }?.let { applyTargetLanguage(it) }
    }

    /** Swaps Origen <-> Destino (the swap button between the 2 dropdowns). */
    fun onSwapLanguages() {
        val state = _uiState.value
        val newSource = state.destinationLanguage
        val newDestination = state.sourceLanguage
        _uiState.update { it.copy(sourceLanguage = newSource, destinationLanguage = newDestination) }
        persistLanguagePair(newSource, newDestination)
        TargetLanguage.entries.find { it.id == newDestination.id }?.let { applyTargetLanguage(it) }
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.mic_permission_required)) }
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.mic_permission_required)) }
            return
        }
        // Fix (#6, Origen/Destino selector): Origen == Destino can't be
        // translated — disabled here (not just visually) so a stray tap
        // never fires a same-language "translation".
        if (!state.canTranslate) {
            _uiState.update { it.copy(errorMessage = localizedText(R.string.choose_different_languages)) }
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.nothing_to_translate)) }
            return
        }
        if (!_uiState.value.canTranslate) {
            _uiState.update { it.copy(errorMessage = localizedText(R.string.choose_different_languages)) }
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.nothing_to_translate)) }
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.mic_permission_required)) }
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
                    errorMessage = if (success) {
                        state.errorMessage
                    } else {
                        localizedText(
                            R.string.could_not_download_model_for,
                            target.localizedNamePlain(getApplication(), uiLanguage),
                        )
                    },
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.mic_permission_required)) }
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
     * Elects a suggested Romaji reply as the user's (TÚ) answer: speaks it out
     * loud in the foreign language, records it in the chat, and — once the TTS
     * finishes — automatically returns to listen for the foreign speaker (ELLOS).
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
     * Listens to the user speaking in Spanish (TÚ), translates it to the target
     * language, and speaks it. When the TTS finishes, [ttsManager.onSpeakFinished]
     * re-listens for the foreign speaker.
     */
    fun onLiveSpeakSpanish() {
        val state = _uiState.value
        if (!state.hasMicPermission) {
            _uiState.update { it.copy(errorMessage = localizedText(R.string.mic_permission_required)) }
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
        persistTargetLanguage(target)
    }

    private fun startSpeakingSpanish() {
        // Fix (Origen/Destino selector): used to always force es-MX here.
        // Now listens in whatever Origen language is selected (source.speechTag
        // per Language.kt), so "Hablar en Español" really means "Hablar en
        // <Origen>" once the user picks EN/JA/etc. as Origen.
        speechManager.speechLanguage = _uiState.value.sourceLanguage.speechTag
        beginListening(isForeignSpeech = false)
    }

    private fun startListeningForeignLanguage() {
        val target = _uiState.value.targetLanguage
        when (target) {
            TargetLanguage.JAPANESE -> speechManager.listenInJapanese()
            TargetLanguage.KOREAN -> speechManager.listenInKorean()
            TargetLanguage.ENGLISH -> speechManager.listenInEnglish()
            // No dedicated Chinese speech-recognition path yet in the main
            // Traductor (Fraseario-only for now) — no-op fallback so the
            // enum stays exhaustive without changing existing behavior.
            TargetLanguage.CHINESE -> Unit
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
                partialTranscript = "",
            )
        }
        ttsManager.stop()
        // Kept in sync right before every start(): without this, the system
        // recognizer defaults to online recognition even with a downloaded
        // offline language pack, so "Modo sin conexión" + airplane mode would
        // still fail for a language whose pack IS installed (e.g. Coreano).
        speechManager.preferOfflineRecognition = _uiState.value.isOfflineMode
        speechManager.start()
        scheduleAutoRetry()
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
        // The user's own Spanish speech (never the foreign speaker's) goes
        // through a question corrector first: STT frequently drops accents
        // and question marks ("como estas" instead of "¿Cómo estás?"), which
        // then translates literally/wrong. Reconstruct it as a proper
        // question before it ever reaches the translator.
        val correctedText = if (!isForeignSpeech) {
            SpanishQuestionCorrector.correct(text)
        } else {
            text
        }
        if (correctedText != text) {
            Log.d(TAG, "translate: Spanish question corrector: \"$text\" -> \"$correctedText\"")
        }
        val text = correctedText
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
                        // Origen -> Destino (Origen/Destino selector): no
                        // longer hardcodes Spanish as the source — uses
                        // whatever Language pair the user picked (ES<->EN,
                        // EN<->JA, JA<->ES, etc.), routed through the
                        // Worker's /translate with a real `source`.
                        onlineTranslateDynamic(
                            text,
                            _uiState.value.sourceLanguage,
                            _uiState.value.destinationLanguage,
                        )
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
                        errorMessage = e.message ?: localizedText(R.string.offline_translation_error),
                    )
                }
            } catch (e: WorkerApiException) {
                Log.e(TAG, "translate WorkerApiException", e)
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = e.message ?: localizedText(R.string.translation_error),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastSpeechText = null
                _uiState.update {
                    it.copy(
                        status = PipelineStatus.Idle,
                        isTranslating = false,
                        errorMessage = localizedText(R.string.unexpected_error),
                    )
                }
            }
        }
    }

    /**
     * Simple Spanish -> target translation for the standard (non-live) flow.
     * Korean goes through the Worker's /translate-ko-phonetic (DeepSeek under
     * the hood — needs the Spanish-phonetic transliteration prompt, not raw
     * Hangul). Japanese/English go through /translate (Google Translate);
     * Japanese output is additionally romanized client-side via
     * [JapaneseRomajiConverter] (Kuromoji) since Google returns kana/kanji,
     * not romaji. Unlike the old [KanaRomaji]-only fallback, this fully
     * romanizes kanji too (not just kana) via morphological analysis.
     */
    private suspend fun onlineTranslate(text: String, target: TargetLanguage): TranslationResult =
        when (target) {
            TargetLanguage.KOREAN -> {
                val phonetic = workerApi.translateKoreanPhonetic(text)
                // /translate-ko-phonetic only returns a Spanish-readable
                // transliteration (e.g. "annyeonghaseyo"), never the actual
                // Hangul. Fetch that separately via the plain /translate
                // route (Google Translate, target=ko) so a native Korean
                // speaker can also read the real script — same UX as
                // Japanese's kana/kanji companion. Only bothered when the
                // "Mostrar coreano (hangul)" toggle is on, to avoid burning
                // an extra call (and daily quota) against the Worker when
                // the user doesn't want it. Best-effort: a failure here
                // (network hiccup, rate limit) must never break the primary
                // phonetic translation the user is waiting for.
                val hangul = if (_uiState.value.isShowKana) {
                    runCatching { workerApi.translate(text, target.id) }
                        .onFailure { Log.w(TAG, "onlineTranslate: Korean Hangul fetch failed", it) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                phonetic.copy(nativeScript = hangul)
            }
            TargetLanguage.JAPANESE, TargetLanguage.ENGLISH, TargetLanguage.CHINESE -> {
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
     * Origen -> Destino translation for the standard (non-live) flow, using
     * the Origen/Destino [Language] pair instead of always assuming the
     * source is Spanish. Mirrors [onlineTranslate]'s Japanese-romaji
     * handling (Kuromoji, via [JapaneseRomajiConverter]) when the Destino is
     * Japanese, whichever the Origen is (ES->JA, EN->JA, etc.).
     *
     * Korean's "fonético" DeepSeek path only applies to the original
     * ES->KO direction (kept via [onlineTranslate]/[TargetLanguage] for the
     * "Escuchar idioma"/live-conversation flows) — every other Destino,
     * from any Origen, goes through the plain Google-backed /translate.
     */
    private suspend fun onlineTranslateDynamic(
        text: String,
        source: Language,
        destination: Language,
    ): TranslationResult {
        val translated = workerApi.translate(text, target = destination.id, source = source.id)
        val mainTranslation = if (destination == Language.JAPANESE) {
            val romaji = JapaneseRomajiConverter.kanjiToRomaji(translated)
            Log.d(
                TAG,
                "JA_ROMAJI_DEBUG onlineTranslateDynamic: input=\"$text\" source=${source.id} " +
                    "googleRaw=\"$translated\" romaji=\"$romaji\"",
            )
            romaji
        } else {
            translated
        }
        return TranslationResult(
            mainTranslation = mainTranslation.ifBlank { text },
            nativeScript = if (destination == Language.JAPANESE) translated else null,
        )
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
        // Fix: meaning/translation used to always come back in Spanish
        // regardless of the app's UI language (Origen selector) — now the
        // Worker is told which language to produce it in, so "Listen
        // Japanese"/Live mode show the meaning in English when Origen =
        // Inglés instead of staying hardcoded to Spanish.
        val result = workerApi.converse(text, foreignLang.id, meaningLang = uiLanguage.localeTag)
        if (foreignLang != TargetLanguage.JAPANESE) return result
        // Defensive guard: DeepSeek's /converse occasionally messes up a
        // suggestion's "romaji" field two different ways instead of an actual
        // Latin-script transliteration: (a) leaking raw kana/kanji into it
        // ("se bugea" — kana shows where romaji should be), or (b) just
        // duplicating the Spanish meaning into it (the exact bug reported:
        // suggestions show Spanish twice — no romaji reading — when spoken to
        // in Japanese). Detect both cases and re-romanize from the "kana"
        // field (which per the prompt always holds the real native script)
        // via Kuromoji, so the romaji slot is always guaranteed to hold an
        // actual Latin-script Japanese reading.
        return result.copy(
            replySuggestions = result.replySuggestions.map { suggestion ->
                val fixedRomaji = sanitizeJapaneseRomaji(suggestion)
                if (fixedRomaji != suggestion.romaji) {
                    Log.d(
                        TAG,
                        "JA_ROMAJI_DEBUG conversationTranslate: fixed bad suggestion romaji: " +
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
     * Ensures a reply suggestion's "romaji" field is actually a Latin-script
     * Japanese reading, not kana/kanji leaked in, and not just the Spanish
     * meaning duplicated into it. If either bad case is detected, re-derives
     * the romaji from the "kana" field (the real native-script reply) via
     * [JapaneseRomajiConverter]; otherwise returns the romaji unchanged.
     */
    private fun sanitizeJapaneseRomaji(suggestion: com.arnold.voicetranslator.data.model.ReplySuggestion): String {
        val romaji = suggestion.romaji
        val looksLikeLeakedKana = containsJapaneseScript(romaji)
        // True romaji (Hepburn) is plain ASCII: no ñ/á/é/í/ó/ú/¿/¡. If the
        // "romaji" field contains any of those, the model put real Spanish
        // there instead of a phonetic reading — the exact bug reported
        // ("siguen saliendo ambas opciones en español"). Checking for these
        // markers (rather than only an exact string match against
        // `spanish`) also catches cases where the model paraphrased instead
        // of copying verbatim.
        val looksLikeSpanish = romaji.isNotBlank() && (
            SPANISH_ONLY_MARKERS.containsMatchIn(romaji) ||
                (suggestion.spanish.isNotBlank() && romaji.trim().equals(suggestion.spanish.trim(), ignoreCase = true))
            )
        if (!looksLikeLeakedKana && !looksLikeSpanish) return romaji

        return when {
            containsJapaneseScript(suggestion.kana) -> JapaneseRomajiConverter.kanjiToRomaji(suggestion.kana)
            looksLikeLeakedKana -> JapaneseRomajiConverter.kanjiToRomaji(romaji)
            // Nothing native-script to romanize from — blank it out instead
            // of showing the wrong Spanish text a second time; the UI falls
            // back to showing just the Spanish meaning in that case.
            else -> ""
        }
    }

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
                        // TÚ turn translated into Japanese or Korean: also
                        // carry the native script (kana/kanji or Hangul) so
                        // it can be shown next to the Romaji/phonetic reading
                        // for a native speaker to read.
                        translationKana = if (!isForeignSpeech &&
                            (target == TargetLanguage.JAPANESE || target == TargetLanguage.KOREAN)
                        ) {
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
            // Origen/Destino selector: speak in whatever Destino the user
            // picked (target.ttsTag per Language.kt) instead of always the
            // legacy 4-language TargetLanguage.
            state.destinationLanguage.ttsLocale
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
            _uiState.update { it.copy(errorMessage = localizedText(R.string.model_already_downloaded)) }
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
                errorMessage = if (success) null else localizedText(R.string.could_not_download_model),
            )
        }
    }

    private fun stopEverything() {
        translateJob?.cancel()
        cancelAutoRetry()
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
                partialTranscript = "",
            )
        }
    }

    private fun localeFor(target: TargetLanguage): Locale = when (target) {
        TargetLanguage.JAPANESE -> Locale.JAPAN
        TargetLanguage.KOREAN -> Locale.KOREA
        TargetLanguage.CHINESE -> Locale.CHINESE
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
        /** Silent auto-retry window: restart listening once if nothing at all
         *  (not even a partial result) was heard within this many ms. */
        const val AUTO_RETRY_SILENCE_MILLIS = 3_000L
        /** Characters that only appear in Spanish, never in Hepburn romaji. */
        val SPANISH_ONLY_MARKERS = Regex("[áéíóúñÁÉÍÓÚÑ¿¡]")

        /** SharedPreferences bucket persisting the chosen source/target languages. */
        const val LANGUAGE_PREFS_NAME = "translator_language_prefs"
        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TARGET_LANG = "target_lang"
        /** Legacy: source used to always be Spanish (the app's only input
         *  language) before the Origen/Destino selector below existed. */
        const val DEFAULT_SOURCE_LANG = "es"
        /** Origen/Destino selector persistence keys (see [Language]). */
        const val KEY_ORIGEN_LANG = "origen_lang_id"
        const val KEY_DESTINO_LANG = "destino_lang_id"
    }
}
