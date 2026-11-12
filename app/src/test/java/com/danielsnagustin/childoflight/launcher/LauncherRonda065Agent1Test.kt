package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.data.PkgRetryState
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests nuevos de la ronda 0.2.65, agente 1 (launcher / puente JNI):
 * P0-1 (mensaje genérico sin el nombre interno), J-P2-1 (el error del
 * stub no tapa al real), X-P2-2 (hash parcial en la identidad del
 * .pkg) y R-P1-12 (puente getCustomDriverSupportInfo sin núcleo).
 * No se toca ningún test compartido: todo lo de aquí es nuevo.
 */
class LauncherRonda065Agent1Test {

    // P0-1: el mensaje visible nunca menciona el nombre interno del
    // núcleo ni el texto crudo de carga, ni siquiera cuando el error
    // real lo trae.
    @Test
    fun initFailureMessageNeverLeaksLoadError() {
        val message = Vita3KLauncher.initFailureMessage(
            Vita3kCoreBridge.NOT_IMPLEMENTED,
            "System.loadLibrary(Vita3K): dlopen failed: library \"libVita3K.so\" not found",
        )
        assertFalse(message.contains("Vita3K"))
        assertFalse(message.contains("System.loadLibrary"))
        assertFalse(message.contains("libVita3K"))
        assertTrue(message.contains("No se pudo cargar el motor"))
    }

    @Test
    fun initFailureMessageWithoutLoadErrorIsStillGeneric() {
        val message = Vita3KLauncher.initFailureMessage(Vita3kCoreBridge.NOT_IMPLEMENTED, null)
        assertFalse(message.contains("Vita3K"))
        assertTrue(message.contains("No se pudo cargar el motor"))
    }

    @Test
    fun initFailureMessageOtherCodeHasNoLoadErrorEither() {
        val message = Vita3KLauncher.initFailureMessage(-2, "System.loadLibrary(Vita3K): boom")
        assertFalse(message.contains("Vita3K"))
        assertFalse(message.contains("loadLibrary"))
    }

    // J-P2-1: sin .so, el primer error (el .so real) es el que queda;
    // el intento del stub no lo sobrescribe.
    @Test
    fun lastLoadErrorKeepsRealLibraryErrorNotStub() {
        assertFalse(Vita3kCoreBridge.available)
        val error = Vita3kCoreBridge.lastLoadError
        assertTrue(error != null && error.contains("Vita3K"))
        assertFalse(error!!.contains("vita3k-bridge"))
    }

    // R-P1-12: sin núcleo no hay dato (null), nunca un array vacío
    // ni una excepción.
    @Test
    fun customDriverSupportInfoIsNullWithoutCore() {
        assertFalse(Vita3kCoreBridge.available)
        assertNull(Vita3kCoreBridge.getCustomDriverSupportInfo(""))
        assertNull(Vita3kCoreBridge.getCustomDriverSupportInfo("Turnip_v26.0.0_R8_Gmem"))
    }

    private fun pkgFile(dir: File, size: Int, fill: Byte): File {
        val file = File(dir, "game.pkg")
        file.writeBytes(ByteArray(size) { fill })
        file.setLastModified(1_700_000_000_000L)
        return file
    }

