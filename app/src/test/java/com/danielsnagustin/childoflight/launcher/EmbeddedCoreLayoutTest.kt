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
    fun `conflicto si el antiguo tiene otro juego que el embebido no tiene`() {
        val root = tempRoot()
        try {
            // NOTA (2026-11-08): este test antes ponía el MISMO juego
            // (PCSB00598) en los dos lados; con el arreglo del conflicto
            // de migración eso ya no es Conflict: si el embebido ya
            // tiene el juego y el antiguo no tiene otro, el lanzamiento
            // debe continuar (AlreadyMigrated). El Conflict real es
            // cuando el antiguo tiene OTRO juego que el embebido no
            // tiene: ahí sí hay ambigüedad sobre cuál es la instalación
            // buena. El caso del mismo juego en ambos lados lo cubre el
            // test `migracion no bloquea si el embebido ya tiene el mismo juego`.
            val legacy = File(root, "ux0/app/PCSE00480/eboot.bin")
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
    fun `migracion no bloquea si el embebido ya tiene el mismo juego`() {
        val root = tempRoot()
        try {
            // Caso del bug JUGAR (2026-11-08, reporte del teléfono):
            // el juego ya está instalado en vita/ux0 y el ux0 antiguo
            // sigue existiendo con el mismo juego. Antes esto era
            // Conflict en cada lanzamiento y JUGAR no arrancaba nunca.
            val legacy = File(root, "ux0/app/PCSB00598/eboot.bin")
            legacy.parentFile.mkdirs()
            legacy.writeText("antiguo")
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.AlreadyMigrated, result)
            // No se tocó nada: el antiguo se deja intacto.
            assertTrue(legacy.isFile)
            assertTrue(migrated.isFile)
            assertEquals("antiguo", legacy.readText())
            assertEquals("migrado", migrated.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `migracion no bloquea si el embebido tiene el juego y el antiguo no tiene juegos`() {
        val root = tempRoot()
        try {
            val legacySave = File(root, "ux0/user/00/savedata/PCSB00598/slot0")
            legacySave.parentFile.mkdirs()
            legacySave.writeText("partida")
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.AlreadyMigrated, result)
            // P1-5(d) (2026-11-08): el save del antiguo se FUSIONA al
            // destino (antes se dejaba intacto y quedaba invisible).
            assertFalse(legacySave.isFile)
            assertTrue(File(root, "vita/ux0/user/00/savedata/PCSB00598/slot0").isFile)
            assertTrue(migrated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `conflicto si el antiguo tiene otro juego en pkg que el embebido no tiene`() {
        val root = tempRoot()
        try {
            val legacyPkg = File(root, "ux0/pkg/PCSE00480.pkg")
            legacyPkg.parentFile.mkdirs()
            legacyPkg.writeText("otro juego")
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertTrue(result is EmbeddedCoreLayout.MigrationResult.Conflict)
            assertTrue(legacyPkg.isFile)
            assertTrue(migrated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `destino embebido vacio con pkg en el antiguo migra como antes`() {
        val root = tempRoot()
        try {
            val legacyPkg = File(root, "ux0/pkg/PCSB00598.pkg")
            legacyPkg.parentFile.mkdirs()
            legacyPkg.writeText("falso pkg")
            File(root, "vita/ux0/app").mkdirs()
            File(root, "vita/ux0/user/00/savedata").mkdirs()

            val result = EmbeddedCoreLayout.migrateToEmbeddedLayout(root)
            assertEquals(EmbeddedCoreLayout.MigrationResult.Migrated(1), result)
            assertTrue(File(root, "vita/ux0/pkg/PCSB00598.pkg").isFile)
            assertFalse(File(root, "ux0").exists())
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
    fun `resolveUx0Dir prefiere el embebido con el juego extraido aunque el antiguo tenga un pkg suelto`() {
        val root = tempRoot()
        try {
            val migrated = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            migrated.parentFile.mkdirs()
            migrated.writeText("migrado")
            // Resto en el antiguo: un .pkg de otra región que
            // gameTitles cuenta como título y el embebido no tiene.
            // Sin el arreglo, resolve devolvía el antiguo y el juego
            // "no estaba instalado" aunque corriera (reporte 19:46).
            val stray = File(root, "ux0/pkg/PCSE00480.pkg")
            stray.parentFile.mkdirs()
            stray.writeText("resto")
            assertEquals(File(root, "vita/ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
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

    @Test
    fun `resolveUx0Dir prefiere el antiguo si el pkg vive ahi y el embebido no lo tiene`() {
        val root = tempRoot()
        try {
            // Caso del bug JUGAR (2026-11-08): el .pkg importado vive en
            // el ux0 antiguo y el embebido tiene otro contenido, pero no
            // ese juego. Devolver el embebido lo haría "desaparecer".
            val pkg = File(root, "ux0/pkg/PCSB00598.pkg")
            pkg.parentFile.mkdirs()
            pkg.writeText("falso pkg")
            val other = File(root, "vita/ux0/app/PCSE00480/eboot.bin")
            other.parentFile.mkdirs()
            other.writeText("otro juego")

            assertEquals(File(root, "ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir prefiere el antiguo si el juego extraido vive ahi y el embebido no lo tiene`() {
        val root = tempRoot()
        try {
            val eboot = File(root, "ux0/app/PCSB00598/eboot.bin")
            eboot.parentFile.mkdirs()
            eboot.writeText("antiguo")
            val otherPkg = File(root, "vita/ux0/pkg/PCSE00480.pkg")
            otherPkg.parentFile.mkdirs()
            otherPkg.writeText("otro juego")

            assertEquals(File(root, "ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir usa el embebido si el juego ya esta ahi`() {
        val root = tempRoot()
        try {
            val pkg = File(root, "ux0/pkg/PCSB00598.pkg")
            pkg.parentFile.mkdirs()
            pkg.writeText("antiguo")
            val eboot = File(root, "vita/ux0/app/PCSB00598/eboot.bin")
            eboot.parentFile.mkdirs()
            eboot.writeText("migrado")

            assertEquals(File(root, "vita/ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveUx0Dir sin juego en ninguno mantiene el comportamiento actual`() {
        val root = tempRoot()
        try {
            // Solo partidas guardadas en ambos lados, ningún juego: no
            // hay nada que "perder", así que manda el layout migrado.
            val legacySave = File(root, "ux0/user/00/savedata/PCSB00598/slot0")
            legacySave.parentFile.mkdirs()
            legacySave.writeText("partida")
            val embeddedSave = File(root, "vita/ux0/user/00/savedata/PCSB00598/slot0")
            embeddedSave.parentFile.mkdirs()
            embeddedSave.writeText("partida")

            assertEquals(File(root, "vita/ux0"), EmbeddedCoreLayout.resolveUx0Dir(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
