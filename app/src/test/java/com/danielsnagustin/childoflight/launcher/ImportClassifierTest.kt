package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.ImportFileGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guía de importación (.bin / paquetes / carpetas).
 * Pura, sin Context: valida que el usuario reciba el mensaje correcto
 * en español cuando elige un archivo que no es el paquete completo.
 */
class ImportClassifierTest {

    @Test
    fun extensionIsCaseInsensitive() {
        assertEquals("vpk", ImportFileGuide.extensionOf("Juego.VPK"))
        assertEquals("bin", ImportFileGuide.extensionOf("EBOOT.BIN"))
        assertEquals("zip", ImportFileGuide.extensionOf("child-of-light.zip"))
        assertEquals("", ImportFileGuide.extensionOf("sin-extension"))
    }

    @Test
    fun bareBinIsDetected() {
        assertTrue(ImportFileGuide.isBareBin("eboot.bin"))
        assertTrue(ImportFileGuide.isBareBin("EBOOT.BIN"))
        assertFalse(ImportFileGuide.isBareBin("juego.zip"))
        assertFalse(ImportFileGuide.isBareBin("juego.vpk"))
        assertFalse(ImportFileGuide.isBareBin("juego.pkg"))
    }

    @Test
    fun packagesAreAccepted() {
        assertTrue(ImportFileGuide.isPackage("juego.zip"))
        assertTrue(ImportFileGuide.isPackage("juego.vpk"))
        assertTrue(ImportFileGuide.isPackage("juego.pkg"))
        assertFalse(ImportFileGuide.isPackage("eboot.bin"))
        assertFalse(ImportFileGuide.isPackage("foto.png"))
    }

    @Test
    fun bareBinHelpForGameMentionsFullPackageAndFolder() {
        val help = ImportFileGuide.bareBinHelp("juego")
        assertTrue(help.contains(".zip"))
        assertTrue(help.contains(".vpk"))
        assertTrue(help.contains("PCSE00480"))
        assertTrue(help.contains("PCSB00598"))
        assertTrue(help.contains("eboot.bin"))
        assertTrue(help.contains("carpeta"))
    }

    @Test
    fun bareBinHelpForDlcMentionsAddcont() {
        val help = ImportFileGuide.bareBinHelp("DLC")
        assertTrue(help.contains("addcont"))
        assertTrue(help.contains("PCSE00480"))
    }

    @Test
    fun bareBinHelpForUpdateMentionsPackage() {
        val help = ImportFileGuide.bareBinHelp("update")
        assertTrue(help.contains(".zip"))
        assertTrue(help.contains("PCSE00480"))
    }

    @Test
    fun pkgMagicIsDetected() {
        // Magia "\x7FPKG": un .pkg por hash sin extensión en el nombre se
        // reconoce igual que uno con .pkg.
        assertTrue(ImportFileGuide.looksLikePkgMagic(byteArrayOf(0x7F, 0x50, 0x4B, 0x47)))
        assertFalse(ImportFileGuide.looksLikePkgMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(ImportFileGuide.looksLikePkgMagic(byteArrayOf(0x7F, 0x50)))
        assertFalse(ImportFileGuide.looksLikePkgMagic(byteArrayOf()))
    }

    @Test
    fun hashNamedPkgIsStillAPackage() {
        // El .pkg del usuario: nombre hash, con extensión .pkg.
        assertTrue(ImportFileGuide.isPackage("EAMfBniHkRQwXxYyZz1234567890abcdefoldLwRkHA.pkg"))
        assertEquals("pkg", ImportFileGuide.extensionOf("EAMfBniHkRQwXxYyZz1234567890abcdefoldLwRkHA.pkg"))
    }
}
