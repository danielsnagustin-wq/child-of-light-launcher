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
 */
class EbootDecryptionCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun elfEbootIsDecrypted() {
        val eboot = tmp.newFile("eboot.bin")
        eboot.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02, 0x01, 0x01))
        assertTrue(GameLibraryRepository.isElfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
    }

    @Test
    fun selfEbootIsValid() {
        // Un eboot bien instalado es un SELF en disco: el núcleo lo
        // descifra en memoria al cargarlo. Exigir solo ELF era el bug
        // que reinstalaba el .pkg en cada JUGAR (reporte 21:46).
        val eboot = tmp.newFile("eboot.bin")
        eboot.writeBytes(byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 0x00, 0x01, 0x02))
        assertTrue(GameLibraryRepository.isSelfFile(eboot))
        assertTrue(GameLibraryRepository.isValidEbootFile(eboot))
        assertFalse(GameLibraryRepository.isElfFile(eboot))
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
