package com.danielsnagustin.childoflight.launcher.data

import java.io.File

/**
 * Lector de diagnóstico del TROPHY.TRP de un juego de PS Vita.
 *
 * Solo lectura, puro Kotlin sin Android: recibe bytes y nunca lanza
 * excepciones (todo fallo es un [TrpReadResult.Unreadable] con motivo).
 * NO cambia la lista de trofeos del launcher ni construye ningún
 * auto-desbloqueo; solo dice qué trae el TRP real del juego.
 *
 * Formato verificado (psdevwiki, "Trophy files", PS3 Developer wiki):
 * el TROPHY.TRP es el "trophy installer" y contiene, entre otros
 * archivos, un TROPCONF.SFM y un TROP.SFM. Ambos son XML con raíz
 * <trophyconf> y un elemento <trophy> por trofeo:
 * - Forma reducida (dentro del TRP, version "1.1"): solo ids, con
 *   elementos autocerrados, por ejemplo
 *   <trophy id="000" hidden="no" ttype="P" pid="-1"/>
 * - Forma completa (TROP.SFM, o TROPCONF.SFM ya instalado,
 *   version "1.0"): con <name> y <detail> dentro de cada <trophy>.
 * Atributos verificados: id (tres dígitos, "000" es siempre el
 * platino cuando existe), hidden ("yes"/"no"), ttype ("P" platino,
 * "G" oro, "S" plata, "B" bronce). El TROPCONF.SFM instalado
 * empieza con una cabecera binaria de 0x40 bytes y después el
 * comentario <!--Sce-Np-Trophy-Signature: ...--> seguido del XML.
 *
 * Lo que NO está verificado y este lector NO inventa: el formato
 * contenedor propio del archivo .TRP (cabecera, tabla de entradas,
 * compresión o cifrado). Por eso el lector no parsea ninguna
 * cabecera TRP: busca el XML <trophyconf> en texto plano dentro de
 * los bytes. Si el contenedor guardara ese XML comprimido o cifrado,
 * el lector devuelve Unreadable y lo dice, en vez de inventar datos.
 * Tampoco se verifica la firma (HMAC/RSA) del XML: no hace falta
 * para contar trofeos y este lector no desbloquea nada.
 *
 * Aviso sobre los ids: los ids del TRP son 000..N-1 en el orden del
 * XML (el 000 es el platino si existe), mientras que los números
 * 1..27 del launcher son el orden de la lista de GameFAQs. Son dos
 * numeraciones distintas, así que comparar ids con números del
 * launcher sería falso. La comparación honesta es por NOMBRE
 * (inglés) y por CANTIDAD: ver [compareByName].
 */
data class TrpTrophy(
    val id: Int,
    val hidden: Boolean,
    val tier: String?,
    val name: String?,
    val detail: String?,
)

data class TrpSuccess(
    val trophyCount: Int,
    val trophies: List<TrpTrophy>,
    val npcommid: String?,
    val trophysetVersion: String?,
    val hasNames: Boolean,
    val spansFound: Int,
    val signatureMarkerPresent: Boolean,
    val warnings: List<String>,
    val note: String,
)

sealed interface TrpReadResult {
    data class Success(val value: TrpSuccess) : TrpReadResult
    data class Unreadable(val reason: String, val detail: String? = null) : TrpReadResult
}

data class TrpNameComparison(
    val trpCount: Int,
    val launcherCount: Int,
    val matchedLauncherNumbers: List<Int>,
    val launcherOnlyNumbers: List<Int>,
    val trpOnlyNames: List<String>,
)

object TrophyTrpReader {

    const val MAX_TRP_BYTES: Long = 64L * 1024L * 1024L
    const val MAX_XML_SPAN_BYTES: Int = 4 * 1024 * 1024
    const val MAX_TROPHIES: Int = 128

    private const val OPEN_TAG = "<trophyconf"
    private const val CLOSE_TAG = "</trophyconf>"

