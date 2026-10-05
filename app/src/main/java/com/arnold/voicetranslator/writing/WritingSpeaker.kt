package com.arnold.voicetranslator.writing

import android.content.Context
import android.speech.tts.TextToSpeech
import com.arnold.voicetranslator.audio.TtsManager
import java.util.Locale

/**
 * Speaks a writing prompt in Japanese. If the device has no Japanese voice,
 * [available] stays false and every speak request is ignored.
 */
class WritingSpeaker(
    context: Context,
    private val onAvailability: (Boolean) -> Unit,
) {
    private val tts = TtsManager(context)
    private var supported = false

    val available: Boolean get() = supported

    init {
        tts.onUnavailable = { onAvailability(false) }
        tts.onReady = {
            val result = tts.setLocale(Locale.JAPANESE)
            supported = result >= TextToSpeech.LANG_AVAILABLE
            onAvailability(supported)
        }
    }

    fun speak(text: String) {
        if (text.isBlank() || !supported) return
        tts.speak(text)
    }

    fun stop() {
        tts.stop()
    }

    fun release() {
        tts.onReady = {}
        tts.onUnavailable = {}
        tts.release()
        supported = false
    }
}
