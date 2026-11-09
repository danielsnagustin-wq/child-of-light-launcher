package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Chequeo de eboot cargable (bug -3, 2026-11-08; corrección
 * 2026-11-08 21:50): un eboot bien instalado es un SELF en disco
 * ('S' 'E' 'C' 0x00; pin load_self.cpp:508-509) y el núcleo lo
 * descifra EN MEMORIA al cargarlo. La versión anterior exigía solo
 * ELF y fallaba siempre: cada JUGAR reinstalaba el .pkg. Solo un
 * eboot sin ninguna de las dos magias (instalación truncada o
 * corrupta) dispara la reinstalación. Un ELF de Vita empieza por
 * 0x7F 'E' 'L' 'F'. Puro JVM.
 *
 * Endurecido (P0-1, 2026-11-09): la magia sola no basta. Un eboot
 * de 4 bytes pasaba como válido y el auto-borrado del .pkg (1,14 GB)
 * destruía la única vía de recuperación. Se exige cabecera
 * estructural: SELF con version 3 y header_type 1 (lo mismo que
 * comprueba el núcleo al cargar, load_self.cpp:512-520), con
 * self_filesize igual al tamaño del archivo y elf_offset y
 * phdr_offset dentro de él; ELF32 con la tabla de segmentos dentro
 * del archivo.
 *
 * Ronda 4 (R4-P0-5/R4-P0-6, 2026-11-09): header_type se lee en su
 * offset real, u16 @10 (el offset 8 es sdk_type, no header_type:
 * un juego con sysver de 3.40 o más trae sdk_type distinto de 1 y
 * antes se rechazaba aunque el SELF fuera perfecto). Y no hay piso
 * de tamaño: un eboot válido puede pesar menos de 1 MB, y un SELF
 * truncado de más de 1 MB se rechaza por self_filesize, no por
 * tamaño mínimo. Los SELF válidos de estos tests llevan por tanto
 * sdk_type 0x10 (realista, distinto de 1) y header_type 1 en @10.
 */
class EbootDecryptionCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
    }

    private fun putU64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
    }

    /**
     * SELF válido: magia + version 3 + sdk_type 0x10 + header_type 1
     * en @10 + self_filesize igual al tamaño del archivo +
     * elf_offset y phdr_offset dentro del archivo con la magia ELF
     * en elf_offset. El tamaño por defecto es 1 MB, pero el SELF
     * también es válido pequeño (ver selfValidoPequenoAceptado).
     */
    private fun validSelfBytes(size: Int = 1_000_000): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 'S'.code.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'C'.code.toByte()
        bytes[3] = 0
        putU32(bytes, 4, 3)
        putU16(bytes, 8, 0x10)
        putU16(bytes, 10, 1)
        putU64(bytes, 32, size.toLong())
        putU64(bytes, 64, 4096)
        putU64(bytes, 72, 4148)
        bytes[4096] = 0x7F
        bytes[4097] = 'E'.code.toByte()
        bytes[4098] = 'L'.code.toByte()
        bytes[4099] = 'F'.code.toByte()
        bytes[4100] = 1
        return bytes
    }

    private fun writeValidSelf(file: File, size: Int = 1_000_000) {
        file.writeBytes(validSelfBytes(size))
    }

    /** ELF32 válido: magia + clase 32 + un segmento dentro del archivo. */
    private fun validElfBytes(size: Int = 1_000_000): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 1
        putU32(bytes, 28, 52)
        putU16(bytes, 42, 32)
        putU16(bytes, 44, 1)
        return bytes
    }

    private fun writeValidElf(file: File, size: Int = 1_000_000) {
        file.writeBytes(validElfBytes(size))
    }

    @Test
    fun elfEbootIsDecrypted() {
        val eboot = tmp.newFile("eboot.bin")
        writeValidElf(eboot)
        assertTrue(GameLibraryRepository.isElfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun selfEbootIsValid() {
        // Un eboot bien instalado es un SELF en disco: el núcleo lo
        // descifra en memoria al cargarlo. Exigir solo ELF era el bug
        // que reinstalaba el .pkg en cada JUGAR (reporte 21:46).
        val eboot = tmp.newFile("eboot.bin")
        writeValidSelf(eboot)
        assertTrue(GameLibraryRepository.isSelfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
        assertFalse(GameLibraryRepository.isElfFile(eboot))
    }

    @Test
    fun selfConSdkTypeDistintoDe1EsValido() {
        // R4-P0-5: el offset 8 es sdk_type, no header_type. Un SELF
        // con sdk_type 0x10 (juego con sysver de 3.60-3.61) y
        // header_type 1 en @10 es válido: el núcleo lo carga.
        val eboot = tmp.newFile("eboot.bin")
        writeValidSelf(eboot)
        assertTrue(GameLibraryRepository.isSelfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.OK,
            GameLibraryRepository.selfValidationReason(eboot),
        )
    }

    @Test
    fun selfConHeaderType0NoEsValido() {
        // R4-P0-5: header_type 0 es lo que el núcleo rechaza
        // (load_self.cpp: header_type != 1). El test viejo lo
        // escondía: su "SELF válido" dejaba bytes[10] en 0 y
        // ponía bytes[8] = 1, codificando el offset erróneo.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = validSelfBytes()
        putU16(bytes, 10, 0)
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.HEADER_TYPE,
            GameLibraryRepository.selfValidationReason(eboot),
        )
    }

    @Test
    fun magicOnlySelfIsNotValid() {
        // P0-1: el caso que borraba el .pkg. 4 bytes de magia, sin
        // tamaño ni cabecera, NO es un eboot válido.
        val eboot = tmp.newFile("eboot.bin")
        eboot.writeBytes(byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 0x00))
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun truncatedValidSelfIsNotValid() {
        // R4-P0-6: cabecera SELF perfecta pero archivo de 100 KB
        // cuya cabecera declara self_filesize de 1 MB: instalación
        // truncada a mitad. El piso de 1 MB anterior también lo
        // rechazaba, pero un truncado a 1,5 MB pasaba; ahora lo
        // rechaza el chequeo estructural, a cualquier tamaño.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = validSelfBytes(100_000)
        putU64(bytes, 32, 1_000_000)
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.TAMANO,
            GameLibraryRepository.selfValidationReason(eboot),
        )
    }

    @Test
    fun selfConElfOffsetFueraDeBoundsNoEsValido() {
        // R4-P0-6: elf_offset apunta fuera del archivo.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = validSelfBytes()
        putU64(bytes, 64, 2_000_000)
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.TAMANO,
            GameLibraryRepository.selfValidationReason(eboot),
        )
    }

    @Test
    fun selfConPhdrOffsetFueraDeBoundsNoEsValido() {
        // R4-P0-6: phdr_offset apunta fuera del archivo.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = validSelfBytes()
        putU64(bytes, 72, 2_000_000)
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.PHDR_FUERA,
            GameLibraryRepository.selfValidationReason(eboot),
        )
    }

    @Test
    fun selfValidoPequenoAceptado() {
        // R4-P0-6: un SELF válido de 8 KB (menos de 1 MB) se acepta:
        // el piso de 1 MB era inventado y lo rechazaba.
        val eboot = tmp.newFile("eboot.bin")
        writeValidSelf(eboot, 8192)
        assertTrue(GameLibraryRepository.isSelfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun elfValidoPequenoAceptado() {
        // R4-P0-6: un ELF válido de 512 bytes (menos de 1 MB) se
        // acepta: sin piso de tamaño, manda la estructura.
        val eboot = tmp.newFile("eboot.bin")
        writeValidElf(eboot, 512)
        assertTrue(GameLibraryRepository.isElfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun selfWithWrongVersionIsNotValid() {
        // El núcleo rechaza SELF con version distinta de 3.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = validSelfBytes()
        putU32(bytes, 4, 2)
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.VERSION,
            GameLibraryRepository.selfValidationReason(eboot),
        )
    }

    @Test
    fun elfWithPhdrOutsideFileIsNotValid() {
        // Magia ELF y tamaño bien, pero la tabla de segmentos apunta
        // fuera del archivo: eboot corrupto.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = validElfBytes()
        putU32(bytes, 28, 0x100000)
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isElfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
        assertEquals(
            GameLibraryRepository.EbootInvalidReason.PHDR_FUERA,
            GameLibraryRepository.elfValidationReason(eboot),
        )
    }

    @Test
    fun randomBytesAreNeitherElfNorSelf() {
        val eboot = tmp.newFile("eboot.bin")
        eboot.writeBytes(ByteArray(64) { (it * 37 % 251).toByte() })
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun shortSelfIsNotValid() {
        val short = tmp.newFile("corto.bin")
        short.writeBytes(byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte()))
        assertFalse(GameLibraryRepository.isSelfFile(short))
        assertFalse(GameLibraryRepository.isValidEbootFile(short))
    }

    @Test
    fun encryptedEbootIsNotDecrypted() {
        val eboot = tmp.newFile("eboot.bin")
        eboot.writeBytes(ByteArray(64) { (it * 37 % 251).toByte() })
        assertFalse(GameLibraryRepository.isElfFile(eboot))
    }

    @Test
    fun missingOrShortEbootIsNotDecrypted() {
        assertFalse(GameLibraryRepository.isElfFile(File(tmp.root, "no-existe.bin")))
        val short = tmp.newFile("corto.bin")
        short.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte()))
        assertFalse(GameLibraryRepository.isElfFile(short))
    }
}
