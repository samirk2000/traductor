package com.arnold.voicetranslator.ui.state

import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult

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
 * Human-readable, Spanish label shown in the top status indicator.
 */
val PipelineStatus.statusText: String
    get() = when (this) {
        PipelineStatus.Idle -> "Listo"
        PipelineStatus.Listening -> "Escuchando..."
        PipelineStatus.Translating -> "Traduciendo..."
        PipelineStatus.Speaking -> "Reproduciendo"
    }

/**
 * Mode-selector for the microphone switch.
 */
enum class MicAction {
    Start,
    Stop,
}

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
    /** Stable identity for Compose list keys (data classes aren't valid keys). */
    val id: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Immutable, once-per-render UI state consumed by the Compose layer.
 * Exposed as a single [kotlinx.coroutines.flow.StateFlow] from the ViewModel.
 */
data class TranslatorUiState(
    val targetLanguage: TargetLanguage = TargetLanguage.JAPANESE,
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
    /** The rolling chat feed of the live conversation, oldest first. */
    val liveMessages: List<LiveChatEntry> = emptyList(),
    /** When true, the continuous foreign listening in live mode is paused. */
    val isLiveListeningPaused: Boolean = false,
) {
    val statusText: String
        get() = status.statusText

    val micAction: MicAction
        get() = if (isListening) MicAction.Stop else MicAction.Start
}
