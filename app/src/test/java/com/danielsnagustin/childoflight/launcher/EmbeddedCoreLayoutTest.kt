package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests de la migración de layout de Etapa 1 a Etapa 2 (ux0 -> vita/ux0).
 * Puro java.io.File: corre en la JVM sin Android.
 */
class EmbeddedCoreLayoutTest {

    private fun tempRoot(): File = Files.createTempDirectory("col-etapa2-layout-").toFile()

    @Test
    fun `sin nada que migrar devuelve NothingToMigrate`() {
        val root = tempRoot()
        try {
            assertEquals(
                EmbeddedCoreLayout.MigrationResult.NothingToMigrate,
                EmbeddedCoreLayout.migrateToEmbeddedLayout(root),
            )
            assertFalse(EmbeddedCoreLayout.isMigrated(root))
            assertFalse(EmbeddedCoreLayout.needsMigration(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `migra ux0 antiguo a vita ux0 y no deja el original`() {
        val root = tempRoot()
        try {
            val eboot = File(root, "ux0/app/PCSB00598/eboot.bin")
            eboot.parentFile.mkdirs()
            eboot.writeText("falso eboot")
            File(root, "ux0/user/00/savedata/PCSB00598").mkdirs()

            assertTrue(EmbeddedCoreLayout.needsMigration(root))
            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.Migrated(1), result)
            assertTrue(File(root, "vita/ux0/app/PCSB00598/eboot.bin").isFile)
            assertTrue(File(root, "vita/ux0/user/00/savedata/PCSB00598").isDirectory)
            assertFalse(File(root, "ux0").exists())
            assertTrue(EmbeddedCoreLayout.isMigrated(root))
            assertFalse(EmbeddedCoreLayout.needsMigration(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `migrar dos veces la segunda es AlreadyMigrated`() {
        val root = tempRoot()
        try {
            val eboot = File(root, "ux0/app/PCSB00598/eboot.bin")
            eboot.parentFile.mkdirs()
            eboot.writeText("falso eboot")
            EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(
                EmbeddedCoreLayout.MigrationResult.AlreadyMigrated,
                EmbeddedCoreLayout.migrateToEmbeddedLayout(root),
            )
            assertTrue(File(root, "vita/ux0/app/PCSB00598/eboot.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `conflicto si existen ux0 antiguo y vita ux0 no mueve nada`() {
        val root = tempRoot()
        try {
            val legacy = File(root, "ux0/app/PCSB00598/eboot.bin")
            legacy.parentFile.mkdirs()
            legacy.writeText("antiguo")
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertTrue(result is EmbeddedCoreLayout.MigrationResult.Conflict)
            // No se tocó nada: los dos siguen donde estaban.
            assertTrue(legacy.isFile)
            assertTrue(migrated.isFile)
            assertEquals("antiguo", legacy.readText())
            assertEquals("migrado", migrated.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `destino embebido vacio no bloquea la migracion`() {
        val root = tempRoot()
        try {
            val legacy = File(root, "ux0/app/PCSB00598/eboot.bin")
            legacy.parentFile.mkdirs()
            legacy.writeText("antiguo")
            // El núcleo creó vita/ux0 vacío al inicializarse antes de migrar.
            File(root, "vita/ux0/app").mkdirs()
            File(root, "vita/ux0/user/00/savedata").mkdirs()

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.Migrated(1), result)
            assertTrue(File(root, "vita/ux0/app/PCSB00598/eboot.bin").isFile)
            assertFalse(File(root, "ux0").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir prefiere el antiguo con contenido si el embebido esta vacio`() {
        val root = tempRoot()
        try {
            val legacy = File(root, "ux0/app/PCSB00598/eboot.bin")
            legacy.parentFile.mkdirs()
            legacy.writeText("antiguo")
            File(root, "vita/ux0/app").mkdirs()

            // Sin este arreglo, el juego "desaparecía": resolve devolvía
            // el vita/ux0 vacío y scanStatus no veía el juego.
            assertEquals(File(root, "ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
            assertTrue(EmbeddedCoreLayout.needsMigration(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir usa el embebido aunque el antiguo exista vacio`() {
        val root = tempRoot()
        try {
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")
            File(root, "ux0/app").mkdirs()

            assertEquals(File(root, "vita/ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `config yml en la raiz no se mueve`() {
        val root = tempRoot()
        try {
            val config = File(root, "config.yml")
            config.writeText("pref-path: x")
            val eboot = File(root, "ux0/app/PCSB00598/eboot.bin")
            eboot.parentFile.mkdirs()
            eboot.writeText("falso eboot")
            EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            // config.yml sigue en la raíz del storage (ahí lo lee el núcleo).
            assertTrue(config.isFile)
            assertFalse(File(root, "vita/config.yml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir usa el ux0 antiguo sin migrar`() {
        val root = tempRoot()
        try {
            assertEquals(File(root, "ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir usa vita ux0 tras migrar`() {
        val root = tempRoot()
        try {
            val eboot = File(root, "ux0/app/PCSB00598/eboot.bin")
            eboot.parentFile.mkdirs()
            eboot.writeText("falso eboot")
            EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(File(root, "vita/ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
