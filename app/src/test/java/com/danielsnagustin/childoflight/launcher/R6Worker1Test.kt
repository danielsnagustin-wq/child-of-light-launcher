package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreReader
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests de la lógica pura nueva de la ronda 6, trabajador 1
 * (flujo A1 + trofeos + config). Archivo propio para no colisionar
 * con otros trabajadores en archivos de test compartidos. Puro JVM.
 *
 * R6-P0: el reintento de loadGame tras -3 está acotado a UNA vez por
 * lanzamiento y solo cuando hay .pkg guardado (Vita3KLauncher.
 * shouldReinstallAndRetryLoadGame). El bucle completo (reinstalar +
 * segundo loadGame + borrado del .pkg tras loadGame == 0) no se
 * puede probar en JVM: necesita el núcleo nativo y la app corriendo;
 * es solo-teléfono (ver informe de la ronda).
 */
class R6Worker1Test {

    @Test
    fun reintento_trasMenos3ConPkg_ySinReintentoPrevio() {
        assertTrue(
            Vita3KLauncher.shouldReinstallAndRetryLoadGame(
                loadRc = Vita3KLauncher.LOAD_GAME_NOT_INSTALLED,
                storedPkgPresent = true,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun reintento_soloUnaVezPorLanzamiento() {
        // Un .pkg que no es el juego (un update guardado como juego)
        // no entra en bucle: tras el primer reintento, no más.
        assertFalse(
            Vita3KLauncher.shouldReinstallAndRetryLoadGame(
                loadRc = Vita3KLauncher.LOAD_GAME_NOT_INSTALLED,
                storedPkgPresent = true,
                alreadyRetried = true,
            ),
        )
    }

    @Test
    fun reintento_sinPkgGuardado_noReinstala() {
        assertFalse(
            Vita3KLauncher.shouldReinstallAndRetryLoadGame(
                loadRc = Vita3KLauncher.LOAD_GAME_NOT_INSTALLED,
                storedPkgPresent = false,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun reintento_sinNucleo_noReinstala() {
        // -1 (sin núcleo): instalar no cambia nada.
        assertFalse(
            Vita3KLauncher.shouldReinstallAndRetryLoadGame(
                loadRc = Vita3kCoreBridge.NOT_IMPLEMENTED,
                storedPkgPresent = true,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun reintento_sinSesion_noReinstala() {
        // -2 (sin sesión): instalar no cambia nada.
        assertFalse(
            Vita3KLauncher.shouldReinstallAndRetryLoadGame(
                loadRc = -2,
                storedPkgPresent = true,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun reintento_conLoadGameOk_noReinstala() {
        assertFalse(
            Vita3KLauncher.shouldReinstallAndRetryLoadGame(
                loadRc = Vita3kCoreBridge.OK,
                storedPkgPresent = true,
                alreadyRetried = false,
            ),
        )
    }

    // --- R6-P1-18: TrophyStoreReader.scan mira trophy/data/<NPCOMMID>/ ---

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun vitaRootWithTrophyData(): File {
        // resolveUx0Dir es vita/ux0 cuando existe (isMigrated).
        val ux0 = File(tmp.root, "vita/ux0").apply { mkdirs() }
        File(ux0, "user/00/trophy/data").mkdirs()
        return tmp.root
    }

    @Test
    fun trophyScan_sinCarpetaData_noExiste() {
        val scan = TrophyStoreReader.scan(tmp.root, "PCSB00598")
        assertFalse(scan.trophyDirExists)
        assertFalse(scan.trpPresent)
    }

    @Test
    fun trophyScan_conTropconf_instaladoLoVe() {
        val root = vitaRootWithTrophyData()
        val dir = File(root, "vita/ux0/user/00/trophy/data/NPWR00001_00").apply { mkdirs() }
        File(dir, "TROPCONF.SFM").writeText("<trophyconf><npcommid>NPWR00001_00</npcommid></trophyconf>")
        File(dir, "TROPUSR.DAT").writeBytes(ByteArray(16))
        val scan = TrophyStoreReader.scan(root, "PCSB00598")
        assertTrue(scan.trophyDirExists)
        assertTrue(scan.trpPresent)
        assertTrue(scan.trpSizeBytes > 0)
        assertTrue(scan.note.contains("NPWR00001_00"))
    }

    @Test
    fun trophyScan_sinTropconf_noPresente() {
        // La carpeta data/ existe pero sin TROPCONF.SFM: datos no
        // instalados todavía (no se inventa un TRP).
        val root = vitaRootWithTrophyData()
        File(root, "vita/ux0/user/00/trophy/data/NPWR00001_00").mkdirs()
        val scan = TrophyStoreReader.scan(root, "PCSB00598")
        assertTrue(scan.trophyDirExists)
        assertFalse(scan.trpPresent)
    }

    @Test
    fun trophyScan_rutaVieja_noVale() {
        // trophy/<TITLE>/TROPHY.TRP ya no se busca: con solo la ruta
        // vieja, el escaneo dice que no hay datos instalados.
        val root = vitaRootWithTrophyData()
        val old = File(root, "vita/ux0/user/00/trophy/PCSB00598").apply { mkdirs() }
        File(old, "TROPHY.TRP").writeBytes(ByteArray(64))
        val scan = TrophyStoreReader.scan(root, "PCSB00598")
        assertFalse(scan.trpPresent)
    }

    // --- R6-P2-29: writeConfigIfChangedDetailed ---

    @Test
    fun configWrite_informeIdem_noEscribe() {
        val file = tmp.newFile("config.yml")
        file.writeText("v-sync: false\n")
        val report = com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator.writeConfigIfChangedDetailed(file, "v-sync: false\n")
        assertFalse(report.wrote)
        assertFalse(report.previousReadFailed)
    }

    @Test
    fun configWrite_informeLecturaFallida_escribeYMarca() {
        // Bytes UTF-8 inválidos: readText() lanza MalformedInputException
        // (determinista en JVM; setReadable(false) no vale: root lo
        // ignora). El contenido previo se trata como desconocido, se
        // reescribe entero y el informe lo marca.
        val file = tmp.newFile("config.yml")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x0A))
        val report = com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator.writeConfigIfChangedDetailed(file, "v-sync: false\n")
        assertTrue(report.wrote)
        assertTrue(report.previousReadFailed)
        org.junit.Assert.assertEquals("v-sync: false\n", file.readText())
    }

    @Test
    fun configWrite_directorio_errorExplicito() {
        val dir = tmp.newFolder("config.yml")
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator.writeConfigIfChangedDetailed(dir, "v-sync: false\n")
        }
    }

    // --- R6-P2-06: versionBelowMinimum ---

    @Test
    fun versionBelowMinimum_casos() {
        val f = com.danielsnagustin.childoflight.launcher.data.FirmwareResolver::versionBelowMinimum
        assertTrue(f(null))
        assertTrue(f(""))
        assertTrue(f("3.10"))
        assertTrue(f("3.14"))
        assertFalse(f("3.15"))
        assertFalse(f("3.60"))
        assertFalse(f("3.73"))
        assertFalse(f("3.74"))
        // No interpretable: aviso (no se sabe si cumple el mínimo).
        assertTrue(f("desconocida"))
    }

    // --- R6-P2-08(b): mergeDlcLists deduplica almacenados entre sí ---

    @Test
    fun mergeDlcLists_dedupicaAlmacenadosEntreSi() {
        val extracted = listOf(
            com.danielsnagustin.childoflight.launcher.model.DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 100L, contentKey = "CHILDOFLIGHTDLC1"),
        )
        val stored = listOf(
            com.danielsnagustin.childoflight.launcher.model.DlcInfo("abc123", "PCSB00598", 50L, isPkg = true, contentKey = "CHILDOFLIGHTDLC2"),
            com.danielsnagustin.childoflight.launcher.model.DlcInfo("ABC123", "PCSB00598", 50L, isPkg = true, contentKey = "childoflightdlc2"),
        )
        val merged = com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.mergeDlcLists(extracted, stored)
        // 1 extraído + 1 almacenado: el segundo .pkg es el mismo DLC
        // con distinta capitalización y se queda fuera.
        assertEquals(2, merged.size)
        assertEquals("CHILDOFLIGHTDLC2", merged[1].contentKey)
    }

    @Test
    fun mergeDlcLists_extraidoMandaSobreGuardado() {
        val extracted = listOf(
            com.danielsnagustin.childoflight.launcher.model.DlcInfo("CHILDOFLIGHTDLC1", "PCSB00598", 100L, contentKey = "CHILDOFLIGHTDLC1"),
        )
        val stored = listOf(
            com.danielsnagustin.childoflight.launcher.model.DlcInfo("hash", "PCSB00598", 50L, isPkg = true, contentKey = "CHILDOFLIGHTDLC1"),
        )
        val merged = com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.mergeDlcLists(extracted, stored)
        assertEquals(1, merged.size)
        assertFalse(merged[0].isPkg)
    }

    // --- R6-P2-01 / R6-P2-08(c): pkgInstallNeededBytes y pkgCopyIncomplete ---

    @Test
    fun pkgInstallNeededBytes_esElDoble() {
        assertEquals(
            2_283_421_152L,
            com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.pkgInstallNeededBytes(1_141_710_576L),
        )
        assertEquals(
            100_000_000L,
            com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.pkgInstallNeededBytes(50_000_000L),
        )
    }

    @Test
    fun pkgCopyIncomplete_casos() {
        val f = com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository::pkgCopyIncomplete
        // Declarado manda cuando lo hay.
        assertTrue(f(100L, 200L, -1L))
        assertFalse(f(200L, 200L, -1L))
        // Declarado 0: manda el tamaño de origen si se conoce.
        assertTrue(f(50L, 0L, 100L))
        assertFalse(f(100L, 0L, 100L))
        // Sin tamaño conocido: no se puede saber.
        assertFalse(f(50L, 0L, -1L))
    }

    // --- R6-P2-02: índice zip por directorio central ---

    @Test
    fun zipCentralDirectory_listaEntradasDeZipReal() {
        // Un zip REAL (java.util.zip): la pasada barata debe listar
        // sus entradas sin extraerlo.
        val zipFile = tmp.newFile("test.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("PCSB00598/eboot.bin"))
            zip.write(ByteArray(10))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("PCSB00598/sce_sys/param.sfo"))
            zip.write(ByteArray(10))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("PCSB00598/addcont/CHILDOFLIGHTDLC1/data.bin"))
            zip.write(ByteArray(10))
            zip.closeEntry()
        }
        val bytes = zipFile.readBytes()
        val tail = bytes.takeLast(65_557).toByteArray()
        val eocd = com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.findZipEocd(tail)
        org.junit.Assert.assertNotNull(eocd)
        val (count, cdSize, cdOffset) = eocd!!
        assertEquals(3, count)
        val cdBytes = bytes.copyOfRange(cdOffset.toInt(), (cdOffset + cdSize).toInt())
        val names = com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.parseZipCentralDirectoryNames(cdBytes, count)
        org.junit.Assert.assertNotNull(names)
        assertEquals(
            listOf("PCSB00598/eboot.bin", "PCSB00598/sce_sys/param.sfo", "PCSB00598/addcont/CHILDOFLIGHTDLC1/data.bin"),
            names,
        )
        assertEquals(
            listOf(com.danielsnagustin.childoflight.launcher.model.TitleId.EU),
            com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.zipTitleDirNames(names!!),
        )
        assertTrue(com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.zipHasEboot(names))
        assertEquals(
            listOf("CHILDOFLIGHTDLC1"),
            com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.zipAddcontDlcIds(names),
        )
    }

    @Test
    fun zipEocd_sinEocd_devuelveNull() {
        assertEquals(null, com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.findZipEocd(ByteArray(100)))
    }

    @Test
    fun zipEocd_zip64_devuelveNull() {
        // EOCD con nº de entradas 0xFFFF: ZIP64, la pasada barata no
        // se puede hacer y el llamador usa el comportamiento actual.
        val eocd = ByteArray(22)
        eocd[0] = 0x50
        eocd[1] = 0x4b
        eocd[2] = 0x05
        eocd[3] = 0x06
        eocd[10] = 0xFF.toByte()
        eocd[11] = 0xFF.toByte()
        assertEquals(null, com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.findZipEocd(eocd))
    }

    @Test
    fun zipCentralDirectory_bloqueRoto_devuelveNull() {
        assertEquals(
            null,
            com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.parseZipCentralDirectoryNames(ByteArray(100), 1),
        )
    }

    @Test
    fun zipTitleDirNames_juegoPlano_noDeterminaTitulo() {
        // Juego plano (eboot en la raíz, Title ID en el SFO): por
        // nombres no se puede determinar el título y el llamador deja
        // el comportamiento actual.
        val names = listOf("eboot.bin", "sce_sys/param.sfo", "data/file.bin")
        assertEquals(emptyList<com.danielsnagustin.childoflight.launcher.model.TitleId>(), com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.zipTitleDirNames(names))
        assertTrue(com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.zipHasEboot(names))
    }

    @Test
    fun zipTitleDirNames_dosRegiones_lasVe() {
        val names = listOf("PCSB00598/eboot.bin", "PCSE00480/eboot.bin")
        assertEquals(
            listOf(
                com.danielsnagustin.childoflight.launcher.model.TitleId.EU,
                com.danielsnagustin.childoflight.launcher.model.TitleId.US,
            ),
            com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.zipTitleDirNames(names),
        )
    }
}
