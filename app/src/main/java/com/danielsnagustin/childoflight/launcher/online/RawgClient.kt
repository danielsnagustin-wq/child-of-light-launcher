/**
 * Cliente para la API pública de RAWG.io.
 *
 * Este cliente permite buscar juegos y obtener sus detalles sin depender de Android.
 * Los dos endpoints utilizados son:
 *
 * 1. **Búsqueda de juegos**
 *    `GET https://api.rawg.io/api/games?key=<apiKey>&search=<query>&page_size=5`
 *
 * 2. **Detalle de un juego**
 *    `GET https://api.rawg.io/api/games/<id>?key=<apiKey>`
 *
 * La clave de la API (`apiKey`) se envía siempre como parámetro de consulta (`key`).
 * El cliente está pensado para ser genérico por título (sirve para *Child of Light*
 * hoy y para *Fatal Frame II* después) y no registra la URL ni la clave en logs.
 *
 * No se utilizan dependencias externas ni clases de Android; todo el parsing JSON
 * es propio y mínimo.
 */
package com.danielsnagustin.childoflight.launcher.online

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/** ---------------------------------------------------------------------------
 *  Modelos de datos
 * --------------------------------------------------------------------------- */
data class RawgGameSummary(
    val id: Int,
    val slug: String,
    val name: String,
    val released: String?,
    val backgroundImageUrl: String?,
    val genres: List<String>
)

data class RawgGameDetails(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String?,
    val genres: List<String>,
    val released: String?,
    val backgroundImageUrl: String?
)

/** ---------------------------------------------------------------------------
 *  Parser JSON propio (muy limitado)
 * --------------------------------------------------------------------------- */
sealed interface JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue
    data class Arr(val list: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    object Null : JsonValue
}

/**
 * Analiza una cadena JSON completa y devuelve la estructura de datos.
 * Lanza IllegalArgumentException si el JSON está mal formado.
 */
fun parseJson(input: String): JsonValue = JsonParser(input).parse()

/**
 * Analizador JSON mínimo. Es una clase (y no funciones locales) porque
 * parseValue y parseObject/parseArray se llaman entre sí, y las funciones
 * locales de Kotlin no admiten recursión mutua ni referencias hacia adelante.
 */
private class JsonParser(private val input: String) {
    private var idx = 0

    private fun skipWhitespace() {
        while (idx < input.length && input[idx].isWhitespace()) idx++
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (idx >= input.length) throw IllegalArgumentException("Unexpected end of JSON")
        return when (input[idx]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            '-', '+' -> parseNumber()
            in '0'..'9' -> parseNumber()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> throw IllegalArgumentException("Unexpected character '${input[idx]}' at $idx")
        }
    }

    private fun parseObject(): JsonValue.Obj {
        idx++ // consume '{'
        val map = mutableMapOf<String, JsonValue>()
        skipWhitespace()
        if (idx < input.length && input[idx] == '}') {
            idx++; return JsonValue.Obj(map)
        }
        while (true) {
            skipWhitespace()
            val key = (parseString() as JsonValue.Str).value
            skipWhitespace()
            if (idx >= input.length || input[idx] != ':')
                throw IllegalArgumentException("Expected ':' after key at $idx")
            idx++ // consume ':'
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            if (idx >= input.length) throw IllegalArgumentException("Unexpected end after object entry")
            when (input[idx]) {
                ',' -> { idx++; continue }
                '}' -> { idx++; break }
                else -> throw IllegalArgumentException("Expected ',' or '}' at $idx")
            }
        }
        return JsonValue.Obj(map)
    }

    private fun parseArray(): JsonValue.Arr {
        idx++ // consume '['
        val list = mutableListOf<JsonValue>()
        skipWhitespace()
        if (idx < input.length && input[idx] == ']') {
            idx++; return JsonValue.Arr(list)
        }
        while (true) {
            val value = parseValue()
            list.add(value)
            skipWhitespace()
            if (idx >= input.length) throw IllegalArgumentException("Unexpected end in array")
            when (input[idx]) {
                ',' -> { idx++; continue }
                ']' -> { idx++; break }
                else -> throw IllegalArgumentException("Expected ',' or ']' at $idx")
            }
        }
        return JsonValue.Arr(list)
    }

