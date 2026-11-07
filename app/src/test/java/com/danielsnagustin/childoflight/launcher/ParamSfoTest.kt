package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.ParamSfo
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Construye un param.sfo sintético con las entradas dadas (clave → valor). */
fun buildSfoBytes(entries: List<Pair<String, String>>): ByteArray {
    val keyTable = ByteArrayOutputStream()
    val dataTable = ByteArrayOutputStream()
    val keyOffsets = mutableListOf<Int>()
    val dataOffsets = mutableListOf<Int>()
    val dataLens = mutableListOf<Int>()
    for ((key, _) in entries) {
        keyOffsets.add(keyTable.size())
        keyTable.write(key.toByteArray(Charsets.US_ASCII))
        keyTable.write(0)
    }
    for ((_, value) in entries) {
        dataOffsets.add(dataTable.size())
        val bytes = value.toByteArray(Charsets.UTF_8)
        dataTable.write(bytes)
        dataTable.write(0)
        dataLens.add(bytes.size + 1)
    }
    val keyTableStart = 0x14 + entries.size * 16
    val dataTableStart = keyTableStart + keyTable.size()
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x00, 0x50, 0x53, 0x46)) // "\0PSF"
    out.write(u32le(0x00000101))
    out.write(u32le(keyTableStart))
    out.write(u32le(dataTableStart))
    out.write(u32le(entries.size))
    for (i in entries.indices) {
        out.write(u16le(keyOffsets[i]))
        out.write(u16le(0x0402)) // string UTF-8
        out.write(u32le(dataLens[i]))
        out.write(u32le(dataLens[i]))
        out.write(u32le(dataOffsets[i]))
    }
    out.write(keyTable.toByteArray())
    out.write(dataTable.toByteArray())
    return out.toByteArray()
}

private fun u16le(v: Int) =
    byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

private fun u32le(v: Int) = byteArrayOf(
    (v and 0xFF).toByte(),
    ((v shr 8) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(),
    ((v shr 24) and 0xFF).toByte(),
)

/**
 * Parser de param.sfo. Puro: SFO sintéticos. Cubre la validación por
 * contenido de juegos planos (eboot.bin en la raíz sin carpeta de
 * Title ID): el Title ID se lee del SFO, no del nombre de la carpeta.
 */
class ParamSfoTest {

    @Test
    fun titleIdAndTitleAreParsed() {
        val info = ParamSfo.parse(
            buildSfoBytes(listOf("TITLE_ID" to "PCSB00598", "TITLE" to "Child of Light")),
        ).getOrThrow()
        assertEquals("PCSB00598", info.titleId)
        assertEquals("Child of Light", info.title)
    }

    @Test
    fun missingTitleIdReturnsNull() {
        val info = ParamSfo.parse(
            buildSfoBytes(listOf("TITLE" to "Child of Light")),
        ).getOrThrow()
        assertNull(info.titleId)
        assertEquals("Child of Light", info.title)
    }

    @Test
    fun badMagicIsRejected() {
        val bytes = buildSfoBytes(listOf("TITLE_ID" to "PCSB00598"))
        bytes[1] = 0x58 // "XSF", no "\0PSF"
        val result = ParamSfo.parse(bytes)
        assertTrue(result.isFailure)
    }

    @Test
    fun truncatedSfoIsRejected() {
        val result = ParamSfo.parse(ByteArray(10))
        assertTrue(result.isFailure)
    }

    @Test
    fun emptySfoIsRejected() {
        val result = ParamSfo.parse(ByteArray(0))
        assertTrue(result.isFailure)
    }

    @Test
    fun extraEntriesAreIgnored() {
        val info = ParamSfo.parse(
            buildSfoBytes(
                listOf(
                    "TITLE_ID" to "PCSE00480",
                    "TITLE" to "Child of Light",
                    "APP_VER" to "01.00",
                    "CATEGORY" to "gd",
                ),
            ),
        ).getOrThrow()
        assertEquals("PCSE00480", info.titleId)
        assertEquals("Child of Light", info.title)
    }
}
