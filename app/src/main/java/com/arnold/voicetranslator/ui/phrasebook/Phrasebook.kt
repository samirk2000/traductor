package com.arnold.voicetranslator.ui.phrasebook

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.data.model.Language
import com.arnold.voicetranslator.data.model.TargetLanguage
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized
import com.arnold.voicetranslator.ui.localization.localizedName
import com.arnold.voicetranslator.ui.localization.toUiLanguage
import org.json.JSONArray
import java.util.Locale

/**
 * =====================================================================
 * Phrasebook / Fraseario — 100% independent module.
 *
 * This file intentionally does NOT touch MainActivity's existing
 * "En vivo" / "Subtítulos" composables or MainViewModel. It owns its own
 * data loading, its own TTS engine instance, and its own favorites
 * storage (SharedPreferences), so nothing here can regress the existing
 * live-conversation or subtitles features.
 * =====================================================================
 */

// ---------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------

data class PhraseJa(val kana: String, val phonetic: String)
data class PhraseKo(val hangul: String, val phonetic: String)
/** Chino — `hanzi` es el caracter nativo, `phonetic` es el pinyin. Parseado
 *  defensivamente: si el JSON aún no trae "zh" para una frase, queda null. */
data class PhraseZh(val hanzi: String, val phonetic: String)
/** Optional — English has no phonetic reading of its own (it *is* the
 *  target script), so there's no `phonetic` field here on purpose: showing
 *  a second Latin-script line identical to the first was exactly the
 *  "duplicado" bug in the Fraseario for Inglés. */
data class PhraseEn(val text: String)

data class Phrase(
    val id: String,
    val category: String,
    val es: String,
    val ja: PhraseJa,
    val ko: PhraseKo,
    val zh: PhraseZh? = null,
    val en: PhraseEn? = null,
)

/**
 * Native script (kana/hangul/hanzi/text) for the currently active
 * [TargetLanguage] — the Fraseario shows exactly one language at a time
 * (mirrors the main Traductor's idioma de salida) instead of mixing them.
 */
private fun nativeScriptFor(phrase: Phrase, target: TargetLanguage): String = when (target) {
    TargetLanguage.JAPANESE -> phrase.ja.kana
    TargetLanguage.KOREAN -> phrase.ko.hangul
    TargetLanguage.CHINESE -> phrase.zh?.hanzi ?: phrase.es
    TargetLanguage.ENGLISH -> phrase.en?.text ?: phrase.es
}

/**
 * Phonetic reading for the currently active [TargetLanguage]: romaji for
 * JA, romanizado for KO, pinyin for ZH, and always "" for EN (English has
 * no separate phonetic script — this is what fixes the old duplicated
 * line in the Inglés cards).
 */
private fun phoneticFor(phrase: Phrase, target: TargetLanguage): String = when (target) {
    TargetLanguage.JAPANESE -> phrase.ja.phonetic
    TargetLanguage.KOREAN -> phrase.ko.phonetic
    TargetLanguage.CHINESE -> phrase.zh?.phonetic ?: ""
    TargetLanguage.ENGLISH -> ""
}

/**
 * "Meaning" line shown at the top of each card (used to always be hardcoded
 * Spanish). Now follows the Traductor's Origen selector: if Origen is
 * Inglés, show the phrase's English meaning instead — every phrase in
 * phrasebook.json already carries an `en` entry. Any other Origen (ja/ko/zh,
 * uncommon as an "idioma de significado") still falls back to Spanish, since
 * those don't have a plain-meaning field of their own.
 */
private fun meaningFor(phrase: Phrase, origin: Language): String = when (origin) {
    Language.ENGLISH -> phrase.en?.text?.takeIf { it.isNotBlank() } ?: phrase.es
    else -> phrase.es
}

/** TTS locale for the currently active [TargetLanguage]. */
private fun ttsLocaleFor(target: TargetLanguage): Locale = when (target) {
    TargetLanguage.JAPANESE -> Locale.JAPAN
    TargetLanguage.KOREAN -> Locale.KOREA
    TargetLanguage.CHINESE -> Locale.CHINESE
    TargetLanguage.ENGLISH -> Locale.ENGLISH
}