    fun read(bytes: ByteArray): TrpReadResult {
        if (bytes.isEmpty()) {
            return TrpReadResult.Unreadable(
                reason = "Archivo vacío (0 bytes): no es un TROPHY.TRP.",
            )
        }
        if (bytes.size.toLong() > MAX_TRP_BYTES) {
            return TrpReadResult.Unreadable(
                reason = "Archivo demasiado grande para ser un TROPHY.TRP.",
                detail = "Tamaño: ${bytes.size} bytes (límite $MAX_TRP_BYTES).",
            )
        }
        val text = String(bytes, Charsets.UTF_8)
        val signatureMarkerPresent = text.contains("Sce-Np-Trophy-Signature")
        val spans = findTrophyConfSpans(text)
        if (spans.isEmpty()) {
            return TrpReadResult.Unreadable(
                reason = "No se encontró el XML <trophyconf> dentro del archivo.",
                detail = "El contenedor TRP no se pudo interpretar: el XML " +
                    "puede estar comprimido o cifrado, o el archivo puede no " +
                    "ser un TROPHY.TRP. No se inventa ningún conteo.",
            )
        }
        if (spans.all { it.truncated }) {
            return TrpReadResult.Unreadable(
                reason = "El XML <trophyconf> está truncado (falta </trophyconf>).",
                detail = "Archivo incompleto o cortado a mitad del XML.",
            )
        }
        val outcomes = spans.filter { !it.truncated }.map { span ->
            parseSpan(span.xml, signatureMarkerPresent)
        }
        val parsed = outcomes.mapNotNull { (it as? SpanParse.Parsed)?.value }
        if (parsed.isEmpty()) {
            val firstReason = outcomes.filterIsInstance<SpanParse.Invalid>().firstOrNull()?.reason
            return TrpReadResult.Unreadable(
                reason = firstReason
                    ?: "El XML <trophyconf> no contiene entradas <trophy> válidas.",
            )
        }
        val best = parsed.sortedWith(
            compareByDescending<TrpSuccess> { it.trophyCount }
                .thenByDescending { it.hasNames },
        ).first()
        val warnings = best.warnings.toMutableList()
        if (spans.size > 1) {
            warnings.add(
                0,
                "Se encontraron ${spans.size} bloques <trophyconf>; " +
                    "se usa el de más trofeos (${best.trophyCount}).",
            )
        }
        return TrpReadResult.Success(
            best.copy(warnings = warnings, spansFound = spans.size),
        )
    }

    fun readFile(file: File): TrpReadResult {
        if (!file.exists()) {
            return TrpReadResult.Unreadable(
                reason = "El archivo TROPHY.TRP no existe.",
                detail = file.path,
            )
        }
        if (!file.isFile) {
            return TrpReadResult.Unreadable(
                reason = "La ruta no es un archivo.",
                detail = file.path,
            )
        }
        if (file.length() > MAX_TRP_BYTES) {
            return TrpReadResult.Unreadable(
                reason = "Archivo demasiado grande para ser un TROPHY.TRP.",
                detail = "Tamaño: ${file.length()} bytes (límite $MAX_TRP_BYTES).",
            )
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return TrpReadResult.Unreadable(
                reason = "No se pudo leer el archivo TROPHY.TRP.",
                detail = e.message,
            )
        }
        return read(bytes)
    }

    fun readBundledTrp(appDir: File): TrpReadResult {
        val trp = TrophyStoreReader.gameBundledTrp(appDir)
            ?: return TrpReadResult.Unreadable(
                reason = "El juego no trae TROPHY.TRP en sce_sys/trophy/.",
                detail = "Ruta buscada bajo: ${appDir.path}",
            )
        return readFile(trp)
    }

    fun compareByName(
        success: TrpSuccess,
        launcherEnglishNames: Map<Int, String>,
    ): TrpNameComparison {
        val trpNames = success.trophies.mapNotNull { it.name }
            .map { normalizeName(it) }.toSet()
        val matched = mutableListOf<Int>()
        val launcherOnly = mutableListOf<Int>()
        for ((number, englishName) in launcherEnglishNames.entries.sortedBy { it.key }) {
            if (normalizeName(englishName) in trpNames) matched.add(number) else launcherOnly.add(number)
        }
        val launcherNormalized = launcherEnglishNames.values.map { normalizeName(it) }.toSet()
        val trpOnly = success.trophies.mapNotNull { it.name }
            .filter { normalizeName(it) !in launcherNormalized }
        return TrpNameComparison(
            trpCount = success.trophyCount,
            launcherCount = launcherEnglishNames.size,
            matchedLauncherNumbers = matched,
            launcherOnlyNumbers = launcherOnly,
            trpOnlyNames = trpOnly,
        )
    }

    fun normalizeName(name: String): String {
        return name.lowercase().trim().replace(Regex("\\s+"), " ")
    }

    private data class TrophyConfSpan(val xml: String, val truncated: Boolean)

