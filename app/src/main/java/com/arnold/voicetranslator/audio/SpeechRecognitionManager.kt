package com.arnold.voicetranslator.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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

    /**
     * When true, asks the recognizer to prefer on-device/offline recognition
     * (`RecognizerIntent.EXTRA_PREFER_OFFLINE`). Without this, the system
     * recognizer defaults to online/network recognition even when an offline
     * language pack is downloaded — which is exactly why, in testing,
     * "modo sin conexión" + a downloaded offline pack (e.g. Coreano) still
     * failed in airplane mode: the request was still trying to reach the
     * network. Kept in sync with the app's "Modo sin conexión" toggle.
     */
    var preferOfflineRecognition: Boolean = false

    /**
     * Called instead of [onError] specifically when the recognizer can't find
     * a supported/installed language (error 12) or a usable client (error 5)
     * while there's no network connection — i.e. the classic "modo avión +
     * modo sin conexión" case where the on-device offline voice pack for the
     * requested language was never downloaded from Android Settings. Lets the
     * UI show precise guidance ("ve a Ajustes > Voz offline...") with a
     * button that opens [android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS]
     * instead of a confusing generic "idioma no reconocido" message.
     */
    var onOfflineVoiceUnavailable: () -> Unit = {}

    /** Notified when the recognizer has begun running (mic active). */
    var onReady: () -> Unit = {}

    /** Notified once the mic/listener has fully stopped. */
    var onEnd: () -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private val pendingResults: MutableList<String> = mutableListOf()
    private val handler = android.os.Handler(context.mainLooper)
    private var retriedRestart = false

    // ---- Audio focus ---------------------------------------------------
    // Request exclusive transient focus while recording so other apps (music,
    // notifications) duck/pause instead of bleeding into the recognizer's
    // input, and so we don't keep recording over another app's playback.
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    /**
     * True when the device currently has a usable network connection
     * (mobile data or Wi-Fi with internet capability). False in airplane
     * mode or with no signal — used to tell a real "language not supported"
     * error apart from "the offline voice pack just isn't downloaded".
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // Fail open: don't misreport airplane mode if the service is unavailable.
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True while the recognizer is listening in a foreign (non-Spanish) locale. */
    private val isForeignLanguage: Boolean
        get() = speechLanguage.equals(JAPANESE_SPEECH_LANGUAGE, ignoreCase = true) ||
            speechLanguage.equals(KOREAN_SPEECH_LANGUAGE, ignoreCase = true) ||
            speechLanguage.equals(ENGLISH_SPEECH_LANGUAGE, ignoreCase = true)

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
        requestAudioFocus()

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(createListener())
        recognizer = sr
        // Warm-up delay: on several real devices (confirmed on OnePlus), the
        // mic/recognition engine isn't fully "hot" the instant
        // startListening() is invoked right after createSpeechRecognizer(),
        // which clips the very first word if the user starts talking
        // immediately (e.g. a fast "はいお願いします" with no leading
        // pause). A short delay lets the engine warm up before we actually
        // start capturing, fixing the "primera palabra cortada" bug.
        handler.postDelayed({
            // Guard against a stale callback firing after start()/cancel()
            // replaced or tore down this recognizer in the meantime.
            if (recognizer === sr) {
                Log.d("STT", "warmup")
                sr.startListening(recognitionIntent())
            }
        }, WARMUP_DELAY_MILLIS)
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

    /** Configures the recognizer to listen for Korean. */
    fun listenInKorean() {
        speechLanguage = KOREAN_SPEECH_LANGUAGE
    }

    /** Configures the recognizer to listen for English. */
    fun listenInEnglish() {
        speechLanguage = ENGLISH_SPEECH_LANGUAGE
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
        abandonAudioFocus()
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

            // "modo avión + modo sin conexión" case: the recognizer reports
            // the language as unsupported (12), the client as unusable (5),
            // or — the code actually seen on real devices in testing —
            // ERROR_LANGUAGE_UNAVAILABLE (13, API 31+): the language exists
            // but its offline pack isn't installed. All three happen because
            // there's no network to fall back to AND the offline voice pack
            // for this language was never downloaded from system Settings.
            // Surface a precise, actionable message instead of the generic
            // "idioma no reconocido" / "(13)", which reads like an app bug
            // rather than a missing system download.
            val isLanguageOrClientError = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == ERROR_LANGUAGE_UNAVAILABLE_CODE
            if (isLanguageOrClientError && !isNetworkAvailable()) {
                Log.e(TAG, "onError: offline + language/client error -> guiding to Voz offline settings")
                pendingResults.clear()
                onOfflineVoiceUnavailable()
                onEnd()
                return
            }

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
                    if (isForeignLanguage) {
                        "El idioma seleccionado no está instalado en el motor de voz de Android. " +
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
            // Set via both the typed constant and the raw extra key: some
            // OEM recognizer services (observed on OnePlus/ColorOS) only
            // honor the literal string key even though it's the exact same
            // value as RecognizerIntent.EXTRA_PARTIAL_RESULTS.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.PARTIAL_RESULTS", true)
            // Give the recognizer more room before it decides speech is
            // "possibly" or "definitely" finished, so a slightly slow start
            // (see warm-up delay above) or a fast multi-word phrase doesn't
            // get its first/last word chopped off.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MILLIS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MILLIS,
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // First-word accuracy fix: the on-device offline model tends to
            // clip/mishear the very first word of a fast utterance (e.g. a
            // quick Japanese "はいお願いします" with no pause) more than the
            // online model does. Only force online recognition when there's
            // actually a network to use it — with no network (airplane
            // mode) we must still honor preferOfflineRecognition, or the
            // "modo sin conexión" + offline-voice-pack fallback above would
            // break again.
            val forceOnlineForAccuracy = isNetworkAvailable() && (
                speechLanguage.equals(JAPANESE_SPEECH_LANGUAGE, ignoreCase = true) ||
                    speechLanguage.equals(KOREAN_SPEECH_LANGUAGE, ignoreCase = true)
                )
            if (preferOfflineRecognition && !forceOnlineForAccuracy) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private companion object {
        const val TAG = "SpeechRecognition"
        const val DEFAULT_SPEECH_LANGUAGE = "es-MX"
        const val JAPANESE_SPEECH_LANGUAGE = "ja-JP"
        const val KOREAN_SPEECH_LANGUAGE = "ko-KR"
        const val ENGLISH_SPEECH_LANGUAGE = "en-US"
        const val RETRY_DELAY_MILLIS = 600L
        /** Delay before startListening() to let the mic/engine warm up (see [start]). */
        const val WARMUP_DELAY_MILLIS = 300L
        /** EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS value. */
        const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 1500L
        /** EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS value. */
        const val COMPLETE_SILENCE_MILLIS = 2000L
        // SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE — added in API 31, kept
        // as a literal so this branch also compiles/works correctly if the
        // constant isn't resolvable on some toolchains, and to document
        // exactly which real-device error code this handles (confirmed via
        // on-device testing: OnePlus 15, airplane mode, offline mode).
        const val ERROR_LANGUAGE_UNAVAILABLE_CODE = 13
    }
}
