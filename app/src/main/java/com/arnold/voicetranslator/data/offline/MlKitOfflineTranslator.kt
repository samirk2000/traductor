package com.arnold.voicetranslator.data.offline

import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult
import com.arnold.voicetranslator.util.KanaRomaji
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit Offline translation fallback. Translates Spanish text to the target
 * language entirely on-device once the corresponding language model has been
 * downloaded. This path works with no internet connection, so it acts as the
 * fallback when the DeepSeek API (or a network) is unavailable.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MlKitOfflineTranslator {

    private val modelManager = RemoteModelManager.getInstance()

    /**
     * Builds a [Translator] for the given target language. ML Kit caches model
     * clients internally, so repeated calls are cheap.
     */
    private fun translatorFor(target: TargetLanguage): Translator {
        val targetCode = when (target) {
            TargetLanguage.JAPANESE -> TranslateLanguage.JAPANESE
            TargetLanguage.KOREAN -> TranslateLanguage.KOREAN
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(targetCode)
            .build()
        return Translation.getClient(options)
    }

    /** The downloadable remote model for [target]. */
    private fun remoteModelFor(target: TargetLanguage): TranslateRemoteModel {
        val languageCode = when (target) {
            TargetLanguage.JAPANESE -> TranslateLanguage.JAPANESE
            TargetLanguage.KOREAN -> TranslateLanguage.KOREAN
        }
        return TranslateRemoteModel.Builder(languageCode).build()
    }

    /**
     * Whether the on-device model for [target] is already downloaded.
     */
    suspend fun isModelDownloaded(target: TargetLanguage): Boolean {
        val task = modelManager.isModelDownloaded(remoteModelFor(target))
        return suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { downloaded ->
                if (continuation.isActive) continuation.resume(downloaded) { }
            }
            task.addOnFailureListener {
                if (continuation.isActive) continuation.resume(false) { }
            }
            continuation.invokeOnCancellation { /* task has no public cancel */ }
        }
    }

    /**
     * Triggers the download of the model for [target] using [RemoteModelManager]
     * so progress can be reported.
     *
     * @param onProgress invoked on the main thread with a 0f..1f value.
     * @return true when the model became ready, false otherwise.
     */
    suspend fun downloadModel(
        target: TargetLanguage,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val translator = translatorFor(target)

        if (isModelDownloaded(target)) {
            translator.close()
            onProgress(1f)
            return true
        }

        val conditions = DownloadConditions.Builder().build()
        val task = translator.downloadModelIfNeeded(conditions)
        return suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener {
                onProgress(1f)
                if (continuation.isActive) continuation.resume(true) { }
            }
            task.addOnFailureListener {
                if (continuation.isActive) continuation.resume(false) { }
            }
            continuation.invokeOnCancellation {
                // The download Task has no public cancel API in this version.
            }
        }
    }

    /**
     * Translates [text] via ML Kit. Returns a [TranslationResult] where
     * `mainTranslation` holds the raw on-device translation and `alternatives`
     * is empty (ML Kit does not produce alternative phrasings).
     *
     * @throws MlKitOfflineException when the model isn't downloaded.
     */
    suspend fun translate(text: String, target: TargetLanguage): TranslationResult {
        val translator = translatorFor(target)

        val downloaded = isModelDownloaded(target)
        if (!downloaded) {
            throw MlKitOfflineException(
                "El modelo de traducción offline no está descargado. " +
                    "Toca \"Descargar modelo\" para usarlo sin conexión."
            )
        }

        val result = try {
            suspendCancellableCoroutine<String> { continuation ->
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        if (continuation.isActive) continuation.resume(translated) { }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                MlKitOfflineException("Error de traducción offline: ${error.message}")
                            )
                        }
                    }
            }
        } finally {
            runCatching { translator.close() }
        }

        // ML Kit returns the native script (kana/kanji for Japanese, Hangul
        // for Korean). For Japanese we romanize so it matches the online Romaji
        // output the user is used to; Korean has no cheap romanizer, so it stays
        // as Hangul and is still readable by the traveler's counterpart.
        val mainTranslation = when (target) {
            TargetLanguage.JAPANESE -> KanaRomaji.toRomajiIfKana(result).ifBlank { result }
            TargetLanguage.KOREAN -> result
        }

        return TranslationResult(
            mainTranslation = mainTranslation.ifBlank { text },
            alternatives = emptyList(),
        )
    }
}

/**
 * Typed, UI-friendly error for the offline path.
 */
class MlKitOfflineException(message: String) : Exception(message)
