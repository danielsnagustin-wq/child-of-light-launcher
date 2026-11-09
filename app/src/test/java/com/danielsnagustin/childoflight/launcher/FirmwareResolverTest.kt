package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameLauncher
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests del firmware interno (puros JVM, sin Android).
 *
 * Reglas que fijan:
 * - El firmware extraído del núcleo vive en `vita/vs0` (vita_fs),
 *   no en `vitaRoot/vs0` (ruta antigua, incorrecta para el núcleo).
 * - Guardar el PUP NO es instalarlo: [FirmwareStatus.Saved] no es
 *   `ready` y nunca habilita el núcleo embebido.
 * - Solo un marcador con VERSIÓN (escrito tras un installFirmware
 *   con éxito) cuenta como instalado; un marcador con solo el nombre
 *   del archivo no vale.
 * - Ninguna frase visible manda al usuario a buscar firmware por
 *   internet ni menciona Vita3K en la interfaz.
 */
class FirmwareResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pupBytes(): ByteArray =
        byteArrayOf(0x53, 0x43, 0x45, 0x55, 0x46, 0x00, 0x01, 0x02) // "SCEUF..."

    private fun writePup(root: File) {
        val dir = File(root, "firmware").apply { mkdirs() }
        File(dir, "PSVUPDAT.PUP").writeBytes(pupBytes())
    }

    @Test
    fun `vs0 extraido en vita es Ready`() {
        val root = tmp.newFolder("vitaRoot")
        File(File(root, "vita"), "vs0/sys").mkdirs()
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Ready)
        assertTrue(status.ready)
    }

    @Test
    fun `resolve no migra la ruta antigua, la migracion es explicita`() {
        // P2-33: resolve() es una lectura: un vs0 en la ruta antigua
        // ya no se migra como efecto secundario ni es Ready. La
        // migración la hace el ViewModel al refrescar el estado (aquí
        // se llama directamente a migrateLegacyVs0) y entonces sí es
        // el firmware que el núcleo ve.
        val root = tmp.newFolder("vitaRoot")
        File(File(root, "vs0"), "sys").mkdirs()
        assertEquals(FirmwareStatus.Missing, FirmwareResolver.resolve(root))
        assertTrue(File(root, "vs0").exists())
        assertTrue(FirmwareResolver.migrateLegacyVs0(root))
        assertFalse(File(root, "vs0").exists())
        assertTrue(File(File(root, "vita"), "vs0/sys").isDirectory)
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Ready)
        assertTrue(status.ready)
    }

    @Test
    fun `PUP guardado igual al instalado no es pendiente`() {
        // P2-29: el PUP guardado es el mismo que se instaló (mismo
        // tamaño que el registrado en el marcador): no hay nada
        // pendiente, no se muestra el botón de instalar.
        val root = tmp.newFolder("vitaRoot")
        File(File(root, "vita"), "vs0/sys").mkdirs()
        writePup(root)
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        assertEquals(8L, FirmwareResolver.installedPupSize(root))
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Ready)
        assertFalse((status as FirmwareStatus.Ready).savedPupPending)
    }

    @Test
    fun `PUP guardado distinto al instalado es pendiente`() {
        // P2-29: llega otro PUP (distinto tamaño que el instalado):
        // es una instalación pendiente y debe verse su botón.
        val root = tmp.newFolder("vitaRoot")
        File(File(root, "vita"), "vs0/sys").mkdirs()
        writePup(root)
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        File(File(root, "firmware"), "PSVUPDAT.PUP").writeBytes(ByteArray(9) { 0x41 })
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Ready)
        assertTrue((status as FirmwareStatus.Ready).savedPupPending)
    }

    @Test
    fun `PUP guardado con marcador antiguo sin tamano es pendiente`() {
        // P2-29: el marcador antiguo no registra el tamaño del PUP:
        // no se puede asegurar que el guardado sea el instalado, así
        // que se muestra como pendiente (reinstalar la misma versión
        // es inofensivo; esconder una instalación pendiente, no).
        val root = tmp.newFolder("vitaRoot")
        File(File(root, "vita"), "vs0/sys").mkdirs()
        writePup(root)
        File(File(root, "firmware"), "installed.json").writeText("{\"version\":\"3.74\",\"file\":\"PSVUPDAT.PUP\"}")
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Ready)
        assertTrue((status as FirmwareStatus.Ready).savedPupPending)
    }

    @Test
    fun `vs0 vacio no es Ready`() {
        val root = tmp.newFolder("vitaRoot")
        File(File(root, "vita"), "vs0").mkdirs()
        assertEquals(FirmwareStatus.Missing, FirmwareResolver.resolve(root))
    }

    @Test
    fun `PUP guardado es Saved no Ready`() {
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Saved)
        assertFalse(status.ready)
    }

    @Test
    fun `marcador antiguo con solo el nombre no es Ready`() {
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        File(File(root, "firmware"), "installed.json").writeText("PSVUPDAT.PUP")
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Saved)
        assertFalse(status.ready)
    }

    @Test
    fun `markInstalled deja Ready con version y vs0`() {
        val root = tmp.newFolder("vitaRoot")
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        assertEquals("3.74", FirmwareResolver.installedVersion(root))
        // P2-C11 (2026-11-08): el marcador solo vale si vita/vs0
        // sigue no vacío; sin vs0 el marcador miente y no es Ready.
        File(File(root, "vita"), "vs0/sys").mkdirs()
        val status = FirmwareResolver.resolve(root)
        assertTrue(status is FirmwareStatus.Ready)
        assertTrue(FirmwareResolver.label(status).contains("3.74"))
    }

    @Test
    fun `markInstalled rechaza version vacia`() {
        val root = tmp.newFolder("vitaRoot")
        assertFalse(FirmwareResolver.markInstalled(root, "  "))
        assertEquals(FirmwareStatus.Missing, FirmwareResolver.resolve(root))
    }

    @Test
    fun `deleteFirmware borra PUP marcador y vs0`() {
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        FirmwareResolver.markInstalled(root, "3.74")
        File(File(root, "vita"), "vs0/sys").mkdirs()
        assertTrue(FirmwareResolver.resolve(root).ready)
        assertTrue(FirmwareResolver.deleteFirmware(root))
        assertFalse(File(root, "firmware/PSVUPDAT.PUP").exists())
        assertFalse(File(root, "firmware/installed.json").exists())
        assertFalse(File(File(root, "vita"), "vs0").exists())
        assertEquals(FirmwareStatus.Missing, FirmwareResolver.resolve(root))
        assertFalse(FirmwareResolver.deleteFirmware(root))
    }

    @Test
    fun `magia SCEUF valida el PUP`() {
        assertTrue(FirmwareResolver.isPupBytes(pupBytes()))
        assertFalse(FirmwareResolver.isPupBytes(byteArrayOf(0x58, 0x58, 0x58, 0x58, 0x58)))
        assertFalse(FirmwareResolver.isPupBytes(byteArrayOf(0x53, 0x43)))
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        assertTrue(FirmwareResolver.isValidPupFile(File(root, "firmware/PSVUPDAT.PUP")))
        val empty = File(root, "vacio.pup").apply { writeBytes(byteArrayOf()) }
        assertFalse(FirmwareResolver.isValidPupFile(empty))
        assertFalse(FirmwareResolver.isValidPupFile(File(root, "no-existe.pup")))
    }

    @Test
    fun `frases visibles no mandan a internet ni mencionan Vita3K`() {
        val noFirmware = FirmwareResolver.NO_FIRMWARE_MESSAGE
        assertFalse(noFirmware.contains("internet", ignoreCase = true))
        assertFalse(noFirmware.contains("busca", ignoreCase = true))
        assertFalse(noFirmware.contains("Vita3K"))
        assertFalse(noFirmware.contains("sube", ignoreCase = true))
        // El usuario intentó mandar el PUP por el chat (>100 MB, no
        // le dejó): la app debe dejar claro que no tiene que enviarlo
        // a ningún sitio; la importación es 100% en el dispositivo.
        assertTrue(noFirmware.contains("No tienes que enviar"))
        assertFalse(FirmwareResolver.label(FirmwareStatus.Missing).contains("internet", ignoreCase = true))
        assertFalse(FirmwareResolver.label(FirmwareStatus.Missing).contains("Vita3K"))
        assertFalse(FirmwareResolver.label(FirmwareStatus.Saved("PSVUPDAT.PUP guardado")).contains("Vita3K"))
    }

    @Test
    fun `frases de firmware del lanzador no mencionan Vita3K`() {
        // Constantes del companion (sin instanciar el lanzador).
        assertFalse(Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL.contains("Vita3K"))
        assertFalse(Vita3KLauncher.FIRMWARE_BLOCKED_EMBEDDED.contains("Vita3K"))
        // R2-P1-01: la ruta es Importar → Firmware (la sección
        // "Avanzado" ya no existe en Ajustes).
        assertTrue(Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL.contains("Importar → Firmware"))
        assertTrue(Vita3KLauncher.FIRMWARE_BLOCKED_EMBEDDED.contains("Importar → Firmware"))
        assertFalse(Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL.contains("Avanzado"))
        assertFalse(Vita3KLauncher.FIRMWARE_BLOCKED_EMBEDDED.contains("Avanzado"))
    }

    @Test
    fun `PUP guardado se borra cuando el firmware ya esta instalado`() {
        // Peso del juego (2026-11-09): PUP guardado (133 MB) + vs0
        // instalado = firmware por duplicado. Con marcador (instalado),
        // el PUP sobra: se borra y el marcador se conserva.
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        assertTrue(FirmwareResolver.deleteSavedPupIfInstalled(root))
        assertFalse(File(File(root, "firmware"), "PSVUPDAT.PUP").exists())
        assertTrue(File(File(root, "firmware"), "installed.json").exists())
    }

    @Test
    fun `PUP guardado NO se borra sin marcador de instalado`() {
        // Sin marcador el firmware solo está guardado, sin instalar:
        // el PUP es la única vía para instalarlo y debe quedarse.
        val root = tmp.newFolder("vitaRoot")
        writePup(root)
        assertFalse(FirmwareResolver.deleteSavedPupIfInstalled(root))
        assertTrue(File(File(root, "firmware"), "PSVUPDAT.PUP").exists())
    }

    @Test
    fun `sin PUP guardado no hay nada que borrar`() {
        val root = tmp.newFolder("vitaRoot")
        assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
        assertFalse(FirmwareResolver.deleteSavedPupIfInstalled(root))
    }
}
