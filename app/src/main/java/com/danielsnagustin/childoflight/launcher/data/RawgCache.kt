/**
 * Clase que gestiona la caché local en disco de los datos de RAWG.
 *
 * Almacena los detalles de los juegos y sus portadas en archivos JSON y JPG
 * dentro de los directorios proporcionados. No depende de Android y puede
 * probarse en la JVM.
 *
 * @property filesDir Directorio donde se guardan los archivos JSON con los
 *   detalles de los juegos.
 * @property cacheDir Directorio donde se guardan las portadas de los juegos
 *   en formato JPG.
 */
package com.danielsnagustin.childoflight.launcher.data

import java.io.File
import java.io.IOException

import com.danielsnagustin.childoflight.launcher.online.parseJson
import com.danielsnagustin.childoflight.launcher.online.JsonValue
import com.danielsnagustin.childoflight.launcher.online.RawgGameDetails

/**
 * Representa los detalles de un juego tal y como los devuelve la API de RAWG.
 *
 * La clase real es [RawgGameDetails] con la siguiente firma:
 * data class RawgGameDetails(
 *     val id: Int,
 *     val slug: String,
 *     val name: String,
 *     val description: String?,
 *     val genres: List<String>,
 *     val released: String?,
 *     val backgroundImageUrl: String?
 * )
 */
class RawgCache(private val filesDir: File, private val cacheDir: File) {

    /** Convierte un título en una clave segura para usar como nombre de archivo. */
    private fun titleToKey(title: String): String {
        var key = title.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .trim('_')
        if (key.isEmpty()) key = "game"
        return key
    }

    /** Escapa una cadena para insertarla en JSON. */
    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Serializa RawgGameDetails a JSON respetando los nombres de campo de la API. */
    private fun serialize(details: RawgGameDetails): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":${details.id},")
        sb.append("\"slug\":${details.slug?.let { "\"${escapeJsonString(it)}\"" } ?: "null"},")
        sb.append("\"name\":\"${escapeJsonString(details.name)}\",")
        sb.append("\"description\":${details.description?.let { "\"${escapeJsonString(it)}\"" } ?: "null"},")
        sb.append("\"genres\":[")
        details.genres.forEachIndexed { idx, genre ->
            sb.append("\"${escapeJsonString(genre)}\"")
            if (idx < details.genres.size - 1) sb.append(",")
        }
        sb.append("],")
        sb.append("\"released\":${details.released?.let { "\"${escapeJsonString(it)}\"" } ?: "null"},")
        sb.append("\"background_image\":${details.backgroundImageUrl?.let { "\"${escapeJsonString(it)}\"" } ?: "null"}")
        sb.append("}")
        return sb.toString()
    }

    /** Guarda los detalles de un juego en disco de forma casi atómica. */
    fun saveDetails(title: String, details: RawgGameDetails): Boolean {
        val key = titleToKey(title)
        val targetFile = File(filesDir, "rawg_details_${key}.json")
        val tempFile = File(filesDir, "rawg_details_${key}.json.tmp")
        return try {
            val json = serialize(details)
            tempFile.writeText(json, Charsets.UTF_8)
            if (tempFile.renameTo(targetFile)) {
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: IOException) {
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Lee los detalles de un juego desde la caché. */
    fun loadDetails(title: String): RawgGameDetails? {
        val key = titleToKey(title)
        val file = File(filesDir, "rawg_details_${key}.json")
        if (!file.isFile) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = parseJson(content) as? JsonValue.Obj ?: return null
            // Lectores auxiliares
            fun JsonValue.Obj.getString(name: String): String? =
                this.map[name]?.let { if (it is JsonValue.Str) it.value else null }

            fun JsonValue.Obj.getInt(name: String): Int? =
                this.map[name]?.let { if (it is JsonValue.Num) it.value.toInt() else null }

            fun JsonValue.Obj.getStringArray(name: String): List<String> =
                this.map[name]?.let {
                    if (it is JsonValue.Arr) {
                        it.list.mapNotNull { v -> if (v is JsonValue.Str) v.value else null }
                    } else emptyList()
                } ?: emptyList()

            val id = json.getInt("id") ?: return null
            val slug = json.getString("slug") ?: return null
            val name = json.getString("name") ?: return null
            val description = json.getString("description")
            val genres = json.getStringArray("genres")
            val released = json.getString("released")
            val backgroundImageUrl = json.getString("background_image")

            RawgGameDetails(
                id = id,
                slug = slug,
                name = name,
                description = description,
                genres = genres,
                released = released,
                backgroundImageUrl = backgroundImageUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Guarda la portada de un juego en disco de forma casi atómica. */
    fun saveCover(title: String, bytes: ByteArray): Boolean {
        val key = titleToKey(title)
        val targetFile = File(cacheDir, "rawg_cover_${key}.jpg")
        val tempFile = File(cacheDir, "rawg_cover_${key}.jpg.tmp")
        return try {
            tempFile.writeBytes(bytes)
            if (tempFile.renameTo(targetFile)) {
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: IOException) {
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Carga la portada de un juego desde la caché. */
    fun loadCover(title: String): ByteArray? {
        val key = titleToKey(title)
        val file = File(cacheDir, "rawg_cover_${key}.jpg")
        return try {
            if (!file.isFile) null else file.readBytes()
        } catch (e: IOException) {
            null
        }
    }
}