    private fun findTrophyConfSpans(text: String): List<TrophyConfSpan> {
        val spans = mutableListOf<TrophyConfSpan>()
        var from = 0
        while (true) {
            val open = text.indexOf(OPEN_TAG, from)
            if (open < 0) break
            val close = text.indexOf(CLOSE_TAG, open)
            if (close < 0) {
                spans.add(TrophyConfSpan(text.substring(open), truncated = true))
                break
            }
            val end = close + CLOSE_TAG.length
            if (end - open > MAX_XML_SPAN_BYTES) {
                spans.add(TrophyConfSpan(text.substring(open, open + MAX_XML_SPAN_BYTES), truncated = true))
            } else {
                spans.add(TrophyConfSpan(text.substring(open, end), truncated = false))
            }
            from = end
        }
        return spans
    }

    private sealed interface SpanParse {
        data class Parsed(val value: TrpSuccess) : SpanParse
        data class Invalid(val reason: String) : SpanParse
    }

    private fun parseSpan(xml: String, signatureMarkerPresent: Boolean): SpanParse {
        val trophyTags = Regex("<trophy\\s+([^>]*?)(/?)>").findAll(xml).toList()
        if (trophyTags.isEmpty()) {
            return SpanParse.Invalid("El XML <trophyconf> no contiene entradas <trophy>.")
        }
        val trophies = mutableListOf<TrpTrophy>()
        val seenIds = mutableSetOf<Int>()
        val warnings = mutableListOf<String>()
        for (match in trophyTags) {
            val attrs = match.groupValues[1]
            val selfClosing = match.groupValues[2] == "/"
            val idText = readAttr(attrs, "id")
                ?: return SpanParse.Invalid("Entrada <trophy> sin atributo id.")
            val id = idText.toIntOrNull()
                ?: return SpanParse.Invalid("Entrada <trophy> con id no numérico: \"$idText\".")
            if (id < 0 || id > 999) {
                return SpanParse.Invalid("Entrada <trophy> con id fuera de rango: $id.")
            }
            if (!seenIds.add(id)) {
                return SpanParse.Invalid("Ids de trofeo duplicados en el XML (id $id repetido).")
            }
            val hidden = readAttr(attrs, "hidden")?.lowercase() == "yes"
            val tier = readAttr(attrs, "ttype")?.uppercase()
            if (tier != null && tier !in setOf("P", "G", "S", "B")) {
                warnings.add("Trofeo id $id: ttype desconocido \"$tier\".")
            }
            var name: String? = null
            var detail: String? = null
            if (!selfClosing) {
                val bodyStart = match.range.last + 1
                val bodyEnd = xml.indexOf("</trophy>", bodyStart)
                if (bodyEnd > bodyStart) {
                    val body = xml.substring(bodyStart, bodyEnd)
                    name = readElement(body, "name")?.let { unescapeXml(it) }
                    detail = readElement(body, "detail")?.let { unescapeXml(it) }
                }
            }
            trophies.add(TrpTrophy(id, hidden, tier, name, detail))
        }
        if (trophies.isEmpty()) {
            return SpanParse.Invalid("El XML <trophyconf> no contiene entradas <trophy>.")
        }
        if (trophies.size > MAX_TROPHIES) {
            return SpanParse.Invalid(
                "Demasiadas entradas <trophy>: ${trophies.size} (límite $MAX_TROPHIES).",
            )
        }
        val sorted = trophies.sortedBy { it.id }
        val ids = sorted.map { it.id }
        if (ids != (0 until ids.size).toList()) {
            warnings.add(
                "Los ids no son 000..${"%03d".format(ids.size - 1)} consecutivos " +
                    "(primero ${"%03d".format(ids.first())}, " +
                    "último ${"%03d".format(ids.last())}).",
            )
        }
        val npcommid = readElement(xml, "npcommid")
        val trophysetVersion = readElement(xml, "trophyset-version")
        return SpanParse.Parsed(
            TrpSuccess(
                trophyCount = sorted.size,
                trophies = sorted,
                npcommid = npcommid,
                trophysetVersion = trophysetVersion,
                hasNames = sorted.any { it.name != null },
                spansFound = 1,
                signatureMarkerPresent = signatureMarkerPresent,
                warnings = warnings,
                note = "XML <trophyconf> en texto plano dentro del TRP. " +
                    "El contenedor TRP en sí no se parsea (formato no verificado).",
            ),
        )
    }

    private fun readAttr(attrs: String, name: String): String? {
        // El nombre debe ir al inicio o tras un espacio: sin el límite,
        // buscar "id" casaba con el "id" de pid="000" y un trofeo sin id
        // se leía como id 0 (bug real, no solo de test).
        val regex = Regex("(?:^|\\s)$name\\s*=\\s*[\"']([^\"']*)[\"']")
        return regex.find(attrs)?.groupValues?.get(1)
    }

    private fun readElement(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
