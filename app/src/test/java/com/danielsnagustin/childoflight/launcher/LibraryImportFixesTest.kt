package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.data.ImportTreeSearch
import com.danielsnagustin.childoflight.launcher.data.PkgHeaderParser
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreReader
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Genera un work.bin (512 B) con un zRIF válido: un .rif realista
 * (Content ID en 0x10 + clave) comprimido con deflate crudo, en
 * base64 y con el prefijo literal "KO" delante.
 *
 * El prefijo es literal, no parte del base64: verificado por
 * simulación (deflate crudo de un .rif siempre empieza en base64 por
 * "Y2Bk", sea cual sea el contenido del .rif), así que un zRIF real
 * es "KO" + base64(deflate(.rif)). El validador prueba las dos
 * lecturas (con y sin prefijo) y los dos inflados (crudo y zlib).
 */
fun buildZrifWorkBinBytes(contentId: String = "EP0001-PCSB00598_00-CHILDOFLIGHT0000"): ByteArray {
    val rif = ByteArray(512)
    byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x02).copyInto(rif)
    contentId.toByteArray(Charsets.US_ASCII).copyInto(rif, 16)
    byteArrayOf(0xDD.toByte(), 0xA3.toByte(), 0xED.toByte(), 0x58.toByte()).copyInto(rif, 0x40)
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(rif)
    deflater.finish()
    val compressed = ByteArray(600)
    val len = deflater.deflate(compressed)
    deflater.end()
    val text = "KO" + Base64.getEncoder().encodeToString(compressed.copyOf(len))
    val out = ByteArray(512)
    text.toByteArray(Charsets.US_ASCII).copyInto(out)
    return out
}