    // X-P2-2: mismo nombre, mismo tamaño y mismo mtime, pero distinto
    // contenido: el .pkg nuevo NO hereda el bloqueo del viejo.
    @Test
    fun sameNameSizeAndMtimeButDifferentContentIsNotBlocked() {
        val dirA = Files.createTempDirectory("pkg-hash-a").toFile()
        val dirB = Files.createTempDirectory("pkg-hash-b").toFile()
        try {
            val oldPkg = pkgFile(dirA, 200, 1)
            val newPkg = pkgFile(dirB, 200, 2)
            assertEquals(oldPkg.length(), newPkg.length())
            assertEquals(oldPkg.lastModified(), newPkg.lastModified())
            assertNotEquals(
                PkgRetryState.partialFileHash(oldPkg),
                PkgRetryState.partialFileHash(newPkg),
            )
            val key = PkgRetryState.gameKey("PCSB00598")
            val blocked = PkgRetryState().recordFailedReinstall(key, oldPkg, 1_000L)
            assertTrue(blocked.isBlocked(key, oldPkg))
            assertFalse(blocked.isBlocked(key, newPkg))
            assertTrue(blocked.shouldAttemptReinstall(key, newPkg))
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    // X-P2-2: mismo contenido (mismo nombre, tamaño y mtime) sí sigue
    // bloqueado.
    @Test
    fun sameContentSameNameSizeAndMtimeStaysBlocked() {
        val dirA = Files.createTempDirectory("pkg-hash-c").toFile()
        val dirB = Files.createTempDirectory("pkg-hash-d").toFile()
        try {
            val first = pkgFile(dirA, 200, 7)
            val same = pkgFile(dirB, 200, 7)
            val key = PkgRetryState.gameKey("PCSB00598")
            val blocked = PkgRetryState().recordFailedReinstall(key, first, 1_000L)
            assertTrue(blocked.isBlocked(key, same))
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    // X-P2-2: las líneas viejas de 6 campos (sin hash) se siguen
    // leyendo y bloquean con la identidad clásica.
    @Test
    fun legacySixFieldLinesStillBlock() {
        val dir = Files.createTempDirectory("pkg-hash-legacy").toFile()
        try {
            val pkg = pkgFile(dir, 200, 3)
            val decoded = PkgRetryState.decode(
                "game:PCSB00598|game.pkg|200|1700000000000|1|1000",
            )
            val key = PkgRetryState.gameKey("PCSB00598")
            assertTrue(decoded.isBlocked(key, pkg))
        } finally {
            dir.deleteRecursively()
        }
    }

    // X-P2-2: el hash sobrevive a codificar y decodificar.
    @Test
    fun encodeDecodeRoundTripPreservesPartialHash() {
        val dir = Files.createTempDirectory("pkg-hash-roundtrip").toFile()
        try {
            val pkg = pkgFile(dir, 200, 5)
            val key = PkgRetryState.gameKey("PCSB00598")
            val state = PkgRetryState().recordFailedReinstall(key, pkg, 1_000L)
            val decoded = PkgRetryState.decode(state.encode())
            assertEquals(state, decoded)
            assertTrue(decoded.isBlocked(key, pkg))
        } finally {
            dir.deleteRecursively()
        }
    }

    // X-P2-2: el hash es determinista y "" si el archivo no existe.
    @Test
    fun partialFileHashIsDeterministicAndEmptyForMissingFile() {
        val dir = Files.createTempDirectory("pkg-hash-det").toFile()
        try {
            val pkg = pkgFile(dir, 200, 9)
            assertEquals(
                PkgRetryState.partialFileHash(pkg),
                PkgRetryState.partialFileHash(pkg),
            )
            assertEquals("", PkgRetryState.partialFileHash(File(dir, "no-existe.pkg")))
        } finally {
            dir.deleteRecursively()
        }
    }

    // W-P1-5: borrar un addon bloqueado limpia sus entradas por nombre
    // de archivo para que una reimportación parta de cero.
    @Test
    fun clearEntriesForFileRemovesOnlyThatFile() {
        val dir = Files.createTempDirectory("pkg-hash-clear").toFile()
        try {
            val gamePkg = pkgFile(dir, 200, 1)
            val dlcPkg = File(dir, "dlc.pkg").apply {
                writeBytes(ByteArray(50) { 2 })
                setLastModified(1_700_000_000_000L)
            }
            val state = PkgRetryState()
                .recordFailedReinstall(PkgRetryState.gameKey("PCSB00598"), gamePkg, 1_000L)
                .recordFailedReinstall(PkgRetryState.addonKey("PCSB00598", "dlc.pkg"), dlcPkg, 2_000L)
            val cleared = state.clearEntriesForFile("dlc.pkg")
            assertEquals(1, cleared.entries.size)
            assertTrue(cleared.entries.containsKey(PkgRetryState.gameKey("PCSB00598")))
            assertFalse(cleared.isBlocked(PkgRetryState.addonKey("PCSB00598", "dlc.pkg"), dlcPkg))
        } finally {
            dir.deleteRecursively()
        }
    }
}
