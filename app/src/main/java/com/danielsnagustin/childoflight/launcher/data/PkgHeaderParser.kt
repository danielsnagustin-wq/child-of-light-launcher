package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.InputStream

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
 * - 0x20: offset de datos (8 bytes BE), 0x28: tamaño de datos (8 bytes BE).
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
 * - Contenido zRIF: ASCII base64 que empieza por "KO", rellenado
 *   con NUL hasta 512 bytes. Sin él Vita3K no puede instalar el .pkg.
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
) {
    /** Nombre del contenido (tras "_00-"), p. ej. "CHILDOFLIGHT0000". */
    val contentName: String get() = contentId.substringAfter("_00-", "")
}

object PkgHeaderParser {

    /** Bytes mínimos para contener el Content ID completo (0x30 + 36). */
    const val HEADER_MIN_BYTES = 0x54
    /** Bytes que se leen del .pkg para parsear (el header son 0x80). */
    const val HEADER_READ_BYTES = 0x80

    private val PKG_MAGIC = byteArrayOf(0x7F, 0x50, 0x4B, 0x47) // "\x7FPKG"
    // El segmento del Title ID se captura laxo (9 caracteres): si no es
    // PCSE00480/PCSB00598, el error dice "otro juego" en vez de
    // "formato no esperado".
    private val CONTENT_ID_REGEX =
        Regex("^([A-Z]{2})[0-9]{4}-([A-Z0-9]{9})_[0-9]{2}-.+$")

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
        return Result.success(PkgHeader(contentId, titleId, region, pkgType, dataSize))
    }

    /** Parsea el header leyendo solo los primeros bytes del stream (no lo consume entero). */
    fun parse(input: InputStream): Result<PkgHeader> {
        val buf = ByteArray(HEADER_READ_BYTES)
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
     * Valida un work.bin (licencia zRIF del .pkg).
     * Reglas: exactamente 512 bytes; contenido ASCII base64 que empieza
     * por "KO", con relleno NUL hasta 512. Un "work (5).bin" de NPS
     * cumple esto (el nombre con "(5)" no importa: solo el contenido).
     */
    fun validateWorkBin(bytes: ByteArray): Result<Unit> {
        if (bytes.size != 512) {
            return Result.failure(
                IllegalArgumentException(
                    "El work.bin debe pesar exactamente 512 bytes (pesa ${bytes.size}). " +
                        "Elige el work.bin de 512 B que acompaña al .pkg.",
                ),
            )
        }
        if (bytes[0] != 'K'.code.toByte() || bytes[1] != 'O'.code.toByte()) {
            return Result.failure(
                IllegalArgumentException(
                    "El work.bin no trae una licencia zRIF válida (debe empezar por «KO»). " +
                        "Elige el work.bin que acompaña a este .pkg.",
                ),
            )
        }
        val end = bytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: bytes.size
        val bad = (0 until end).firstOrNull { i ->
            val c = bytes[i].toInt().toChar()
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
        return Result.success(Unit)
    }

    /** Valida un work.bin desde un stream (lee como máximo 513 bytes). */
    fun validateWorkBin(input: InputStream): Result<Unit> {
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
            validateWorkBin(buf.copyOf(read))
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

    private fun readU64BE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
