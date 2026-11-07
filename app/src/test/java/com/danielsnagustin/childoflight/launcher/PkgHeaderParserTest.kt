package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.PkgHeaderParser
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser del header .pkg + validación de work.bin.
 * Puro: headers sintéticos con la estructura real (magic en 0x00,
 * Content ID en 0x30). Cubre el fallo reportado por el usuario
 * (2026-11-07): su .pkg EU se llama por hash ("EAMfBniHkRQw...") y
 * el launcher lo rechazaba por no traer el Title ID en el nombre.
 * El nombre nunca se mira aquí: solo el contenido.
 */
class PkgHeaderParserTest {

    /** Header .pkg sintético con el Content ID dado (0x80 bytes). */
    private fun pkgHeaderBytes(contentId: String, dataSize: Long = 114171057L): ByteArray {
        val bytes = ByteArray(0x80)
        bytes[0] = 0x7F
        bytes[1] = 0x50
        bytes[2] = 0x4B
        bytes[3] = 0x47
        bytes[4] = 0x00
        bytes[5] = 0x01 // revisión
        bytes[6] = 0x00
        bytes[7] = 0x02 // tipo: Vita
        var size = dataSize
        for (i in 7 downTo 0) {
            bytes[0x28 + i] = (size and 0xFF).toByte()
            size = size shr 8
        }
        contentId.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x30)
        return bytes
    }

    private fun workBinBytes(zrif: String = "KO5ifR1dQ+e7BsB"): ByteArray {
        val bytes = ByteArray(512)
        zrif.toByteArray(Charsets.US_ASCII).copyInto(bytes)
        return bytes
    }

    @Test
    fun euGameHeaderIsParsed() {
        val result = PkgHeaderParser.parse(pkgHeaderBytes("EP0001-PCSB00598_00-CHILDOFLIGHT0000"))
        assertTrue(result.isSuccess)
        val header = result.getOrThrow()
        assertEquals(TitleId.EU, header.titleId)
        assertEquals("EU", header.region)
        assertEquals("EP0001-PCSB00598_00-CHILDOFLIGHT0000", header.contentId)
        assertEquals("CHILDOFLIGHT0000", header.contentName)
        assertEquals(114171057L, header.dataSizeBytes)
        assertEquals(2, header.pkgType)
    }

    @Test
    fun usGameHeaderIsParsed() {
        val result = PkgHeaderParser.parse(pkgHeaderBytes("UP0001-PCSE00480_00-CHILDOFLIGHT0000"))
        assertTrue(result.isSuccess)
        assertEquals(TitleId.US, result.getOrThrow().titleId)
        assertEquals("US", result.getOrThrow().region)
    }

    @Test
    fun hashNamedPkgIsParsedByContentNotByName() {
        // El caso del usuario: nombre hash, sin Title ID en el nombre.
        // El parser no recibe el nombre: solo el header.
        val result = PkgHeaderParser.parse(pkgHeaderBytes("EP0001-PCSB00598_00-CHILDOFLIGHT0000"))
        assertTrue(result.isSuccess)
        assertEquals(TitleId.EU, result.getOrThrow().titleId)
    }

    @Test
    fun unknownTitleIdIsRejectedAsOtherGame() {
        val result = PkgHeaderParser.parse(pkgHeaderBytes("EP0001-PCSB00999_00-OTHERGAME000000"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("otro juego"))
    }

    @Test
    fun invalidMagicIsRejected() {
        val bytes = pkgHeaderBytes("EP0001-PCSB00598_00-CHILDOFLIGHT0000")
        bytes[0] = 0x50 // "PK" (zip), no "\x7FPKG"
        bytes[1] = 0x4B
        val result = PkgHeaderParser.parse(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No es un .pkg"))
    }

    @Test
    fun truncatedHeaderIsRejected() {
        val result = PkgHeaderParser.parse(ByteArray(40))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("demasiado pequeño"))
    }

    @Test
    fun emptyContentIdIsRejected() {
        val bytes = ByteArray(0x80)
        bytes[0] = 0x7F
        bytes[1] = 0x50
        bytes[2] = 0x4B
        bytes[3] = 0x47
        val result = PkgHeaderParser.parse(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Content ID"))
    }

    @Test
    fun malformedContentIdIsRejected() {
        val result = PkgHeaderParser.parse(pkgHeaderBytes("GARBAGE-NO-CONTENT-ID"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("formato esperado"))
    }

    @Test
    fun regionMismatchIsRejected() {
        // Prefijo UP (US) con Title ID EU: el .pkg no cuadra.
        val result = PkgHeaderParser.parse(pkgHeaderBytes("UP0001-PCSB00598_00-CHILDOFLIGHT0000"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no cuadra"))
    }

    @Test
    fun parseFromStreamReadsOnlyHeader() {
        val result = PkgHeaderParser.parse(ByteArrayInputStream(pkgHeaderBytes("EP0001-PCSB00598_00-CHILDOFLIGHT0000")))
        assertTrue(result.isSuccess)
        assertEquals(TitleId.EU, result.getOrThrow().titleId)
    }

    @Test
    fun pkgMagicIsDetected() {
        assertTrue(PkgHeaderParser.isPkgMagic(byteArrayOf(0x7F, 0x50, 0x4B, 0x47)))
        assertFalse(PkgHeaderParser.isPkgMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(PkgHeaderParser.isPkgMagic(byteArrayOf(0x7F, 0x50)))
        assertFalse(PkgHeaderParser.isPkgMagic(byteArrayOf()))
    }

    // --- work.bin ---

    @Test
    fun validWorkBinPasses() {
        assertTrue(PkgHeaderParser.validateWorkBin(workBinBytes()).isSuccess)
    }

    @Test
    fun workBinWrongSizeIsRejected() {
        val result = PkgHeaderParser.validateWorkBin(ByteArray(511))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("512"))
    }

    @Test
    fun workBinNotStartingWithKoIsRejected() {
        val bytes = workBinBytes("AB5ifR1dQ+e7BsB")
        val result = PkgHeaderParser.validateWorkBin(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("KO"))
    }

    @Test
    fun workBinWithInvalidCharsIsRejected() {
        val bytes = workBinBytes()
        bytes[2] = 0x01 // carácter no base64 dentro del zRIF
        val result = PkgHeaderParser.validateWorkBin(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("caracteres no válidos"))
    }

    @Test
    fun workBinFromStreamValidatesSize() {
        val result = PkgHeaderParser.validateWorkBin(ByteArrayInputStream(workBinBytes()))
        assertTrue(result.isSuccess)
        val tooBig = PkgHeaderParser.validateWorkBin(ByteArrayInputStream(ByteArray(513)))
        assertTrue(tooBig.isFailure)
    }
}
