package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.InputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * Parser del header de un .pkg de PS Vita (formato PKG de Sony).
 *
 * Puro (sin Android): testeable en unit tests con headers sintéticos.
 *
 * Formato del header (verificado contra psdevwiki "PKG files" y el
 * parser de github.com/sargunv/rom-tools, dos fuentes independientes):
 * - 0x00: magic 4 bytes "\x7FPKG" (0x7F504B47), big-endian.
 * - 0x04: revisión (2 bytes), 0x06: tipo (2 bytes: 0x0001 PS3,
 *   0x0002 PSP/Vita). Se leen pero NO se rechaza por ellos: hay
 *   variantes y lo que valida el juego es el Content ID.
 * - 0x08: offset de metadatos (4 bytes BE, en Vita suele ser 0x280).
 * - 0x10: tamaño de metadatos (4 bytes BE, en Vita suele ser 0x160).
 * - 0x18: tamaño TOTAL del .pkg (8 bytes BE, `total_size` en upstream:
 *   `vita3k/packages/include/packages/pkg.h`, struct PkgHeader).
 *   Upstream rechaza el .pkg si el archivo es más pequeño que este
 *   valor (`pkg.cpp`: `if (pkg_size < byte_swap(pkg_header.total_size))`).
 * - 0x20: offset de datos (8 bytes BE, `data_offset`), 0x28: tamaño de
 *   datos (8 bytes BE, `data_size`).
 * - 0x30: Content ID, 48 bytes (36 caracteres + relleno), ASCII
 *   terminado en NUL, p. ej. "EP0001-PCSB00598_00-CHILDOFLIGHT0000".
 *
 * Estructura del Content ID: "<prefijo región><4 dígitos>-<TITLE_ID>_<2 dígitos>-<nombre>".
 * Prefijos: EP = Europa, UP = Estados Unidos. El Title ID va DENTRO
 * del archivo: los .pkg reales de NPS se llaman por hash
 * ("EAMfBniHkRQw...oldLwRkHA.pkg") y NUNCA traen el Title ID en el
 * nombre. Por eso el nombre del archivo es irrelevante aquí: solo
 * manda el contenido. Ningún chequeo de este archivo mira el nombre.
 *
 * work.bin (licencia del .pkg, lo que Vita3K pide al instalar):
 * - Exactamente 512 bytes.
 * - El archivo REAL de NPS es una licencia .rif BINARIA: cabecera
 *   binaria + Content ID ASCII en el offset 16 + clave. El "KO..."
 *   que se ve en la web de NPS es la misma licencia en texto (zRIF),
 *   pero el .bin NO contiene "KO" en ningún sitio (verificado con el
 *   work.bin real del usuario: 512 B, Content ID
 *   "EP0001-PCSB00598_00-CHILDOFLIGHT0000" en 0x10, sin "KO").
 *   Se aceptan las dos formas: .rif binario (Content ID en 0x10) o
 *   texto zRIF (ASCII base64 que empieza por "KO", con NUL hasta 512).
 *   La forma B se VERIFICA de verdad: el zRIF se decodifica (base64) y
 *   se infla (deflate, como `validate_zrif` de upstream,
 *   `packages/src/license.cpp`: base64 + inflate a 512 bytes) y el
 *   Content ID del .rif resultante debe coincidir con el esperado.
 *   Un zRIF corto o que no infle a 512 bytes no es una licencia válida.
 *   Si se conoce el Content ID del .pkg, el del .bin debe coincidir.
 */
