package com.arnold.voicetranslator.writing

import android.content.Context

/** Local progress for the writing course. Nothing here is uploaded. */
class WritingProgressStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): PersistedProgress {
        val raw = prefs.getString(KEY, null) ?: return PersistedProgress()
        return runCatching { ProgressCodec.decode(raw) }.getOrDefault(PersistedProgress())
    }

    fun write(progress: PersistedProgress) {
        prefs.edit().putString(KEY, ProgressCodec.encode(progress)).apply()
    }

    private companion object {
        const val PREFS = "writing_kana"
        const val KEY = "progress_v1"
    }
}
