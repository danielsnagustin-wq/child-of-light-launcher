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

    /**
     * work.bin con un zRIF VÁLIDO (antes era "KO" + 16 caracteres sin
     * más, que la validación antigua aceptaba sin comprobar nada).
     * Generado por [buildZrifWorkBinBytes]: .rif realista comprimido
     * con deflate crudo y en base64 con prefijo "KO".
     */
    private fun workBinBytes(): ByteArray = buildZrifWorkBinBytes()

    /**
     * work.bin REAL de NPS: licencia .rif binaria de 512 B (cabecera
     * binaria + Content ID en el offset 16 + clave). Sin "KO" en ningún
     * sitio: es el formato del archivo work (5).bin del usuario.
     */
    private fun rifWorkBinBytes(contentId: String = "EP0001-PCSB00598_00-CHILDOFLIGHT0000"): ByteArray {
        val bytes = ByteArray(512)
        byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x02).copyInto(bytes)
        contentId.toByteArray(Charsets.US_ASCII).copyInto(bytes, 16)
        byteArrayOf(0xDD.toByte(), 0xA3.toByte(), 0xED.toByte(), 0x58.toByte()).copyInto(bytes, 0x40)
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
    fun binaryRifWorkBinPasses() {
        // El work.bin real de NPS: 512 B binarios con Content ID en 0x10, sin "KO".
        assertTrue(PkgHeaderParser.validateWorkBin(rifWorkBinBytes()).isSuccess)
    }

    @Test
    fun binaryRifWithExpectedContentIdPasses() {
        val result = PkgHeaderParser.validateWorkBin(
            rifWorkBinBytes(),
            "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun binaryRifWithWrongContentIdIsRejected() {
        // El .bin es de otro juego: su Content ID no coincide con el del .pkg.
        val result = PkgHeaderParser.validateWorkBin(
            rifWorkBinBytes(),
            "UP0001-PCSE00480_00-CHILDOFLIGHT0000",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("otro juego"))
    }

    @Test
    fun binaryRifWithoutContentIdIsRejected() {
        val bytes = rifWorkBinBytes()
        bytes.fill(0, 16, 52) // borra el Content ID del offset 16
        assertTrue(PkgHeaderParser.validateWorkBin(bytes).isFailure)
    }

    @Test
    fun workBinGarbageIsRejected() {
        // 512 B sin "KO" ni Content ID: no es una licencia.
        val bytes = ByteArray(512) { 0x41 }
        val result = PkgHeaderParser.validateWorkBin(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("work.bin"))
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
