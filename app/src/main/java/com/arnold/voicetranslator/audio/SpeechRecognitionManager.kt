package com.arnold.voicetranslator.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps the native [android.speech.SpeechRecognizer] to capture voice input.
 * The recognition language can be switched at runtime between Spanish
 * (`es-MX`) and Japanese (`ja-JP`) via [speechLanguage], enabling a two-way
 * conversation (hear Spanish by default, or listen to a Japanese speaker).
 *
 * Live (partial) results are surfaced via [onPartialResult] so the UI can show
 * what is being heard while the user speaks.
 */
class SpeechRecognitionManager(private val context: Context) {

    /** Locale tag the recognizer is listening in. Defaults to Mexican Spanish. */
    var speechLanguage: String = DEFAULT_SPEECH_LANGUAGE

    /** Called with live partial transcriptions while the user is speaking. */
    var onPartialResult: (String) -> Unit = {}

    /** Called with the final, most-confident transcription. */
    var onResult: (String) -> Unit = {}

    /** Called when recognition fails (no speech, error, etc). */
    var onError: (String) -> Unit = {}

    /** Notified when the recognizer has begun running (mic active). */
    var onReady: () -> Unit = {}

    /** Notified once the mic/listener has fully stopped. */
    var onEnd: () -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private val pendingResults: MutableList<String> = mutableListOf()
    private val handler = android.os.Handler(context.mainLooper)
    private var retriedRestart = false

    /** True while the recognizer is listening to a Japanese locale. */
    private val isJapaneseLanguage: Boolean
        get() = speechLanguage.equals(JAPANESE_SPEECH_LANGUAGE, ignoreCase = true)

    /** True while the recognizer is actively listening for speech. */
    val isListening: Boolean get() = recognizer != null

    /**
     * Starts listening in the currently configured [speechLanguage]. Safe to
     * call repeatedly; a live recognizer is silently replaced.
     */
    fun start() {
        Log.d(TAG, "start() -> language=$speechLanguage")
        resetRetryGuard()
        destroyRecognizer()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(createListener())
            sr.startListening(recognitionIntent())
        }
        onReady()
    }

    /** Configures the recognizer to listen for Mexican Spanish. */
    fun listenInSpanish() {
        speechLanguage = DEFAULT_SPEECH_LANGUAGE
    }

    /** Configures the recognizer to listen for Japanese. */
    fun listenInJapanese() {
        speechLanguage = JAPANESE_SPEECH_LANGUAGE
        // If the device lacks an offline Japanese pack, the service may fall
        // back to network recognition; otherwise onError(11) surfaces guidance.
    }

    /** Stops listening. The recognizer finalizes and delivers via onResult. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Full teardown of the recognizer + its callbacks. */
    fun cancel() {
        pendingResults.clear()
        destroyRecognizer()
        onEnd()
    }

    /**
     * Tears down and releases the underlying recognizer. Must be called from
     * onDestroy so no listeners leak.
     */
    fun release() {
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            Log.w(TAG, "SpeechRecognizer.destroy() failed")
        }
        recognizer = null
    }

    /**
     * Resets the one-shot retry guard whenever a fresh listening starts.
     */
    private fun resetRetryGuard() {
        retriedRestart = false
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech (microphone active)")
            onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            pendingResults.clear()
        }

        override fun onError(error: Int) {
            Log.e(TAG, "onError code=$error language=$speechLanguage")
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No se detectó habla"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de escucha agotado"
                SpeechRecognizer.ERROR_NETWORK -> "Error de red en el reconocimiento"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Permiso de micrófono requerido"
                SpeechRecognizer.ERROR_AUDIO -> "Error de captura de audio"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                SpeechRecognizer.ERROR_CLIENT -> "Cliente de reconocimiento no disponible"
                // Code 12: the requested language is not supported by the
                // recognizer. Show guidance mainly when listening to Japanese.
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                    if (isJapaneseLanguage) {
                        "El idioma Japonés no está instalado en el motor de voz de Android. " +
                            "Ve a Ajustes > Dictado por voz de Google para descargarlo."
                    } else {
                        "El idioma de reconocimiento no está disponible en este dispositivo."
                    }
                }
                // Code 11: server disconnected (e.g. because a fresh recognizer
                // was created right after the previous one stopped).
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                    "Error de reconocimiento: se reinició el micrófono, vuelve a intentarlo."
                else -> "Error de reconocimiento ($error)"
            }

            // Transient restart failure: retry once silently and only surface
            // the message if the retry fails too.
            if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !retriedRestart) {
                Log.d(TAG, "onError: retrying once")
                retriedRestart = true
                handler.postDelayed({ retriedRestart = false; start() }, RETRY_DELAY_MILLIS)
                return
            }

            Log.e(TAG, "onError message=$message")
            pendingResults.clear()
            onError(message)
            onEnd()
        }

        override fun onResults(result: Bundle?) {
            val matches = extractResults(result)
            Log.d(TAG, "onResults matches=${matches}")
            pendingResults.clear()
            onResult(matches.lastOrNull() ?: "")
            onEnd()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = extractResults(partialResults)
            if (matches.isNotEmpty()) {
                Log.d(TAG, "onPartialResults matches=${matches}")
            }
            pendingResults.clear()
            pendingResults.addAll(matches)
            matches.lastOrNull()?.let { onPartialResult(it) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun extractResults(bundle: Bundle?): List<String> =
            bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { !it.isNullOrBlank() }
                ?: emptyList()
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
            // Ask the service to honor our language preference so it can tell
            // us (error code 11) when the requested language is not installed.
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            // Surface live partials and keep listening through natural pauses.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                2500L,
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

    private companion object {
        const val TAG = "SpeechRecognition"
        const val DEFAULT_SPEECH_LANGUAGE = "es-MX"
        const val JAPANESE_SPEECH_LANGUAGE = "ja-JP"
        const val RETRY_DELAY_MILLIS = 600L
    }
}
