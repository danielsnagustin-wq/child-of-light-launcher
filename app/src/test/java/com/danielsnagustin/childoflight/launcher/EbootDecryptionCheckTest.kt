package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Chequeo de eboot descifrado (bug -3, 2026-11-08): tras instalar
 * un .pkg, el núcleo descifra el PFS (install_pkg de upstream:
 * extrae cifrado y luego F00D lo pasa a <TITLE>_dec y lo renombra).
 * Si ese proceso se interrumpe (app cerrada a mitad: 1,14 GB),
 * queda eboot.bin en disco pero CIFRADO: el escáner lo ve como
 * "extraído" y el núcleo no puede arrancar el juego. Un ELF de Vita
 * empieza por 0x7F 'E' 'L' 'F'; el contenido cifrado no. Puro JVM.
 */
class EbootDecryptionCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun elfEbootIsDecrypted() {
        val eboot = tmp.newFile("eboot.bin")
        eboot.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02, 0x01, 0x01))
        assertTrue(GameLibraryRepository.isElfFile(eboot))
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
