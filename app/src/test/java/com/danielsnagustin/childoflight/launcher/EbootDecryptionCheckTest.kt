package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import java.io.File
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
 * destruía la única vía de recuperación. Ahora se exige tamaño
 * mínimo (1 MB) y cabecera estructural: SELF con version 3 y
 * header_type 1 (lo mismo que comprueba el núcleo al cargar,
 * load_self.cpp:512-520); ELF32 con la tabla de segmentos dentro
 * del archivo. Los archivos falsos de estos tests son, por tanto,
 * de 1 MB con cabecera válida.
 */
class EbootDecryptionCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** SELF válido: magia + version 3 + header_type 1, 1 MB. */
    private fun writeValidSelf(file: File) {
        val bytes = ByteArray(1_000_000)
        bytes[0] = 'S'.code.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'C'.code.toByte()
        bytes[3] = 0
        bytes[4] = 3
        bytes[8] = 1
        file.writeBytes(bytes)
    }

    /** ELF32 válido: magia + clase 32 + un segmento dentro del archivo, 1 MB. */
    private fun writeValidElf(file: File) {
        val bytes = ByteArray(1_000_000)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 1
        bytes[28] = 52
        bytes[42] = 32
        bytes[44] = 1
        file.writeBytes(bytes)
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
        // Cabecera SELF perfecta pero archivo de 100 KB: instalación
        // truncada a mitad. No pasa.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = ByteArray(100_000)
        bytes[0] = 'S'.code.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'C'.code.toByte()
        bytes[4] = 3
        bytes[8] = 1
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun selfWithWrongVersionIsNotValid() {
        // El núcleo rechaza SELF con version distinta de 3.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = ByteArray(1_000_000)
        bytes[0] = 'S'.code.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'C'.code.toByte()
        bytes[4] = 2
        bytes[8] = 1
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isSelfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun elfWithPhdrOutsideFileIsNotValid() {
        // Magia ELF y tamaño bien, pero la tabla de segmentos apunta
        // fuera del archivo: eboot corrupto.
        val eboot = tmp.newFile("eboot.bin")
        val bytes = ByteArray(1_000_000)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 1
        bytes[28] = 0
        bytes[29] = 0
        bytes[30] = 0x10
        bytes[31] = 0
        bytes[42] = 32
        bytes[44] = 1
        eboot.writeBytes(bytes)
        assertFalse(GameLibraryRepository.isElfFile(eboot))
        assertFalse(GameLibraryRepository.isValidEbootFile(eboot))
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
