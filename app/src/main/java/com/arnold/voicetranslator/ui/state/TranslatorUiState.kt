package com.arnold.voicetranslator.ui.state

import androidx.compose.runtime.Composable
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.data.model.Language
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import com.arnold.voicetranslator.ui.localization.toUiLanguage

/**
 * The high-level operational state of the app's audio/translation pipeline,
 * mirrored to the top status indicator in the UI.
 */
enum class PipelineStatus {
    /** Waiting for the user to press the mic button. */
    Idle,

    /** Microphone is live and the recognizer is transcribing Spanish speech. */
    Listening,

    /** Speech captured; translation request is in-flight (API or offline). */
    Translating,

    /** Translation received and TTS is speaking it out loud. */
    Speaking;
}

/**
 * Human-readable label shown in the top status indicator, localized to
 * [uiLanguage] (app-wide UI language — see [TranslatorUiState.uiLanguage]),
 * not just a fixed Spanish string anymore.
 */
@Composable
fun PipelineStatus.localizedText(uiLanguage: UiLanguage): String = when (this) {
    PipelineStatus.Idle -> localized(uiLanguage, R.string.status_idle)
    PipelineStatus.Listening -> localized(uiLanguage, R.string.status_listening)
    PipelineStatus.Translating -> localized(uiLanguage, R.string.status_translating)
    PipelineStatus.Speaking -> localized(uiLanguage, R.string.status_speaking)
}

/**
 * Mode-selector for the microphone switch.
 */
enum class MicAction {
    Start,
    Stop,
}

/** Download state of a single language's offline (ML Kit) model. */
enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
}

/** Per-language offline model status, shown in the "Modelos Offline" settings section. */
data class OfflineModelInfo(
    val target: TargetLanguage,
    val status: ModelDownloadStatus = ModelDownloadStatus.NOT_DOWNLOADED,
    val progress: Float = 0f,
)

/**
 * A single completed exchange, kept so the user can scroll back through the
 * conversation (original speech, romaji reading, and resulting translation).
 */
data class TranslationHistoryItem(
    val sourceText: String,
    val sourceRomaji: String?,
    val translation: String,
    val isJapaneseInput: Boolean,
)

/** Which side is currently active in a live conversation. */
enum class LiveTurn {
    /** "TÚ": the user speaks Spanish. */
    YOU,
    /** "ELLOS": the foreign speaker talks in the active target language. */
    THEM,
}

/**
 * A single chat bubble in the live conversation. [text] is the read/typed text
 * (Spanish for [LiveTurn.YOU], the foreign language for [LiveTurn.THEM]) and
 * [translation] is the rendered translation of that turn.
 */