/**
 * Normalizes a category string so it can be compared reliably regardless
 * of accents/case coming from phrasebook.json (e.g. "CORTESIA" vs
 * "cortesía" vs "Cortesia" all resolve to the same key: "cortesia"). This
 * is what fixes the "Básico" chip showing empty when the JSON category
 * didn't exactly byte-match the hardcoded chip value.
 */
private fun normalizeCategoryKey(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace("á", "a")
    .replace("é", "e")
    .replace("í", "i")
    .replace("ó", "o")
    .replace("ú", "u")
    .replace("ñ", "n")

/** Ordered list of categories (normalized keys) as they should appear in the filter chips. */
val PHRASEBOOK_CATEGORIES = listOf(
    "cortesia", "precios", "compras", "restaurante", "transporte", "hotel",
    "direcciones", "basico", "conversacion_casual", "emergencia",
)

@Composable
fun phrasebookCategoryLabel(category: String, uiLanguage: UiLanguage): String = when (normalizeCategoryKey(category)) {
    "cortesia" -> localized(uiLanguage, R.string.category_courtesy)
    "precios" -> localized(uiLanguage, R.string.category_prices)
    "compras" -> localized(uiLanguage, R.string.category_shopping)
    "restaurante" -> localized(uiLanguage, R.string.category_restaurant)
    "transporte" -> localized(uiLanguage, R.string.category_transport)
    "hotel" -> localized(uiLanguage, R.string.category_hotel)
    "direcciones" -> localized(uiLanguage, R.string.category_directions)
    "basico" -> localized(uiLanguage, R.string.category_basic)
    "conversacion_casual" -> localized(uiLanguage, R.string.category_casual_conversation)
    "emergencia" -> localized(uiLanguage, R.string.category_emergency)
    else -> category.replaceFirstChar { it.uppercase() }
}

/** Loads data/phrasebook.json (bundled as an Android asset) into memory. */
private fun loadPhrasebook(context: Context): List<Phrase> {
    return runCatching {
        val json = context.assets.open("phrasebook.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val jaObj = obj.getJSONObject("ja")
                val koObj = obj.getJSONObject("ko")
                // "zh" is optional/defensive: older phrasebook.json entries
                // (or ones not yet updated by hand with pinyin) simply won't
                // have it, and that must never crash the loader.
                val zhObj = obj.optJSONObject("zh")
                val enObj = obj.optJSONObject("en")
                add(
                    Phrase(
                        id = obj.getString("id"),
                        category = obj.getString("category"),
                        es = obj.getString("es"),
                        ja = PhraseJa(
                            kana = jaObj.getString("kana"),
                            phonetic = jaObj.optString("romaji", ""),
                        ),
                        ko = PhraseKo(
                            hangul = koObj.getString("hangul"),
                            phonetic = koObj.optString("romanized", ""),
                        ),
                        zh = zhObj?.let {
                            PhraseZh(
                                hanzi = it.optString("hanzi", ""),
                                phonetic = it.optString("pinyin", ""),
                            )
                        },
                        en = enObj?.let {
                            PhraseEn(
                                text = it.optString("text", ""),
                            )
                        },
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }
}

// ---------------------------------------------------------------------
// Favorites (localStorage equivalent: SharedPreferences)
// ---------------------------------------------------------------------

private const val PREFS_NAME = "phrasebook_prefs"
private const val KEY_FAVORITES = "favorite_ids"

private fun readFavorites(context: Context): MutableSet<String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
}

private fun writeFavorites(context: Context, favorites: Set<String>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(KEY_FAVORITES, favorites)
        .apply()
}

// ---------------------------------------------------------------------
// Own, self-contained TTS helper (does not reuse TtsManager/ViewModel,
// keeping this module fully independent).
// ---------------------------------------------------------------------

private class PhrasebookTts(context: Context) {
    private var engine: TextToSpeech? = null
    var ready: Boolean = false
        private set

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
    }

    fun speak(text: String, locale: Locale) {
        if (text.isBlank()) return
        val tts = engine ?: return
        runCatching {
            tts.language = locale
            tts.stop()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phrasebook_$text")
        }
    }

    fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }
}

// ---------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhrasebookScreen(
    modifier: Modifier = Modifier,
    // Mirrors the main Traductor's "idioma de salida" so the Fraseario shows
    // the same single active language instead of always mixing JA + KO.
    targetLanguage: TargetLanguage = TargetLanguage.JAPANESE,
    // Mirrors the Traductor's Origen selector: drives the "meaning" line
    // (top of each card), which used to always be hardcoded Spanish.
    originLanguage: Language = Language.SPANISH,
    // App-wide UI language (see MainActivity/TranslatorUiState.uiLanguage):
    // drives EVERY piece of chrome on this screen (title, search hint,
    // category chips, empty state, content descriptions), not just the
    // "meaning" line above (which is already driven by originLanguage).
    uiLanguage: UiLanguage = originLanguage.toUiLanguage(),
) {
    val context = LocalContext.current

    val phrases = remember { loadPhrasebook(context) }
    LaunchedEffect(phrases) {
        Log.d("PHRASEBOOK", "categorias en json: ${phrases.map { it.category }.distinct()}")
    }
    var favorites by remember { mutableStateOf(readFavorites(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    // selectedCategory always stores a normalized key (see normalizeCategoryKey)
    // so it can be compared 1:1 against PHRASEBOOK_CATEGORIES entries and against
    // normalizeCategoryKey(phrase.category) below — no more accent/case mismatches.
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var showOnlyFavorites by rememberSaveable { mutableStateOf(false) }

    val tts = remember { PhrasebookTts(context) }
    DisposableEffect(Unit) {
        onDispose { tts.release() }
    }

    fun toggleFavorite(id: String) {
        val updated = favorites.toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        favorites = updated
        writeFavorites(context, updated)
    }

    // Filtered step by step (category -> search -> favorites) instead of one
    // big accumulative AND, so toggling one filter can never leave a stale
    // condition "pegada" from a previous filter combination.
    val filtered = remember(phrases, query, selectedCategory, showOnlyFavorites, favorites, originLanguage) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        phrases
            .filter { phrase ->
                selectedCategory == null || normalizeCategoryKey(phrase.category) == selectedCategory
            }
            .filter { phrase ->
                normalizedQuery.isBlank() ||
                    meaningFor(phrase, originLanguage).lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    phrase.es.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            .filter { phrase ->
                !showOnlyFavorites || favorites.contains(phrase.id)
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            localized(uiLanguage, R.string.phrasebook_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            localized(uiLanguage, R.string.phrasebook_subtitle, targetLanguage.localizedName(uiLanguage)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    if (originLanguage == Language.ENGLISH) {
                        localized(uiLanguage, R.string.search_in_english)
                    } else {
                        localized(uiLanguage, R.string.search_in_spanish)
                    },
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = localized(uiLanguage, R.string.clear_search_cd))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        Spacer(Modifier.size(12.dp))

        // Category chips (horizontal, scrollable) + favorites toggle chip.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            item {
                FilterChip(
                    // "Todas" only reads as selected when there's truly no
                    // other filter active — otherwise it looked selected
                    // while favorites was still silently on.
                    selected = selectedCategory == null && !showOnlyFavorites,
                    onClick = {
                        selectedCategory = null
                        // Clicking "Todas"/any category always clears the
                        // favorites-only toggle — this is the fix for the
                        // "vuelvo a Todas y sigue vacío" bug: showOnlyFavorites
                        // no longer stays pegado from a previous selection.
                        showOnlyFavorites = false
                    },
                    label = { Text(localized(uiLanguage, R.string.category_all)) },
                    shape = RoundedCornerShape(20.dp),
                )
            }
            items(PHRASEBOOK_CATEGORIES) { category ->
                FilterChip(
                    selected = selectedCategory == category && !showOnlyFavorites,
                    onClick = {
                        showOnlyFavorites = false
                        selectedCategory = if (selectedCategory == category) null else category
                    },
                    label = { Text(phrasebookCategoryLabel(category, uiLanguage)) },
                    shape = RoundedCornerShape(20.dp),
                )
            }
            item {
                FilterChip(
                    selected = showOnlyFavorites,
                    onClick = { showOnlyFavorites = !showOnlyFavorites },
                    label = { Text(localized(uiLanguage, R.string.favorites_chip)) },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        // Keying on the three filter inputs guarantees this block always
        // recomposes fresh when the user switches chips or types a search,
        // instead of ever reusing a stale filtered list/empty-state.
        key(selectedCategory, showOnlyFavorites, query) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        localized(uiLanguage, R.string.no_phrases_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filtered, key = { it.id }) { phrase ->
                        val native = nativeScriptFor(phrase, targetLanguage)
                        val phonetic = phoneticFor(phrase, targetLanguage)
                        val locale = ttsLocaleFor(targetLanguage)
                        val meaning = meaningFor(phrase, originLanguage)
                        PhraseCard(
                            es = meaning,
                            native = native,
                            phonetic = phonetic,
                            target = targetLanguage,
                            uiLanguage = uiLanguage,
                            isFavorite = favorites.contains(phrase.id),
                            onToggleFavorite = { toggleFavorite(phrase.id) },
                            onSpeak = { tts.speak(native, locale) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Card UI
// ---------------------------------------------------------------------

/**
 * Single-language phrase card: Spanish small/gray on top, the active target
 * language's native script (kana/hangul/hanzi/text) large in the middle,
 * and — only when [target] isn't [TargetLanguage.ENGLISH] and [phonetic]
 * isn't blank — its phonetic reading small italic below, plus TTS and
 * favorite buttons. English has no separate phonetic script, so line 3 is
 * skipped entirely for it; that's what fixes the old "duplicado" bug where
 * Inglés showed the same text twice.
 */
@Composable
private fun PhraseCard(
    es: String,
    native: String,
    phonetic: String,
    target: TargetLanguage,
    uiLanguage: UiLanguage,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSpeak: () -> Unit,
) {
    // Chino usa tamaños ligeramente distintos (línea es 12sp, hanzi 18sp
    // bold) según lo pedido para el Fraseario zh; el resto de idiomas
    // conserva el tamaño original (13sp / 26sp) para no regresar nada.
    val isChinese = target == TargetLanguage.CHINESE
    val esFontSize = if (isChinese) 12.sp else 13.sp
    val nativeFontSize = if (isChinese) 18.sp else 26.sp

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Meaning (Spanish or English, per Origen) — small, gray, top.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Guards the rare Origen==Destino==Inglés case, where the
                // meaning line and the native line below would otherwise
                // show the exact same English text twice.
                if (es.isNotBlank() && !es.equals(native, ignoreCase = true)) {
                    Text(
                        text = es,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = esFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) {
                            localized(uiLanguage, R.string.remove_favorite_cd)
                        } else {
                            localized(uiLanguage, R.string.add_favorite_cd)
                        },
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.size(10.dp))

            // Native script — big, bold, centered, middle. For CHINESE this
            // is the hanzi at 18sp (per Fraseario zh spec) instead of the
            // 26sp used by the other target languages.
            Text(
                text = native,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = nativeFontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            // Phonetic reading (romaji/romanizado/pinyin) — small, gray,
            // italic, below. Never shown for English: EN's "native script"
            // *is* the target text already, so a second identical-ish line
            // was the duplicado bug this skip fixes.
            if (target != TargetLanguage.ENGLISH && phonetic.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = phonetic,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.size(10.dp))

            // TTS button.
            IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = localized(uiLanguage, R.string.listen_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
