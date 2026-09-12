package com.arnold.voicetranslator.data.offline

import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.data.model.TranslationResult
import com.arnold.voicetranslator.util.JapaneseRomajiConverter
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
            TargetLanguage.ENGLISH -> TranslateLanguage.ENGLISH
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
            TargetLanguage.ENGLISH -> TranslateLanguage.ENGLISH
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
            // Typed so the ViewModel can show a specific "download this
            // model?" dialog instead of a generic error banner — this is the
            // exact "modo avión + sin conexión" case where a translation was
            // attempted before the model was ever downloaded.
            throw ModelNotDownloadedException(target)
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
            // ML Kit returns the native script. Romanize Japanese (via
            // Kuromoji, same as the online path — handles kanji, not just
            // kana) so offline output matches the online Romaji view;
            // Korean/English are already usable as-is.
            TargetLanguage.JAPANESE -> JapaneseRomajiConverter.kanjiToRomaji(result).ifBlank { result }
            else -> result
        }

        return TranslationResult(
            mainTranslation = mainTranslation.ifBlank { text },
            alternatives = emptyList(),
            // Keep the raw kana/kanji so the UI can show it next to the
            // Romaji for native speakers, same as the online path.
            nativeScript = if (target == TargetLanguage.JAPANESE) result else null,
        )
    }
}

/**
 * Typed, UI-friendly error for the offline path.
 */
open class MlKitOfflineException(message: String) : Exception(message)

/**
 * Thrown specifically when the on-device model for [target] hasn't been
 * downloaded yet — lets the ViewModel show a targeted "download this model?"
 * dialog (naming the language and its approximate size) instead of a generic
 * error banner. This is the exact "modo avión, idioma no reconocido" case:
 * the user is offline and simply never downloaded the model.
 */
class ModelNotDownloadedException(val target: TargetLanguage) : MlKitOfflineException(
    "Falta el modelo offline de ${target.displayName} " +
        "(~${OfflineModelSize.approxMbFor(target)} MB). Descárgalo para usarlo sin conexión.",
)

/** Rough, user-facing download-size estimates for ML Kit's translate models. */
object OfflineModelSize {
    fun approxMbFor(target: TargetLanguage): Int = when (target) {
        TargetLanguage.JAPANESE -> 30
        TargetLanguage.KOREAN -> 30
        TargetLanguage.ENGLISH -> 30
    }
}