data class LiveChatEntry(
    val turn: LiveTurn,
    val text: String,
    val translation: String,
    val sourceRomaji: String? = null,
    /**
     * For [LiveTurn.YOU] entries translated into Japanese: the native
     * kana/kanji script for [translation] (which itself holds the Romaji),
     * so a native speaker can also read the original script. Null for
     * non-Japanese targets or when unavailable.
     */
    val translationKana: String? = null,
    /**
     * Native kana/kanji companion for [text] instead of [translation] — used
     * by the tapped-suggestion reply bubble, where [text] (not [translation])
     * holds the Japanese Romaji being sent.
     */
    val textKana: String? = null,
    /** Stable identity for Compose list keys (data classes aren't valid keys). */
    val id: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Immutable, once-per-render UI state consumed by the Compose layer.
 * Exposed as a single [kotlinx.coroutines.flow.StateFlow] from the ViewModel.
 */
data class TranslatorUiState(
    val targetLanguage: TargetLanguage = TargetLanguage.JAPANESE,
    /**
     * Origen/Destino selector (Traductor): the standard "Hablar/Escribir en
     * Origen" one-directional flow now translates [sourceLanguage] ->
     * [destinationLanguage] instead of always assuming Spanish -> [targetLanguage].
     * [targetLanguage] above is kept as-is (and kept roughly in sync with
     * [destinationLanguage] — see MainViewModel.onSelectDestinationLanguage)
     * purely for the untouched offline-model / live-conversation / "Escuchar
     * idioma" code paths, which still key off the 4-language [TargetLanguage].
     */
    val sourceLanguage: Language = Language.DEFAULT_SOURCE,
    val destinationLanguage: Language = Language.DEFAULT_DESTINATION,
    val status: PipelineStatus = PipelineStatus.Idle,
    val isOfflineMode: Boolean = false,
    val isModelDownloaded: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val downloadProgress: Float = 0f,
    val isListening: Boolean = false,
    val isTranslating: Boolean = false,
    val isSpeaking: Boolean = false,
    /** True when the active speech mode listens to a Japanese speaker. */
    val isListeningToJapanese: Boolean = false,
    /** True when the active "listen" mode listens to a foreign language
     *  (Japanese OR Korean, per [targetLanguage]). */
    val isListeningForeign: Boolean = false,
    /** Original text(s) that produced [result]; used to echo kana + romaji. */
    val sourceText: String? = null,
    /** Romaji transcription of [sourceText] when it was Japanese kana. */
    val sourceRomaji: String? = null,
    val result: TranslationResult? = null,
    /** Rolling feed of completed translations, newest last. */
    val historyList: List<TranslationHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val hasMicPermission: Boolean = false,
    val isTtsAvailable: Boolean = false,
    /** True when the "Anime Subtitles" fullscreen overlay is shown. */
    val isSubtitlesMode: Boolean = false,
    /** When true, a finished translation is read aloud automatically (TTS). */
    val isAutoSpeakEnabled: Boolean = true,
    /** When true, reply suggestions also show the Japanese kana/kanji text. */
    val isShowKana: Boolean = false,
    /** When true, the live (continuous) two-way conversation mode is active. */
    val isLiveConversation: Boolean = false,
    /** Which side is currently speaking/listening; null when not in a turn. */
    val liveTurn: LiveTurn? = null,
    /** Live (partial) transcript being heard during the current turn. */
    val liveTranscript: String = "",
    /**
     * Live (partial) transcript for the standard (non-live) "Hablar en
     * Origen" / "Escuchar Idioma" mic flow — shown as a gray "Escuchando:
     * ..." hint below the mic buttons so the user can see what's being
     * heard while they're still talking, mirroring [liveTranscript] but not
     * gated behind [isLiveConversation].
     */
    val partialTranscript: String = "",
    /** The rolling chat feed of the live conversation, oldest first. */
    val liveMessages: List<LiveChatEntry> = emptyList(),
    /** When true, the continuous foreign listening in live mode is paused. */
    val isLiveListeningPaused: Boolean = false,
    /** Per-language offline (ML Kit) model status for the Settings screen. */
    val offlineModels: Map<TargetLanguage, OfflineModelInfo> = TargetLanguage.entries.associateWith {
        OfflineModelInfo(target = it)
    },
    /**
     * Non-null when a translation was attempted offline but the model for
     * this language isn't downloaded yet — the UI shows a dialog asking to
     * download it, naming the language explicitly instead of a generic
     * "unrecognized language" error.
     */
    val missingOfflineModelPrompt: TargetLanguage? = null,
    /**
     * Guidance shown (as a Snackbar with an "Abrir Ajustes" action) when the
     * native Android SpeechRecognizer can't find an installed offline voice
     * pack while there's no network (e.g. airplane mode) — replaces the
     * confusing generic "idioma no reconocido" error in that specific case.
     */
    val voiceOfflineGuidance: String? = null,
) {
    /**
     * App-wide UI language (tabs, menus, buttons, hints — not just the
     * translated content) derived from the Origen selector: English Origen
     * -> English UI; everything else -> Spanish UI (the app's original,
     * default UI language).
     */
    val uiLanguage: UiLanguage
        get() = sourceLanguage.toUiLanguage()

    val micAction: MicAction
        get() = if (isListening) MicAction.Stop else MicAction.Start

    /** False when Origen == Destino — the "Elige idiomas diferentes" case
     *  where the mic/typing "traducir" actions must be disabled. */
    val canTranslate: Boolean
        get() = sourceLanguage != destinationLanguage
}
