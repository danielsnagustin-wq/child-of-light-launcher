package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.PkgRetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tope de reinstalación por .pkg (ronda 0.2.64, H-2 tiempo de carga).
 * Puro y testeable en JVM: el estado vive en memoria (archivos
 * temporales reales para la identidad: nombre, tamaño, mtime).
 */
class PkgRetryStateTest {

    private fun pkgFile(dir: File, name: String, size: Int): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(size))
        return file
    }

    @Test
    fun firstFailureBlocksSameFile() {
        val dir = Files.createTempDirectory("pkg-retry-test").toFile()
        try {
            val pkg = pkgFile(dir, "game.pkg", 100)
            val key = PkgRetryState.gameKey("PCSB00598")
            val empty = PkgRetryState()
            assertFalse(empty.isBlocked(key, pkg))
            assertTrue(empty.shouldAttemptReinstall(key, pkg))
            val afterFailure = empty.recordFailedReinstall(key, pkg, 1_000L)
            assertTrue(afterFailure.isBlocked(key, pkg))
            assertFalse(afterFailure.shouldAttemptReinstall(key, pkg))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun changedFileResetsTheCap() {
        val dir = Files.createTempDirectory("pkg-retry-test").toFile()
        try {
            val pkg = pkgFile(dir, "game.pkg", 100)
            val key = PkgRetryState.gameKey("PCSB00598")
            val blocked = PkgRetryState().recordFailedReinstall(key, pkg, 1_000L)
            assertTrue(blocked.isBlocked(key, pkg))
            // Mismo nombre, otro tamaño (reimportado): el tope ya no vale.
            pkg.writeBytes(ByteArray(200))
            assertFalse(blocked.isBlocked(key, pkg))
            assertTrue(blocked.shouldAttemptReinstall(key, pkg))
            // Mismo tamaño, otro mtime: también resetea.
            val sameSize = pkgFile(dir, "game.pkg", 200)
            sameSize.setLastModified(pkg.lastModified() + 60_000L)
            val blocked2 = PkgRetryState().recordFailedReinstall(key, sameSize, 2_000L)
            sameSize.setLastModified(sameSize.lastModified() + 60_000L)
            assertFalse(blocked2.isBlocked(key, sameSize))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun successClearsTheEntry() {
        val dir = Files.createTempDirectory("pkg-retry-test").toFile()
        try {
            val pkg = pkgFile(dir, "game.pkg", 100)
            val key = PkgRetryState.gameKey("PCSB00598")
            val blocked = PkgRetryState().recordFailedReinstall(key, pkg, 1_000L)
            assertTrue(blocked.isBlocked(key, pkg))
            assertFalse(blocked.recordSuccess(key).isBlocked(key, pkg))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun addonKeysAreIndependentFromGameKey() {
        val dir = Files.createTempDirectory("pkg-retry-test").toFile()
        try {
            val gamePkg = pkgFile(dir, "game.pkg", 100)
            val updatePkg = pkgFile(dir, "update.pkg", 50)
            val gameKey = PkgRetryState.gameKey("PCSB00598")
            val updateKey = PkgRetryState.addonKey("PCSB00598", "update.pkg")
            val blocked = PkgRetryState().recordFailedReinstall(updateKey, updatePkg, 1_000L)
            assertTrue(blocked.isBlocked(updateKey, updatePkg))
            assertFalse(blocked.isBlocked(gameKey, gamePkg))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val dir = Files.createTempDirectory("pkg-retry-test").toFile()
        try {
            val pkg = pkgFile(dir, "game.pkg", 100)
            val key = PkgRetryState.gameKey("PCSB00598")
            val state = PkgRetryState()
                .recordFailedReinstall(key, pkg, 1_000L)
                .recordFailedReinstall(PkgRetryState.addonKey("PCSB00598", "dlc.pkg"), pkgFile(dir, "dlc.pkg", 10), 2_000L)
            val decoded = PkgRetryState.decode(state.encode())
            assertEquals(state, decoded)
            assertTrue(decoded.isBlocked(key, pkg))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedLinesAreSkipped() {
        val decoded = PkgRetryState.decode(
            "game:PCSB00598|game.pkg|100|123|1|1000\n" +
                "línea rota sin separadores\n" +
                "game:OTRO|otro.pkg|no-numero|123|1|1000\n" +
                "game:PCSB00598|game.pkg|100|123|no-numero|1000\n" +
                "\n" +
                "addon:PCSB00598:dlc.pkg|dlc.pkg|10|456|1|2000\n",
        )
        assertEquals(2, decoded.entries.size)
        assertTrue(decoded.entries.containsKey("game:PCSB00598"))
        assertTrue(decoded.entries.containsKey("addon:PCSB00598:dlc.pkg"))
    }

    @Test
    fun secondFailureKeepsBlocked() {
        val dir = Files.createTempDirectory("pkg-retry-test").toFile()
        try {
            val pkg = pkgFile(dir, "game.pkg", 100)
            val key = PkgRetryState.gameKey("PCSB00598")
            val once = PkgRetryState().recordFailedReinstall(key, pkg, 1_000L)
            val twice = once.recordFailedReinstall(key, pkg, 2_000L)
            assertEquals(2, twice.entries.getValue(key).failedReinstalls)
            assertTrue(twice.isBlocked(key, pkg))
        } finally {
            dir.deleteRecursively()
        }
    }
}