data class PkgHeader(
    /** Content ID completo, p. ej. "EP0001-PCSB00598_00-CHILDOFLIGHT0000". */
    val contentId: String,
    val titleId: TitleId,
    /** Región del prefijo del Content ID: "EU" (EP) o "US" (UP). */
    val region: String,
    /** Campo "type" del header (0x0002 en Vita). Solo informativo. */
    val pkgType: Int,
    /** Tamaño del contenido según el header (offset 0x28). 0 = no informado. */
    val dataSizeBytes: Long,
    /** Tamaño TOTAL del .pkg según el header (offset 0x18). 0 = no informado. */
    val totalSizeBytes: Long = 0L,
    /** Offset de los datos según el header (offset 0x20). 0 = no informado. */
    val dataOffsetBytes: Long = 0L,
    /**
     * content_type de la tabla INFO del .pkg (pin packages/src/pkg.cpp:
     * 0x15 = juego, 0x16 = DLC, 0x1F = tema; el núcleo rechaza
     * cualquier otro). Es lo que distingue un DLC con certeza (P1-4).
     * Null si la tabla INFO no se pudo leer: entonces el llamador usa
     * la regla vieja (solo el nombre del contenido).
     */
    val pkgContentType: Int? = null,
) {
    /** Nombre del contenido (tras "_00-"), p. ej. "CHILDOFLIGHT0000". */
    val contentName: String get() = contentId.substringAfter("_00-", "")

    /**
     * Tamaño total que el header declara para el archivo completo:
     * `total_size` (0x18) si lo trae, si no `data_offset + data_size`.
     * 0 si el header no informa ningún tamaño (entonces la importación
     * no puede comprobar si el .pkg está truncado y no lo intenta).
     */
    val declaredTotalSizeBytes: Long
        get() = when {
            totalSizeBytes > 0 -> totalSizeBytes
            dataOffsetBytes > 0 && dataSizeBytes > 0 -> dataOffsetBytes + dataSizeBytes
            else -> 0L
        }
}

object PkgHeaderParser {

    /** Bytes mínimos para contener el Content ID completo (0x30 + 36). */
    const val HEADER_MIN_BYTES = 0x54
    /** Bytes que se leen del .pkg para parsear (el header son 0x80). */
    const val HEADER_READ_BYTES = 0x80
    /**
     * Bytes que se leen del .pkg en el parseo por stream: el header
     * (0x80) + la tabla INFO que sigue (content_type, P1-4). La tabla
     * vive tras los dos headers (~0x100) y tiene pocas entradas: 4 KB
     * sobran y no se lee el .pkg entero.
     */
    const val INFO_READ_BYTES = 4096
    /** content_type de la tabla INFO (pin packages/src/pkg.cpp). */
    const val CONTENT_TYPE_APP = 0x15
    const val CONTENT_TYPE_DLC = 0x16
    const val CONTENT_TYPE_THEME = 0x1F

    /**
     * Longitud mínima de un texto zRIF válido. Un zRIF real es un .rif
     * de 512 bytes comprimido (deflate) y en base64: medido con un .rif
     * realista (Content ID + clave + resto a cero) el texto resultante
     * ronda los ~110 caracteres, nunca 2. El inflado a 512 bytes es la
     * comprobación fuerte; este mínimo solo rechaza textos absurdos
     * ("KO" + un par de caracteres) antes de intentar decodificar.
     */
    const val MIN_ZRIF_TEXT_LENGTH = 64

    /**
     * Decodifica un texto zRIF a su .rif binario de 512 bytes.
     * Upstream (`validate_zrif`, `packages/src/license.cpp`) hace:
     * base64 del texto e inflate hasta 512 bytes. El prefijo "KO" es
     * literal, no parte del base64: verificado por simulación, el
     * base64 de un deflate crudo de un .rif siempre empieza por
     * "Y2Bk" sea cual sea su contenido, así que un zRIF real es
     * "KO" + base64(deflate(.rif)). Se prueban las dos lecturas del
     * prefijo (sin él primero, que es la real; el texto completo
     * después, por tolerancia) y los dos inflados (deflate crudo y
     * zlib): lo que decide es que el resultado sean exactamente 512
     * bytes. Si ninguna variante llega a 512, el texto no es un zRIF
     * válido.
     */
    fun decodeZrifToRif(text: String): Result<ByteArray> {
        val clean = text.trim()
        if (clean.length < MIN_ZRIF_TEXT_LENGTH) {
            return Result.failure(
                IllegalArgumentException(
                    "La licencia zRIF es demasiado corta (${clean.length} caracteres; " +
                        "una licencia real tiene más de $MIN_ZRIF_TEXT_LENGTH). " +
                        "Elige el work.bin que acompaña a este .pkg.",
                ),
            )
        }
        val candidates = listOf(clean.removePrefix("KO"), clean)
        for (candidate in candidates) {
            val decoded = decodeBase64Lenient(candidate) ?: continue
            for (nowrap in listOf(true, false)) {
                val inflated = inflateTo512(decoded, nowrap)
                if (inflated != null) return Result.success(inflated)
            }
        }
        return Result.failure(
            IllegalArgumentException(
                "No se pudo decodificar la licencia zRIF (no es base64 válido " +
                    "o no se descomprime a una licencia de 512 bytes). " +
                    "Elige el work.bin que acompaña a este .pkg.",
            ),
        )
    }

