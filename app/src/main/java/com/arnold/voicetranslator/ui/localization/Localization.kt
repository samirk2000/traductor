package com.arnold.voicetranslator.ui.localization

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.data.model.Language
import com.arnold.voicetranslator.data.model.TargetLanguage
import java.util.Locale

/**
 * Full-app UI language: drives EVERY on-screen string (tabs, menus, buttons,
 * hints, dialogs — not just the translated content), independent of the
 * device's system locale. Derived from the Traductor's "Origen" selector
 * (see [Language.toUiLanguage] / [com.arnold.voicetranslator.ui.state.TranslatorUiState.uiLanguage]):
 * Origen = Inglés -> [EN], anything else -> [ES].
 */
enum class UiLanguage(val localeTag: String) {
    EN("en"),
    ES("es"),
}

/** Maps the Traductor's Origen selector to the app-wide [UiLanguage]. */
fun Language.toUiLanguage(): UiLanguage =
    if (this == Language.ENGLISH) UiLanguage.EN else UiLanguage.ES

/**
 * Resolves a string resource for [uiLanguage] regardless of the device's
 * actual system locale, by creating a locale-overridden [android.content.Context]
 * on the fly and reading from it. This is what lets values/strings.xml
 * (English, default) vs values-es/strings.xml (Spanish) be selected purely
 * by the in-app Origen selector instead of Settings > System > Languages.
 */
@Composable
fun localized(uiLanguage: UiLanguage, @StringRes id: Int): String {
    val context = LocalContext.current
    return remember(uiLanguage, id) {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale(uiLanguage.localeTag))
        context.createConfigurationContext(config).getString(id)
    }
}

/** Same as [localized] but with `String.format`-style arguments (e.g. "%1$s"). */
@Composable
fun localized(uiLanguage: UiLanguage, @StringRes id: Int, vararg formatArgs: Any): String {
    val context = LocalContext.current
    val argsKey = formatArgs.joinToString()
    return remember(uiLanguage, id, argsKey) {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale(uiLanguage.localeTag))
        context.createConfigurationContext(config).getString(id, *formatArgs)
    }
}

/**
 * Non-Composable counterpart of [localized], for use from ViewModels (e.g.
 * [com.arnold.voicetranslator.ui.MainViewModel]'s `errorMessage` strings),
 * which have direct [Context] access ([android.app.Application]) but no
 * Compose scope to call [LocalContext.current] from.
 */
fun localizedString(context: Context, uiLanguage: UiLanguage, @StringRes id: Int, vararg formatArgs: Any): String {
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale(uiLanguage.localeTag))
    val localizedContext = context.createConfigurationContext(config)
    return if (formatArgs.isEmpty()) localizedContext.getString(id) else localizedContext.getString(id, *formatArgs)
}

/**
 * Localized display name for a [Language] (Origen/Destino selector), e.g.
 * "Inglés" in Spanish UI vs "English" in English UI — independent from
 * [Language.displayName], which is always Spanish (used for the flag+name
 * chip, a stylistic choice kept as-is for the language picker itself).
 */
@Composable
fun Language.localizedName(uiLanguage: UiLanguage): String = when (this) {
    Language.SPANISH -> localized(uiLanguage, R.string.lang_spanish)
    Language.ENGLISH -> localized(uiLanguage, R.string.lang_english)
    Language.JAPANESE -> localized(uiLanguage, R.string.lang_japanese)
    Language.KOREAN -> localized(uiLanguage, R.string.lang_korean)
    Language.CHINESE -> localized(uiLanguage, R.string.lang_chinese)
}

/** Localized display name for a [TargetLanguage] (legacy 4-language selector). */
@Composable
fun TargetLanguage.localizedName(uiLanguage: UiLanguage): String = when (this) {
    TargetLanguage.JAPANESE -> localized(uiLanguage, R.string.lang_japanese)
    TargetLanguage.KOREAN -> localized(uiLanguage, R.string.lang_korean)
    TargetLanguage.CHINESE -> localized(uiLanguage, R.string.lang_chinese)
    TargetLanguage.ENGLISH -> localized(uiLanguage, R.string.lang_english)
}

/** Non-Composable counterpart of [TargetLanguage.localizedName], for ViewModels. */
fun TargetLanguage.localizedNamePlain(context: Context, uiLanguage: UiLanguage): String = when (this) {
    TargetLanguage.JAPANESE -> localizedString(context, uiLanguage, R.string.lang_japanese)
    TargetLanguage.KOREAN -> localizedString(context, uiLanguage, R.string.lang_korean)
    TargetLanguage.CHINESE -> localizedString(context, uiLanguage, R.string.lang_chinese)
    TargetLanguage.ENGLISH -> localizedString(context, uiLanguage, R.string.lang_english)
}
