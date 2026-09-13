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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

data class Phrase(
    val id: String,
    val category: String,
    val es: String,
    val ja: PhraseJa,
    val ko: PhraseKo,
)

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
fun PhrasebookScreen(modifier: Modifier = Modifier) {
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
            "Frases esenciales en japonés y coreano",
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
                    PhraseCard(
                        phrase = phrase,
                        isFavorite = favorites.contains(phrase.id),
                        onToggleFavorite = { toggleFavorite(phrase.id) },
                        onSpeakJapanese = { tts.speak(phrase.ja.kana, Locale.JAPAN) },
                        onSpeakKorean = { tts.speak(phrase.ko.hangul, Locale.KOREA) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Card UI
// ---------------------------------------------------------------------

@Composable
private fun PhraseCard(
    phrase: Phrase,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSpeakJapanese: () -> Unit,
    onSpeakKorean: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Spanish (bold) + favorite star.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = phrase.es,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Quitar de favoritos" else "Agregar a favoritos",
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            // Balanced JA / KO row so both scripts are always visible,
            // side by side on wide screens and stacked-safe on narrow ones
            // (each block manages its own wrapping).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScriptBlock(
                    label = "JA",
                    mainText = phrase.ja.kana,
                    subText = phrase.ja.romaji,
                    accentColor = MaterialTheme.colorScheme.primary,
                    onTap = onSpeakJapanese,
                    modifier = Modifier.weight(1f),
                )
                ScriptBlock(
                    label = "KO",
                    mainText = phrase.ko.hangul,
                    subText = phrase.ko.romanized,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onTap = onSpeakKorean,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One script block: big native text (kana or hangul) with a small gray
 * phonetic reading underneath. Tapping it speaks the native text aloud.
 */
@Composable
private fun ScriptBlock(
    label: String,
    mainText: String,
    subText: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Escuchar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = mainText,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 26.sp,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