    /** Content ID del .rif resultante de decodificar un zRIF, o null si no se puede extraer. */
    fun extractZrifContentId(text: String): String? =
        decodeZrifToRif(text).getOrNull()?.let { extractWorkBinContentId(it) }

    private fun decodeBase64Lenient(text: String): ByteArray? {
        val decoder = Base64.getDecoder()
        runCatching { return decoder.decode(text) }
        // Sin relleno '=' al final: se completa antes de rendirse.
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        return runCatching { decoder.decode(padded) }.getOrNull()
    }

    /** Infla a exactamente 512 bytes, o null si no llega / se pasa / falla. */
    private fun inflateTo512(bytes: ByteArray, nowrap: Boolean): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(bytes)
            val out = ByteArray(512)
            var total = 0
            while (total < out.size) {
                val n = inflater.inflate(out, total, out.size - total)
                if (n <= 0) break
                total += n
            }
            if (total == 512 && inflater.finished()) out else null
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private val PKG_MAGIC = byteArrayOf(0x7F, 0x50, 0x4B, 0x47) // "\x7FPKG"

    /** Content ID de una licencia .rif: p. ej. "EP0001-PCSB00598_00-CHILDOFLIGHT0000". */
    private val RIF_CONTENT_ID_REGEX = Regex("^[A-Z]{2}[0-9]{4}-[A-Z0-9]{9}_00-[A-Z0-9]{1,16}$")

    /**
     * Content ID ASCII dentro de un work.bin binario (.rif): está en el
     * offset 16 (hasta 48 bytes o el primer NUL). Null si no hay un
     * Content ID con formato válido ahí.
     */
    fun extractWorkBinContentId(bytes: ByteArray): String? {
        if (bytes.size < 64) return null
        val end = (16 until minOf(64, bytes.size)).firstOrNull { bytes[it] == 0.toByte() }
            ?: minOf(64, bytes.size)
        val text = bytes.copyOfRange(16, end).toString(Charsets.US_ASCII)
        return text.takeIf { RIF_CONTENT_ID_REGEX.matches(it) }
    }
    // El segmento del Title ID se captura laxo (9 caracteres): si no es
    // PCSE00480/PCSB00598, el error dice "otro juego" en vez de
    // "formato no esperado".
    // S6: el separador "_00-" es OPCIONAL: un Content ID atípico sin
    // él ("EP0001-PCSB00598-CHILDOFLIGHT0000") también se acepta y
    // deja contentName en "" (el llamador usa el nombre del archivo).
    private val CONTENT_ID_REGEX =
        Regex("^([A-Z]{2})[0-9]{4}-([A-Z0-9]{9})(?:_[0-9]{2}-.+|-.+)$")

    /** ¿Empiezan estos bytes por la magia de un .pkg? */
    fun isPkgMagic(firstBytes: ByteArray): Boolean {
        if (firstBytes.size < PKG_MAGIC.size) return false
        return PKG_MAGIC.indices.all { firstBytes[it] == PKG_MAGIC[it] }
    }

