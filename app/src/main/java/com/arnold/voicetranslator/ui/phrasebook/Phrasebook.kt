package com.arnold.voicetranslator.ui.phrasebook

import android.content.Context
import android.speech.tts.TextToSpeech
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnold.voicetranslator.data.model.TargetLanguage
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

data class PhraseJa(val kana: String, val romaji: String)
data class PhraseKo(val hangul: String, val romanized: String)
/** Optional — the bundled JSON doesn't provide English data yet, so this is
 *  parsed defensively and simply absent (null) for every phrase today. */
data class PhraseEn(val text: String, val romanized: String)

data class Phrase(
    val id: String,
    val category: String,
    val es: String,
    val ja: PhraseJa,
    val ko: PhraseKo,
    val en: PhraseEn? = null,
)

/** One language's native script + phonetic reading + TTS locale, resolved
 *  from a [Phrase] for whichever [TargetLanguage] is currently active. */
private data class NativeScript(val main: String, val phonetic: String, val locale: Locale)

/**
 * Picks only the active target language's native script + phonetic reading
 * out of [phrase] — the Fraseario shows exactly one language at a time now
 * (mirrors the main Traductor's current idioma de salida), instead of always
 * mixing Japanese and Korean together.
 */
private fun nativeScriptFor(phrase: Phrase, target: TargetLanguage): NativeScript = when (target) {
    TargetLanguage.JAPANESE -> NativeScript(phrase.ja.kana, phrase.ja.romaji, Locale.JAPAN)
    TargetLanguage.KOREAN -> NativeScript(phrase.ko.hangul, phrase.ko.romanized, Locale.KOREA)
    TargetLanguage.ENGLISH -> phrase.en?.let { NativeScript(it.text, it.romanized, Locale.ENGLISH) }
        // No "en" data in phrasebook.json yet — fall back to the Spanish
        // text itself (already Latin script, no romaji needed) instead of
        // showing an empty card.
        ?: NativeScript(phrase.es, "", Locale.ENGLISH)
}

/** Ordered list of categories as they should appear in the filter chips. */
val PHRASEBOOK_CATEGORIES = listOf(
    "basico", "aeropuerto", "hotel", "restaurante", "emergencia", "compras",
)

fun phrasebookCategoryLabel(category: String): String = when (category) {
    "basico" -> "Básico"
    "aeropuerto" -> "Aeropuerto"
    "hotel" -> "Hotel"
    "restaurante" -> "Restaurante"
    "emergencia" -> "Emergencia"
    "compras" -> "Compras"
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
                val enObj = obj.optJSONObject("en")
                add(
                    Phrase(
                        id = obj.getString("id"),
                        category = obj.getString("category"),
                        es = obj.getString("es"),
                        ja = PhraseJa(
                            kana = jaObj.getString("kana"),
                            romaji = jaObj.getString("romaji"),
                        ),
                        ko = PhraseKo(
                            hangul = koObj.getString("hangul"),
                            romanized = koObj.getString("romanized"),
                        ),
                        en = enObj?.let {
                            PhraseEn(
                                text = it.getString("text"),
                                romanized = it.optString("romanized", ""),
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
) {
    val context = LocalContext.current

    val phrases = remember { loadPhrasebook(context) }
    var favorites by remember { mutableStateOf(readFavorites(context)) }
    var query by rememberSaveable { mutableStateOf("") }
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

    val filtered = remember(phrases, query, selectedCategory, showOnlyFavorites, favorites) {
        val normalizedQuery = query.trim().lowercase()
        phrases.filter { phrase ->
            val matchesQuery = normalizedQuery.isBlank() ||
                phrase.es.lowercase().contains(normalizedQuery)
            val matchesCategory = selectedCategory == null || phrase.category == selectedCategory
            val matchesFavorite = !showOnlyFavorites || favorites.contains(phrase.id)
            matchesQuery && matchesCategory && matchesFavorite
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "Fraseario",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "Frases esenciales en ${targetLanguage.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar en español…") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar búsqueda")
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
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("Todas") },
                    shape = RoundedCornerShape(20.dp),
                )
            }
            items(PHRASEBOOK_CATEGORIES) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = {
                        selectedCategory = if (selectedCategory == category) null else category
                    },
                    label = { Text(phrasebookCategoryLabel(category)) },
                    shape = RoundedCornerShape(20.dp),
                )
            }
            item {
                FilterChip(
                    selected = showOnlyFavorites,
                    onClick = { showOnlyFavorites = !showOnlyFavorites },
                    label = { Text("★ Favoritos") },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No se encontraron frases.",
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
                    PhraseCard(
                        es = phrase.es,
                        native = native,
                        isFavorite = favorites.contains(phrase.id),
                        onToggleFavorite = { toggleFavorite(phrase.id) },
                        onSpeak = { tts.speak(native.main, native.locale) },
                    )
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
 * language's native script (kana/hangul/etc.) large in the middle, its
 * phonetic reading small below, plus TTS and favorite buttons. Replaces the
 * old dual JA+KO layout — only the currently selected idioma de salida is
 * ever shown, matching the main Traductor's language selector.
 */
@Composable
private fun PhraseCard(
    es: String,
    native: NativeScript,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSpeak: () -> Unit,
) {
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
            // Spanish — small, gray, top.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = es,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Quitar de favoritos" else "Agregar a favoritos",
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

            // Native script — big, centered, middle.
            Text(
                text = native.main,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            // Phonetic reading (romaji/romanized) — small, gray, below.
            if (native.phonetic.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = native.phonetic,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
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
                    contentDescription = "Escuchar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
