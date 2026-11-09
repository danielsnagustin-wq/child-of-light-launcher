package com.danielsnagustin.childoflight.launcher.data

import java.io.File

/**
 * Parser de param.sfo (formato PSF de Sony), el archivo de metadatos
 * que acompaña a eboot.bin dentro de sce_sys/ de cada juego de Vita.
 *
 * Puro (sin Android): testeable en unit tests con SFO sintéticos.
 *
 * Formato (little-endian):
 * - 0x00: magic 4 bytes "\0PSF" (0x00, 'P', 'S', 'F').
 * - 0x04: versión (4 bytes, 0x00000101).
 * - 0x08: key_table_start (u32 LE), 0x0C: data_table_start (u32 LE),
 *   0x10: número de entradas (u32 LE).
 * - Entradas desde 0x14, 16 bytes cada una: key_offset (u16),
 *   data_fmt (u16), data_len (u32), data_max_len (u32), data_offset (u32).
 * - Claves: strings NUL-terminados en key_table_start + key_offset.
 * - Datos: en data_table_start + data_offset; fmt 0x0402 = string UTF-8.
 *
 * Para qué sirve aquí: validar el juego por su CONTENIDO cuando la
 * carpeta o el zip no traen el Title ID en el nombre — p. ej. un .zip
 * plano con eboot.bin en la raíz, o una carpeta con otro nombre. Se
 * lee TITLE_ID (y TITLE, el nombre del juego) de sce_sys/param.sfo.
 * Si el SFO falta o no trae TITLE_ID, no se inventa nada: se rechaza
 * con el motivo. El nombre del archivo/carpeta nunca decide nada.
 */
data class SfoInfo(
    /** Title ID leído del SFO, p. ej. "PCSB00598". null si no lo trae. */
    val titleId: String?,
    /** Nombre del juego leído del SFO, p. ej. "Child of Light". null si no lo trae. */
    val title: String?,
    /**
     * CATEGORY leída del SFO ("gd" juego, "gp" update/parche, "ac"
     * contenido adicional). null si no la trae. P2-27: un update trae
     * "gp"; un juego importado como update trae "gd" y se rechaza.
     */
    val category: String? = null,
)

object ParamSfo {

    private val PSF_MAGIC = byteArrayOf(0x00, 0x50, 0x53, 0x46) // "\0PSF"
    private const val FMT_UTF8 = 0x0402
    /** Un param.sfo real pesa pocos KB; por encima de esto, algo va mal. */
    const val MAX_SFO_BYTES = 256 * 1024

    /** Parsea un param.sfo desde sus bytes. */
    fun parse(bytes: ByteArray): Result<SfoInfo> {
        if (bytes.size < 0x14) {
            return Result.failure(
                IllegalArgumentException("El param.sfo es demasiado pequeño (${bytes.size} bytes)."),
            )
        }
        if (!PSF_MAGIC.indices.all { bytes[it] == PSF_MAGIC[it] }) {
            return Result.failure(
                IllegalArgumentException("El archivo no es un param.sfo (no empieza por \\0PSF)."),
            )
        }
        val keyTableStart = readU32LE(bytes, 0x08).toInt()
        val dataTableStart = readU32LE(bytes, 0x0C).toInt()
        val entryCount = readU32LE(bytes, 0x10).toInt()
        if (keyTableStart < 0x14 || keyTableStart > bytes.size ||
            dataTableStart < keyTableStart || dataTableStart > bytes.size
        ) {
            return Result.failure(
                IllegalArgumentException("El param.sfo trae offsets fuera del archivo."),
            )
        }
        if (entryCount < 0 || entryCount > 512) {
            return Result.failure(
                IllegalArgumentException("El param.sfo trae un número de entradas no válido ($entryCount)."),
            )
        }
        var titleId: String? = null
        var title: String? = null
        var category: String? = null
        for (i in 0 until entryCount) {
            val entryOff = 0x14 + i * 16
            if (entryOff + 16 > bytes.size) {
                return Result.failure(
                    IllegalArgumentException("El param.sfo está truncado en la entrada $i."),
                )
            }
            val keyOffset = readU16LE(bytes, entryOff)
            val dataFmt = readU16LE(bytes, entryOff + 2)
            val dataLen = readU32LE(bytes, entryOff + 4).toInt()
            val dataOffset = readU32LE(bytes, entryOff + 12).toInt()
            if (dataFmt != FMT_UTF8) continue
            val keyPos = keyTableStart + keyOffset
            if (keyPos < 0 || keyPos >= bytes.size) continue
            val key = readCString(bytes, keyPos, dataTableStart)
            if (key != "TITLE_ID" && key != "TITLE" && key != "CATEGORY") continue
            val dataPos = dataTableStart + dataOffset
            if (dataPos < 0 || dataPos >= bytes.size || dataLen < 0) continue
            val value = readCString(bytes, dataPos, minOf(dataPos + dataLen, bytes.size))
            when (key) {
                "TITLE_ID" -> titleId = value
                "TITLE" -> title = value
                "CATEGORY" -> category = value
            }
        }
        return Result.success(SfoInfo(titleId?.trim(), title?.trim(), category?.trim()))
    }

    /** Parsea el param.sfo de un archivo (lo lee entero, con tope de tamaño). */
    fun parse(file: File): Result<SfoInfo> {
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("No existe param.sfo en ${file.path}."))
        }
        if (file.length() > MAX_SFO_BYTES) {
            return Result.failure(
                IllegalArgumentException("El param.sfo pesa demasiado (${file.length()} bytes)."),
            )
        }
        return try {
            parse(file.readBytes())
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo leer el param.sfo: ${e.message}"))
        }
    }

    /** Lee un string NUL-terminado en [bytes] desde [start] hasta [limit] (excluido). */
    private fun readCString(bytes: ByteArray, start: Int, limit: Int): String {
        val end = minOf(limit, bytes.size)
        var pos = start
        while (pos < end && bytes[pos] != 0.toByte()) pos++
        return String(bytes, start, pos - start, Charsets.UTF_8).trim()
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }
}
