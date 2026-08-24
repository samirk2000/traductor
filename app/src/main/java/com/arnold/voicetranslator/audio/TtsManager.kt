package com.arnold.voicetranslator.audio

import android.content.Context
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Wraps [android.speech.tts.TextToSpeech] to read translated text aloud in the
 * target language (Japanese or Korean). Reports readiness via [onReady]. If the
 * UI asks to speak before the engine is initialized, the utterance is deferred
 * and played back automatically the moment the engine becomes ready.
 */
class TtsManager(context: Context) {

    /** True once the TTS engine has completed initialization. */
    var isAvailable: Boolean = false
        private set

    /** Fired when an utterance fully finishes so the UI drops "Reproduciendo". */
    var onSpeakFinished: () -> Unit = {}

    /** Fired once the engine reports initialization success. */
    var onReady: () -> Unit = {}

    private val appContext = context.applicationContext
    private var tts: android.speech.tts.TextToSpeech? = null
    private var pendingLocale: Locale? = null
    private var pendingSpeak: (() -> Unit)? = null
    private val utteranceId = UUID.randomUUID().toString()

    init {
        tts = android.speech.tts.TextToSpeech(appContext) { status ->
            isAvailable = status == android.speech.tts.TextToSpeech.SUCCESS
            if (isAvailable) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        onSpeakFinished()
                    }

                    @Deprecated("Replaced by onError(String,int) in API 21+")
                    override fun onError(utteranceId: String?) {
                        onSpeakFinished()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onSpeakFinished()
                    }
                })
                pendingLocale?.let { applyLocale(it) }
                pendingSpeak?.let { it(); pendingSpeak = null }
                onReady()
            } else {
                Log.w(TAG, "TextToSpeech initialization failed (status=$status)")
            }
        }
    }

    /**
     * Configures the language for synthesis. If the engine isn't ready yet, the
     * locale is applied as soon as initialization completes.
     */
    fun setLocale(targetLocale: Locale) {
        pendingLocale = targetLocale
        if (isAvailable) {
            applyLocale(targetLocale)
        }
    }

    /**
     * Speaks [text], clearing any in-flight utterance first. If the engine is
     * still initializing, the speak is deferred until it becomes ready so the
     * translation is always played back.
     */
    fun speak(text: String) {
        fun doSpeak() {
            val engine = tts
            if (text.isBlank()) {
                onSpeakFinished()
                return
            }
            engine?.stop()
            val result = engine?.speak(
                text,
                android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            ) ?: android.speech.tts.TextToSpeech.ERROR
            if (result == android.speech.tts.TextToSpeech.ERROR) {
                onSpeakFinished()
            }
        }

        if (isAvailable) {
            doSpeak()
        } else {
            pendingSpeak = ::doSpeak
        }
    }

    /** Stops any in-flight utterance. */
    fun stop() {
        runCatching { tts?.stop() }
    }

    /** Releases native TTS resources. Call from onDestroy. */
    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        isAvailable = false
        pendingSpeak = null
    }

    private fun applyLocale(locale: Locale) {
        tts?.setLanguage(locale)
    }

    private companion object {
        const val TAG = "TtsManager"
    }
}