    /** Parsea el header desde los primeros bytes del archivo. */
    fun parse(bytes: ByteArray): Result<PkgHeader> {
        if (bytes.size < HEADER_MIN_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "El archivo es demasiado pequeño para ser un .pkg de Vita " +
                        "(${bytes.size} bytes; el header necesita $HEADER_MIN_BYTES). " +
                        "Puede estar truncado o no ser un .pkg.",
                ),
            )
        }
        if (!isPkgMagic(bytes)) {
            return Result.failure(
                IllegalArgumentException(
                    "No es un .pkg de Vita: el archivo no empieza por la marca " +
                        "de paquete (\\x7FPKG). Elige el .pkg completo del juego.",
                ),
            )
        }
        val contentId = readContentId(bytes)
            ?: return Result.failure(
                IllegalArgumentException(
                    "El .pkg no trae un Content ID válido en el header. " +
                        "Puede estar corrupto o no ser un .pkg de PS Vita.",
                ),
            )
        val match = CONTENT_ID_REGEX.matchEntire(contentId)
            ?: return Result.failure(
                IllegalArgumentException(
                    "El Content ID del .pkg no tiene el formato esperado: \"$contentId\". " +
                        "Se esperaba algo como EP0001-PCSB00598_00-...",
                ),
            )
        val prefix = match.groupValues[1]
        val titleId = TitleId.fromString(match.groupValues[2])
            ?: return Result.failure(
                IllegalArgumentException(
                    "Este .pkg es de otro juego (${match.groupValues[2]}). " +
                        "Este launcher solo acepta Child of Light: PCSE00480 (US) o PCSB00598 (EU).",
                ),
            )
        val region = when (prefix) {
            "EP" -> "EU"
            "UP" -> "US"
            else -> return Result.failure(
                IllegalArgumentException(
                    "El Content ID del .pkg trae una región desconocida ($prefix). " +
                        "Se esperaba EP (Europa) o UP (EE. UU.).",
                ),
            )
        }
        if (region != titleId.region) {
            return Result.failure(
                IllegalArgumentException(
                    "El Content ID del .pkg no cuadra: dice región $region pero el " +
                        "Title ID ${titleId.id} es de ${titleId.region}. El .pkg parece " +
                        "corrupto o de otro juego.",
                ),
            )
        }
        val pkgType = readU16BE(bytes, 0x06)
        val dataSize = readU64BE(bytes, 0x28)
        val totalSize = readU64BE(bytes, 0x18)
        val dataOffset = readU64BE(bytes, 0x20)
        val contentType = readInfoContentType(bytes)
        return Result.success(PkgHeader(contentId, titleId, region, pkgType, dataSize, totalSize, dataOffset, contentType))
    }

    /** Parsea el header leyendo solo los primeros bytes del stream (no lo consume entero). */
    fun parse(input: InputStream): Result<PkgHeader> {
        val buf = ByteArray(INFO_READ_BYTES)
        var read = 0
        return try {
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            parse(buf.copyOf(read))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo leer el header del .pkg: ${e.message}"))
        }
    }

    /**
     * Valida un work.bin (licencia del .pkg).
     * Reglas: exactamente 512 bytes; y una de estas dos formas:
     *  A) binario .rif (el real de NPS): Content ID válido en el offset 16.
     *     Si se pasa expectedContentId, debe coincidir: el .bin tiene que
     *     ser la licencia DE ESTE .pkg, no de otro juego.
     *  B) texto zRIF: ASCII base64 que empieza por "KO", con relleno NUL
     *     hasta 512 (por si alguien guarda el zRIF como archivo). También
     *     vale el archivo con SOLO la cadena "KO..." (~110 caracteres,
     *     quizá con salto de línea final): antes se rechazaba en el
     *     primer chequeo de tamaño sin llegar nunca a esta rama (P1-3).
     *     Se decodifica (base64 + inflate a 512 bytes) y su Content ID debe
     *     coincidir con expectedContentId, igual que la forma A.
     * Un "work (5).bin" de NPS cumple A (el nombre con "(5)" no importa:
     * solo el contenido).
     */
    fun validateWorkBin(bytes: ByteArray, expectedContentId: String? = null): Result<Unit> {
        // P1-3: un zRIF en texto no tiene por qué venir rellenado con
        // NUL hasta 512 bytes. Si el archivo trae solo la cadena
        // "KO..." se recorta (fuera saltos de línea) y se rellena con
        // NUL hasta 512; el resto del camino (Forma B) no cambia. Un
        // archivo corto que NO empiece por "KO" sigue rechazándose por
        // tamaño: un .rif binario válido siempre pesa 512.
        val normalized = if (bytes.size < 512) {
            val text = bytes.toString(Charsets.US_ASCII).trim()
            if (text.startsWith("KO")) {
                text.toByteArray(Charsets.US_ASCII).copyOf(512)
            } else {
                bytes
            }
        } else {
            bytes
        }
        if (normalized.size != 512) {
            return Result.failure(
                IllegalArgumentException(
                    "El work.bin debe pesar exactamente 512 bytes (pesa ${bytes.size}). " +
                        "Elige el work.bin de 512 B que acompaña al .pkg.",
                ),
            )
        }
        // Forma B: texto zRIF.
        if (normalized[0] == 'K'.code.toByte() && normalized[1] == 'O'.code.toByte()) {
            val end = normalized.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: normalized.size
            val text = normalized.copyOfRange(0, end).toString(Charsets.US_ASCII)
            val bad = text.indices.firstOrNull { i ->
                val c = text[i]
                !(c.isLetterOrDigit() || c == '+' || c == '/' || c == '=')
            }
            if (bad != null) {
                return Result.failure(
                    IllegalArgumentException(
                        "El work.bin trae caracteres no válidos en la licencia zRIF " +
                            "(posición $bad). Elige el work.bin que acompaña a este .pkg.",
                    ),
                )
            }
            // P1-6: antes cualquier texto "KO..." pasaba sin comprobar
            // ni la longitud ni de qué juego era. Ahora el zRIF se
            // decodifica (base64 + inflate a 512 bytes) y su Content ID
            // debe coincidir con el del .pkg, igual que la forma A.
            val rif = decodeZrifToRif(text).getOrElse { e ->
                return Result.failure(e)
            }
            val contentId = extractWorkBinContentId(rif)
                ?: return Result.failure(
                    IllegalArgumentException(
                        "La licencia zRIF no trae un Content ID válido. " +
                            "Elige el work.bin que acompaña a este .pkg.",
                    ),
                )
            if (expectedContentId != null && contentId != expectedContentId) {
                return Result.failure(
                    IllegalArgumentException(
                        "Este work.bin es de otro juego (trae Content ID $contentId, " +
                            "se esperaba $expectedContentId). Elige el work.bin que acompaña a este .pkg.",
                    ),
                )
            }
            return Result.success(Unit)
        }
        // Forma A: binario .rif (la real de NPS).
        val contentId = extractWorkBinContentId(normalized)
        if (contentId == null) {
            return Result.failure(
                IllegalArgumentException(
                    "Ese archivo no es un work.bin válido: no trae licencia zRIF " +
                        "(texto que empieza por «KO») ni Content ID de licencia .rif. " +
                        "Elige el work.bin de 512 B que acompaña a este .pkg.",
                ),
            )
        }
        if (expectedContentId != null && contentId != expectedContentId) {
            return Result.failure(
                IllegalArgumentException(
                    "Este work.bin es de otro juego (trae Content ID $contentId, " +
                        "se esperaba $expectedContentId). Elige el work.bin que acompaña a este .pkg.",
                ),
            )
        }
        return Result.success(Unit)
    }

    /** Valida un work.bin desde un stream (lee como máximo 513 bytes). */
    fun validateWorkBin(input: InputStream, expectedContentId: String? = null): Result<Unit> {
        return try {
            val buf = ByteArray(513)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            if (read > 512) {
                return Result.failure(
                    IllegalArgumentException(
                        "El work.bin debe pesar exactamente 512 bytes (pesa más de 512). " +
                            "Elige el work.bin de 512 B que acompaña al .pkg.",
                    ),
                )
            }
            validateWorkBin(buf.copyOf(read), expectedContentId)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo leer el work.bin: ${e.message}"))
        }
    }

    /** Content ID ASCII del header (48 bytes en 0x30, hasta el primer NUL). */
    private fun readContentId(bytes: ByteArray): String? {
        val raw = bytes.copyOfRange(0x30, 0x30 + 48)
        val end = raw.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: raw.size
        val text = String(raw.copyOf(end), Charsets.US_ASCII).trim()
        if (text.isEmpty()) return null
        if (text.any { it.code < 0x20 || it.code > 0x7E }) return null
        return text
    }

    private fun readU16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readU32BE(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    /**
     * content_type de la tabla INFO del .pkg (pin packages/src/pkg.cpp:
     * tras el header (0xC0) y el ext header, la tabla en info_offset
     * (u32 BE en 0x08) con info_count entradas (u32 BE en 0x0C); cada
     * entrada es tipo (u32) + tamaño (u32) + datos. La de tipo 2 trae
     * el content_type (0x15 juego, 0x16 DLC, 0x1F tema) en su tercer
     * u32. La tabla es texto plano: se lee sin descifrar nada. Null si
     * no se puede leer (header corto, offsets absurdos, sin entrada
     * tipo 2): entonces el llamador usa la regla vieja (solo el
     * nombre del contenido).
     */
    private fun readInfoContentType(bytes: ByteArray): Int? {
        if (bytes.size < 0x10) return null
        val infoOffset = readU32BE(bytes, 0x08)
        val infoCount = readU32BE(bytes, 0x0C)
        if (infoOffset < 0x80 || infoCount <= 0 || infoCount > 64) return null
        var pos = infoOffset
        repeat(infoCount) {
            if (pos < 0 || pos + 8 > bytes.size) return null
            val type = readU32BE(bytes, pos)
            val size = readU32BE(bytes, pos + 4)
            if (type == 2) {
                if (pos + 12 > bytes.size) return null
                return readU32BE(bytes, pos + 8)
            }
            if (size < 0 || size > 4096) return null
            pos += 8 + size
        }
        return null
    }

    private fun readU64BE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