/** Header .pkg sintético con total_size (0x18), data_offset (0x20) y data_size (0x28). */
private fun pkgHeaderBytesFull(
    contentId: String,
    totalSize: Long,
    dataOffset: Long,
    dataSize: Long,
): ByteArray {
    val bytes = ByteArray(0x80)
    bytes[0] = 0x7F
    bytes[1] = 0x50
    bytes[2] = 0x4B
    bytes[3] = 0x47
    bytes[6] = 0x00
    bytes[7] = 0x02
    fun putU64(offset: Int, value: Long) {
        var v = value
        for (i in 7 downTo 0) {
            bytes[offset + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
    }
    putU64(0x18, totalSize)
    putU64(0x20, dataOffset)
    putU64(0x28, dataSize)
    contentId.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x30)
    return bytes
}

/**
 * Correcciones del reporte de errores 2026-11-08, área
 * biblioteca/importación. Puro (solo java.io.File / parsers): corre
 * en la JVM sin Android. Lo que necesita Context
 * (GameLibraryRepository: .pkg truncado, duplicados, borrado dual)
 * no se cubre aquí: lo verifica la CI con los tests de integración
 * y la prueba en el teléfono.
 */
class LibraryImportFixesTest {

    // --- P1-8: total_size / data_offset del header .pkg ---

    @Test
    fun `header lee total_size en 0x18 y data_offset en 0x20`() {
        val header = PkgHeaderParser.parse(
            pkgHeaderBytesFull("EP0001-PCSB00598_00-CHILDOFLIGHT0000", 1_141_710_576L, 0x80L, 1_141_710_496L),
        ).getOrThrow()
        assertEquals(1_141_710_576L, header.totalSizeBytes)
        assertEquals(0x80L, header.dataOffsetBytes)
        assertEquals(1_141_710_496L, header.dataSizeBytes)
        assertEquals(1_141_710_576L, header.declaredTotalSizeBytes)
    }

    @Test
    fun `declaredTotalSize usa data_offset mas data_size si no hay total_size`() {
        val header = PkgHeaderParser.parse(
            pkgHeaderBytesFull("EP0001-PCSB00598_00-CHILDOFLIGHT0000", 0L, 0x80L, 1000L),
        ).getOrThrow()
        assertEquals(0L, header.totalSizeBytes)
        assertEquals(1128L, header.declaredTotalSizeBytes)
    }

    @Test
    fun `declaredTotalSize es 0 si el header no informa tamanos`() {
        val header = PkgHeaderParser.parse(
            pkgHeaderBytesFull("EP0001-PCSB00598_00-CHILDOFLIGHT0000", 0L, 0L, 0L),
        ).getOrThrow()
        assertEquals(0L, header.declaredTotalSizeBytes)
    }

    // --- P1-6: zRIF verificado de verdad ---

    @Test
    fun `zRIF valido decodifica a 512 bytes con su Content ID`() {
        val text = buildZrifWorkBinBytes().toString(Charsets.US_ASCII).trim { it == '\u0000' }
        val rif = PkgHeaderParser.decodeZrifToRif(text).getOrThrow()
        assertEquals(512, rif.size)
        assertEquals(
            "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
            PkgHeaderParser.extractWorkBinContentId(rif),
        )
        assertEquals(
            "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
            PkgHeaderParser.extractZrifContentId(text),
        )
    }

    @Test
    fun `zRIF valido pasa validateWorkBin con el Content ID esperado`() {
        val result = PkgHeaderParser.validateWorkBin(
            buildZrifWorkBinBytes(),
            "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `zRIF de otro juego se rechaza aunque sea base64 valido`() {
        val otherGame = buildZrifWorkBinBytes("UP0001-PCSE00480_00-CHILDOFLIGHT0000")
        val result = PkgHeaderParser.validateWorkBin(
            otherGame,
            "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("otro juego"))
    }

    @Test
    fun `zRIF corto se rechaza`() {
        val bytes = ByteArray(512)
        "KOAA".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        val result = PkgHeaderParser.validateWorkBin(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("demasiado corta"))
    }

    @Test
    fun `zRIF que no infla a 512 bytes se rechaza`() {
        // "KO" + base64 válido que decodifica a ceros: no es deflate.
        val bytes = ByteArray(512)
        ("KO" + "A".repeat(100)).toByteArray(Charsets.US_ASCII).copyInto(bytes)
        val result = PkgHeaderParser.validateWorkBin(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("decodificar"))
    }

    // --- P2-C3: dos regiones en el árbol ---

    @Test
    fun `findTitleDirs encuentra las dos regiones`() {
        val root = Files.createTempDirectory("col-dos-regiones-").toFile()
        try {
            File(root, "PCSE00480/eboot.bin").apply { parentFile.mkdirs(); writeText("us") }
            File(root, "PCSB00598/eboot.bin").apply { parentFile.mkdirs(); writeText("eu") }
            val found = ImportTreeSearch.findTitleDirs(root)
            assertEquals(setOf(TitleId.US, TitleId.EU), found.map { it.first }.toSet())
            // findTitleDir sigue devolviendo una (compatibilidad).
            assertNotNull(ImportTreeSearch.findTitleDir(root))
        } finally {
            root.deleteRecursively()
        }
    }

    // --- P2-C2: el SFO manda sobre el nombre de la carpeta ---

    private fun titleDirWithSfo(root: File, folderName: String, sfoTitle: String?): File {
        val dir = File(root, folderName).apply { mkdirs() }
        File(dir, "eboot.bin").writeText("fake-eboot")
        if (sfoTitle != null) {
            val sceSys = File(dir, "sce_sys").apply { mkdirs() }
            File(sceSys, "param.sfo").writeBytes(
                buildSfoBytes(listOf("TITLE_ID" to sfoTitle, "TITLE" to "Child of Light")),
            )
        }
        return dir
    }

    @Test
    fun `SFO de otra region rechaza la carpeta`() {
        val root = Files.createTempDirectory("col-sfo-mismatch-").toFile()
        try {
            val dir = titleDirWithSfo(root, "PCSB00598", "PCSE00480")
            val result = ImportTreeSearch.validateTitleDirSfo(dir)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("PCSE00480"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `SFO de otro juego rechaza la carpeta`() {
        val root = Files.createTempDirectory("col-sfo-otro-").toFile()
        try {
            val dir = titleDirWithSfo(root, "PCSB00598", "PCSB00999")
            assertTrue(ImportTreeSearch.validateTitleDirSfo(dir).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `SFO que cuadra acepta la carpeta`() {
        val root = Files.createTempDirectory("col-sfo-ok-").toFile()
        try {
            val dir = titleDirWithSfo(root, "PCSB00598", "PCSB00598")
            assertEquals(TitleId.EU, ImportTreeSearch.validateTitleDirSfo(dir).getOrThrow())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sin SFO el nombre de la carpeta sigue valiendo`() {
        val root = Files.createTempDirectory("col-sin-sfo-").toFile()
        try {
            val dir = titleDirWithSfo(root, "PCSB00598", null)
            assertEquals(TitleId.EU, ImportTreeSearch.validateTitleDirSfo(dir).getOrThrow())
        } finally {
            root.deleteRecursively()
        }
    }

    // --- P2-C12: TROPHY.TRP sin distinguir mayúsculas ---

    @Test
    fun `gameBundledTrp encuentra SCE_SYS en mayusculas`() {
        val root = Files.createTempDirectory("col-trp-").toFile()
        try {
            val appDir = File(root, "PCSB00598").apply { mkdirs() }
            val trp = File(appDir, "SCE_SYS/TROPHY/trophy.trp").apply {
                parentFile.mkdirs()
                writeText("fake-trp")
            }
            assertEquals(trp, TrophyStoreReader.gameBundledTrp(appDir))
        } finally {
            root.deleteRecursively()
        }
    }

    // --- P2-C11: el marcador de firmware no basta solo ---

    @Test
    fun `marcador con version sin vs0 no es Ready`() {
        val root = Files.createTempDirectory("col-fw-marcador-").toFile()
        try {
            assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
            // Sin vita/vs0 no vacío, el marcador miente: no es Ready.
            assertFalse(FirmwareResolver.resolve(root).ready)
            assertEquals(FirmwareStatus.Missing, FirmwareResolver.resolve(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `marcador con version y vs0 si es Ready`() {
        val root = Files.createTempDirectory("col-fw-ok-").toFile()
        try {
            assertTrue(FirmwareResolver.markInstalled(root, "3.74"))
            File(File(root, "vita"), "vs0/sys").mkdirs()
            val status = FirmwareResolver.resolve(root)
            assertTrue(status is FirmwareStatus.Ready)
        } finally {
            root.deleteRecursively()
        }
    }

    // --- P2-A1: firmware legacy se migra ---

    @Test
    fun `vs0 en la ruta antigua se migra a vita vs0 y es Ready`() {
        val root = Files.createTempDirectory("col-fw-legacy-").toFile()
        try {
            File(File(root, "vs0"), "sys").mkdirs()
            // P2-33: resolve() es solo lectura y ya no migra: sin
            // migrar, la ruta antigua NO es Ready (el núcleo solo lee
            // vita/vs0). La migración es explícita y la hace
            // refreshStatus antes de leer el estado.
            assertEquals(FirmwareStatus.Missing, FirmwareResolver.resolve(root))
            assertTrue(FirmwareResolver.migrateLegacyVs0(root))
            val status = FirmwareResolver.resolve(root)
            assertTrue(status is FirmwareStatus.Ready)
            assertTrue(status.ready)
            assertFalse(File(root, "vs0").exists())
            assertTrue(File(File(root, "vita"), "vs0/sys").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `vs0 antiguo no pisa un vs0 del nucleo que ya existe`() {
        val root = Files.createTempDirectory("col-fw-ambos-").toFile()
        try {
            File(File(root, "vita"), "vs0/sys").mkdirs()
            File(File(root, "vs0"), "sys").mkdirs()
            assertTrue(FirmwareResolver.resolve(root).ready)
            // El antiguo se deja: lo borra deleteFirmware, no resolve().
            assertTrue(File(root, "vs0").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // --- P1-5(d): la migración fusiona el contenido no-juego ---

    @Test
    fun `migracion fusiona DLCs y saves del antiguo cuando el juego ya esta en el destino`() {
        val root = Files.createTempDirectory("col-merge-").toFile()
        try {
            val legacyDlc = File(root, "ux0/addcont/PCSB00598/CHILDOFLIGHTDLC1/data.bin")
            legacyDlc.parentFile.mkdirs()
            legacyDlc.writeText("dlc")
            val legacySave = File(root, "ux0/user/00/savedata/PCSB00598/slot0")
            legacySave.parentFile.mkdirs()
            legacySave.writeText("partida")
            val legacyLicense = File(root, "ux0/pkg/PCSB00598.work.bin")
            legacyLicense.parentFile.mkdirs()
            legacyLicense.writeText("licencia")
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")
            // El .pkg del juego también en el destino: sin eso sería Conflict por el juego.
            File(root, "vita/ux0/pkg/PCSB00598.pkg").apply {
                parentFile.mkdirs()
                writeText("pkg")
            }

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.AlreadyMigrated, result)
            assertTrue(File(root, "vita/ux0/addcont/PCSB00598/CHILDOFLIGHTDLC1/data.bin").isFile)
            assertTrue(File(root, "vita/ux0/user/00/savedata/PCSB00598/slot0").isFile)
            assertTrue(File(root, "vita/ux0/pkg/PCSB00598.work.bin").isFile)
            assertFalse(legacyDlc.exists())
            assertFalse(legacySave.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `migracion no mezcla un DLC que ya existe en el destino`() {
        val root = Files.createTempDirectory("col-merge-skip-").toFile()
        try {
            val legacyDlc = File(root, "ux0/addcont/PCSB00598/CHILDOFLIGHTDLC1/data.bin")
            legacyDlc.parentFile.mkdirs()
            legacyDlc.writeText("viejo")
            val destDlc = File(root, "vita/ux0/addcont/PCSB00598/CHILDOFLIGHTDLC1/data.bin")
            destDlc.parentFile.mkdirs()
            destDlc.writeText("nuevo")
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.AlreadyMigrated, result)
            assertEquals("nuevo", destDlc.readText())
            assertEquals("viejo", legacyDlc.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
