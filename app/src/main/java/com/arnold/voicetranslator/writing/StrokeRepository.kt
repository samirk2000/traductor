package com.arnold.voicetranslator.writing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads KanjiVG stroke polylines bundled in `assets/writing/strokes.json`.
 * See that folder's ATTRIBUTION.txt (CC BY-SA 3.0, Ulrich Apel / KanjiVG).
 */
object StrokeRepository {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): Map<String, List<List<Vec>>> {
        val raw = context.assets.open("writing/strokes.json").bufferedReader().use { it.readText() }
        return parse(raw)
    }

    fun parse(raw: String): Map<String, List<List<Vec>>> {
        val file = json.decodeFromString(StrokeFile.serializer(), raw)
        return file.glyphs.mapValues { (_, strokes) ->
            strokes.map { stroke -> stroke.map { Vec(it[0].toFloat(), it[1].toFloat()) } }
        }
    }

    @Serializable
    private data class StrokeFile(
        val viewBox: Int = 109,
        val glyphs: Map<String, List<List<List<Double>>>> = emptyMap(),
    )
}