    private fun parseString(): JsonValue.Str {
        idx++ // consume opening quote
        val sb = StringBuilder()
        while (idx < input.length) {
            val ch = input[idx++]
            when (ch) {
                '\\' -> {
                    if (idx >= input.length) throw IllegalArgumentException("Invalid escape at end of string")
                    val esc = input[idx++]
                    when (esc) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > input.length) throw IllegalArgumentException("Invalid \\u escape")
                            val hex = input.substring(idx, idx + 4)
                            sb.append(hex.toInt(16).toChar())
                            idx += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape character '\\$esc'")
                    }
                }
                '"' -> return JsonValue.Str(sb.toString())
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseNumber(): JsonValue.Num {
        val start = idx
        if (input[idx] == '-' || input[idx] == '+') idx++
        while (idx < input.length && input[idx].isDigit()) idx++
        if (idx < input.length && input[idx] == '.') {
            idx++
            while (idx < input.length && input[idx].isDigit()) idx++
        }
        if (idx < input.length && (input[idx] == 'e' || input[idx] == 'E')) {
            idx++
            if (idx < input.length && (input[idx] == '+' || input[idx] == '-')) idx++
            while (idx < input.length && input[idx].isDigit()) idx++
        }
        val numStr = input.substring(start, idx)
        val value = try {
            numStr.toDouble()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid number format '$numStr' at position $start")
        }
        return JsonValue.Num(value)
    }

    private fun parseBoolean(): JsonValue.Bool {
        return if (input.startsWith("true", idx)) {
            idx += 4
            JsonValue.Bool(true)
        } else if (input.startsWith("false", idx)) {
            idx += 5
            JsonValue.Bool(false)
        } else {
            throw IllegalArgumentException("Invalid boolean at $idx")
        }
    }

    private fun parseNull(): JsonValue {
        if (input.startsWith("null", idx)) {
            idx += 4
            return JsonValue.Null
        }
        throw IllegalArgumentException("Invalid null at $idx")
    }

    fun parse(): JsonValue {
        val result = parseValue()
        skipWhitespace()
        if (idx != input.length) throw IllegalArgumentException("Extra data after JSON value")
        return result
    }
}

/** Helper extensions to read fields from JsonValue.Obj de forma tolerante a nulos */
private fun JsonValue.Obj.getString(key: String): String? =
    (this.map[key] as? JsonValue.Str)?.value

private fun JsonValue.Obj.getNumber(key: String): Double? =
    (this.map[key] as? JsonValue.Num)?.value

private fun JsonValue.Obj.getArray(key: String): List<JsonValue>? =
    (this.map[key] as? JsonValue.Arr)?.list

private fun JsonValue.Obj.getObject(key: String): JsonValue.Obj? =
    (this.map[key] as? JsonValue.Obj)

/** ---------------------------------------------------------------------------
 *  Función de red por defecto (HttpURLConnection)
 * --------------------------------------------------------------------------- */
fun defaultRawgHttpGet(urlStr: String): String? {
    var connection: HttpURLConnection? = null
    return try {
        val url = URL(urlStr)
        connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("User-Agent", "ChildOfLightLauncher")
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                sb.toString()
            }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

/** ---------------------------------------------------------------------------
 *  Cliente RAWG
 * --------------------------------------------------------------------------- */
class RawgClient(
    private val fetch: (String) -> String? = ::defaultRawgHttpGet
) {

    /** Busca juegos según una consulta. */
    fun searchGames(query: String, apiKey: String): List<RawgGameSummary> {
        if (query.isBlank() || apiKey.isBlank()) return emptyList()
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.rawg.io/api/games?key=$encodedKey&search=$encodedQuery&page_size=5"
        val body = fetch(url) ?: return emptyList()
        return try {
            val root = parseJson(body) as? JsonValue.Obj ?: return emptyList()
            val results = root.getArray("results") ?: return emptyList()
            results.mapNotNull { item ->
                val obj = item as? JsonValue.Obj ?: return@mapNotNull null
                val idNum = obj.getNumber("id")?.toInt() ?: return@mapNotNull null
                val slug = obj.getString("slug") ?: return@mapNotNull null
                val name = obj.getString("name") ?: return@mapNotNull null
                val released = obj.getString("released")
                val background = obj.getString("background_image")
                val genreArray = obj.getArray("genres") ?: emptyList()
                val genres = genreArray.mapNotNull { gItem ->
                    (gItem as? JsonValue.Obj)?.getString("name")
                }
                RawgGameSummary(
                    id = idNum,
                    slug = slug,
                    name = name,
                    released = released,
                    backgroundImageUrl = background,
                    genres = genres
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Obtiene los detalles de un juego por su ID. */
    fun getGameDetails(id: Int, apiKey: String): RawgGameDetails? {
        if (apiKey.isBlank()) return null
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = "https://api.rawg.io/api/games/$id?key=$encodedKey"
        val body = fetch(url) ?: return null
        return try {
            val root = parseJson(body) as? JsonValue.Obj ?: return null
            val idNum = root.getNumber("id")?.toInt() ?: return null
            val slug = root.getString("slug") ?: return null
            val name = root.getString("name") ?: return null
            val descriptionRaw = root.getString("description_raw")
            val descriptionHtml = root.getString("description")
            val description = descriptionRaw ?: descriptionHtml?.let {
                // Elimina etiquetas HTML simples
                val pattern = Pattern.compile("<[^>]*>")
                pattern.matcher(it).replaceAll("")
            }
            val released = root.getString("released")
            val background = root.getString("background_image")
            val genreArray = root.getArray("genres") ?: emptyList()
            val genres = genreArray.mapNotNull { gItem ->
                (gItem as? JsonValue.Obj)?.getString("name")
            }
            RawgGameDetails(
                id = idNum,
                slug = slug,
                name = name,
                description = description,
                genres = genres,
                released = released,
                backgroundImageUrl = background
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Busca un juego por título exacto (ignorando mayúsculas) y devuelve sus detalles. */
    fun findGameDetails(title: String, apiKey: String): RawgGameDetails? {
        if (title.isBlank() || apiKey.isBlank()) return null
        val candidates = searchGames(title, apiKey)
        if (candidates.isEmpty()) return null
        val exact = candidates.firstOrNull {
            it.name.equals(title, ignoreCase = true)
        } ?: candidates.first()
        return getGameDetails(exact.id, apiKey)
    }
}
