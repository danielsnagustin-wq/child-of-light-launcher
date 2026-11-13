package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.SessionRepository
import com.danielsnagustin.childoflight.launcher.model.GameSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests del repositorio de sesiones (capa de archivos, sin Android).
 * Cubre: índice (escritura/lectura/escape), snapshot/restore de la
 * savedata viva, export/import por zip y el rechazo de zip-slip.
 */
class SessionRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): SessionRepository {
        val vitaRoot = tmp.newFolder("vita3k")
        return SessionRepository(vitaRoot)
    }

    private fun session(id: String = "s1", name: String = "Partida 1"): GameSession =
        GameSession(
            id = id,
            name = name,
            titleId = "PCSE00480",
            createdAtMillis = 1000L,
            lastPlayedAtMillis = 2000L,
            playTimeMillis = 3_600_000L,
            chapter = "Capítulo 2",
            savedataFiles = 0,
            savedataBytes = 0L,
            unlockedTrophies = 3,
        )

    @Test
    fun indexRoundTripPreservesSessions() {
        val r = repo()
        val sessions = listOf(session("s1", "Partida 1"), session("s2", "Partida 2 | con | barras"))
        r.saveSessions(sessions)
        val loaded = r.listSessions()
        assertEquals(2, loaded.size)
        assertEquals("Partida 1", loaded[0].name)
        assertEquals("Partida 2 | con | barras", loaded[1].name)
        assertEquals("Capítulo 2", loaded[1].chapter)
        assertEquals(3, loaded[1].unlockedTrophies)
        assertEquals(3_600_000L, loaded[1].playTimeMillis)
    }

    @Test
    fun indexSkipsMalformedLines() {
        val r = repo()
        r.saveSessions(listOf(session("s1")))
        val index = File(r.sessionsRoot, "index.txt")
        index.appendText("\nESTA-LINEA-ESTA-ROTA\n" + SessionRepository.formatLine(session("s2", "Buena")) + "\n")
        val loaded = r.listSessions()
        assertEquals(2, loaded.size)
        assertEquals("s1", loaded[0].id)
        assertEquals("s2", loaded[1].id)
    }

    @Test
    fun activeSessionIdRoundTrip() {
        val r = repo()
        assertEquals(null, r.getActiveSessionId())
        r.setActiveSessionId("s1")
        assertEquals("s1", r.getActiveSessionId())
        r.setActiveSessionId(null)
        assertEquals(null, r.getActiveSessionId())
    }

    @Test
    fun snapshotAndRestoreSavedataRoundTrip() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = File(vitaRoot, "ux0/user/00/savedata/PCSE00480")
        live.mkdirs()
        File(live, "save0.bin").writeText("PROGRESO-A")
        File(live, "save1.bin").writeText("PROGRESO-B")

        val (files, bytes) = r.snapshotSavedata("PCSE00480", "s1")
        assertEquals(2, files)
        assertTrue(bytes > 0)

        // La viva cambia (nuevo juego) y al restaurar vuelve lo guardado.
        File(live, "save0.bin").writeText("NUEVO-JUEGO")
        File(live, "save2.bin").writeText("EXTRA")
        r.restoreSavedata("PCSE00480", "s1")
        assertEquals("PROGRESO-A", File(live, "save0.bin").readText())
        assertEquals("PROGRESO-B", File(live, "save1.bin").readText())
        assertFalse(File(live, "save2.bin").exists())
    }

    @Test
    fun snapshotOfEmptyLiveSavedataClearsSessionCopy() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        // La carpeta viva EXISTE pero está vacía: el snapshot refleja ese
        // vacío y limpia la copia guardada de la sesión.
        val live = File(vitaRoot, "ux0/user/00/savedata/PCSE00480")
        live.mkdirs()
        val sessionSave = File(r.sessionDir("s1"), "savedata")
        sessionSave.mkdirs()
        File(sessionSave, "old.bin").writeText("VIEJO")
        val (files, _) = r.snapshotSavedata("PCSE00480", "s1")
        assertEquals(0, files)
        assertFalse(File(sessionSave, "old.bin").exists())
    }

    @Test
    fun snapshotWithNoLiveSavedataKeepsSessionCopy() {
        val r = repo()
        // H1 (auditoría): si la carpeta viva NO existe (el juego aún no
        // creó savedata), la copia guardada de la sesión NO se toca:
        // borrarla aquí perdería los saves de una partida anterior.
        // El snapshot devuelve las estadísticas de lo guardado.
        val sessionSave = File(r.sessionDir("s1"), "savedata")
        sessionSave.mkdirs()
        File(sessionSave, "old.bin").writeText("VIEJO")
        val (files, bytes) = r.snapshotSavedata("PCSE00480", "s1")
        assertEquals(1, files)
        assertTrue(bytes > 0)
        assertEquals("VIEJO", File(sessionSave, "old.bin").readText())
    }

    @Test
    fun exportImportZipRoundTrip() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = File(vitaRoot, "ux0/user/00/savedata/PCSE00480")
        live.mkdirs()
        File(live, "save0.bin").writeText("PROGRESO")
        r.snapshotSavedata("PCSE00480", "s1")
        r.writeSessionExtras("s1", "1|1000;2|2000", "profile-g99")

        val out = ByteArrayOutputStream()
        r.exportSession(session("s1", "Mi partida"), out)

        // Borrar la original y reimportar: llega con id nuevo y los datos.
        r.deleteSession("s1")
        val imported = r.importSession(ByteArrayInputStream(out.toByteArray()))
        assertNotEquals("s1", imported.id)
        assertEquals("Mi partida", imported.name)
        assertEquals(1, imported.savedataFiles)
        assertEquals("PROGRESO", File(r.sessionDir(imported.id), "savedata/save0.bin").readText())
        assertEquals("1|1000;2|2000", r.readAchievementsText(imported.id))
        assertEquals("profile-g99", r.readControlProfileId(imported.id))
    }

    @Test
    fun importRejectsZipWithoutSessionMetadata() {
        val r = repo()
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("save0.bin"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        try {
            r.importSession(ByteArrayInputStream(out.toByteArray()))
            throw AssertionError("Se esperaba IllegalArgumentException por falta de session.txt")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("session.txt"))
        }
    }

    @Test
    fun importRejectsZipSlipEntries() {
        val r = repo()
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("session.txt"))
            zip.write(("COL-SESSION-v1\n" + SessionRepository.formatLine(session("sX")) + "\n").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("../evil.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        try {
            r.importSession(ByteArrayInputStream(out.toByteArray()))
            throw AssertionError("Se esperaba rechazo por zip-slip")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fuera de la carpeta destino"))
        }
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun blankTitleIdNeverTouchesSavedataParent() {
        val r = repo()
        try {
            r.snapshotSavedata("", "s1")
            throw AssertionError("Se esperaba rechazo por Title ID vacío")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Title ID vacío"))
        }
    }

    // --- R2-P2-32: respaldos savedata.bak-* huérfanos ---

    @Test
    fun orphanSavedataBakIsRestoredWhenSavedataMissing() {
        val r = repo()
        // Proceso muerto a mitad de snapshot: solo queda el .bak-*.
        val bak = File(r.sessionDir("s1"), "savedata.bak-123")
        bak.mkdirs()
        File(bak, "save0.bin").writeText("RESPALDO")
        r.reconcileSavedataBackups("s1")
        assertEquals("RESPALDO", File(r.sessionDir("s1"), "savedata/save0.bin").readText())
        assertFalse(File(r.sessionDir("s1"), "savedata.bak-123").exists())
    }

    @Test
    fun orphanSavedataBakIsDeletedWhenSavedataExists() {
        val r = repo()
        val savedata = File(r.sessionDir("s1"), "savedata")
        savedata.mkdirs()
        File(savedata, "save0.bin").writeText("ACTUAL")
        val bak = File(r.sessionDir("s1"), "savedata.bak-123")
        bak.mkdirs()
        File(bak, "save0.bin").writeText("VIEJO")
        r.reconcileSavedataBackups("s1")
        assertFalse(File(r.sessionDir("s1"), "savedata.bak-123").exists())
        assertEquals("ACTUAL", File(savedata, "save0.bin").readText())
    }

    @Test
    fun snapshotReconcilesOrphanBakBeforeCopying() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = File(vitaRoot, "ux0/user/00/savedata/PCSE00480")
        live.mkdirs()
        File(live, "save0.bin").writeText("NUEVO")
        val bak = File(r.sessionDir("s1"), "savedata.bak-123")
        bak.mkdirs()
        File(bak, "save0.bin").writeText("VIEJO")
        val (files, _) = r.snapshotSavedata("PCSE00480", "s1")
        assertEquals(1, files)
        assertEquals("NUEVO", File(r.sessionDir("s1"), "savedata/save0.bin").readText())
        assertFalse(File(r.sessionDir("s1"), "savedata.bak-123").exists())
    }

    @Test
    fun exportExcludesSavedataBakEntries() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = File(vitaRoot, "ux0/user/00/savedata/PCSE00480")
        live.mkdirs()
        File(live, "save0.bin").writeText("PROGRESO")
        r.snapshotSavedata("PCSE00480", "s1")
        val bak = File(r.sessionDir("s1"), "savedata.bak-123")
        bak.mkdirs()
        File(bak, "save0.bin").writeText("VIEJO")

        val out = ByteArrayOutputStream()
        r.exportSession(session("s1", "Mi partida"), out)
        val entries = mutableListOf<String>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.contains("savedata/save0.bin"))
        assertFalse(entries.any { it.startsWith("savedata.bak-") })
    }

    @Test
    fun importDropsSavedataBakEntries() {
        val r = repo()
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("session.txt"))
            zip.write(("COL-SESSION-v1\n" + SessionRepository.formatLine(session("sX")) + "\n").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("savedata/save0.bin"))
            zip.write("PROGRESO".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("savedata.bak-123/save0.bin"))
            zip.write("VIEJO".toByteArray())
            zip.closeEntry()
        }
        val imported = r.importSession(ByteArrayInputStream(out.toByteArray()))
        assertEquals("PROGRESO", File(r.sessionDir(imported.id), "savedata/save0.bin").readText())
        assertFalse(File(r.sessionDir(imported.id), "savedata.bak-123").exists())
    }

    @Test
    fun liveSavedataFingerprintTracksChanges() {
        val r = repo()
        assertEquals(null, r.liveSavedataFingerprint("PCSE00480"))
        val vitaRoot = File(tmp.root, "vita3k")
        val live = File(vitaRoot, "ux0/user/00/savedata/PCSE00480")
        live.mkdirs()
        File(live, "save0.bin").writeText("A")
        val first = r.liveSavedataFingerprint("PCSE00480")!!
        assertEquals(1, first.first)
        assertTrue(first.second > 0)
        File(live, "save1.bin").writeText("B")
        val second = r.liveSavedataFingerprint("PCSE00480")!!
        assertEquals(2, second.first)
        assertTrue(second.second > first.second)
    }

    // --- Trofeos del núcleo por sesión (ronda 0.2.70, P1-6) ---

    private fun liveTrophyDir(vitaRoot: File, npcommid: String = "NPWR06875_00"): File {
        val dir = File(vitaRoot, "ux0/user/00/trophy/data/$npcommid")
        dir.mkdirs()
        return dir
    }

    @Test
    fun snapshotAndRestoreTrophyDataRoundTrip() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = liveTrophyDir(vitaRoot)
        File(live, "TROPUSR.DAT").writeText("TROFEOS-A")
        File(live, "TROPCONF.SFM").writeText("CONF")

        r.snapshotTrophyData("PCSE00480", "s1")
        assertEquals("TROFEOS-A", File(r.sessionDir("s1"), "trophy/NPWR06875_00/TROPUSR.DAT").readText())

        // La viva cambia (otra sesión jugó) y al restaurar vuelve lo guardado.
        File(live, "TROPUSR.DAT").writeText("TROFEOS-B")
        File(live, "EXTRA.PNG").writeText("X")
        r.restoreTrophyData("PCSE00480", "s1")
        assertEquals("TROFEOS-A", File(live, "TROPUSR.DAT").readText())
        assertEquals("CONF", File(live, "TROPCONF.SFM").readText())
        assertFalse(File(live, "EXTRA.PNG").exists())
    }

    @Test
    fun restoreWithNoStoredTrophyClearsLive() {
        // La sesión no tiene trofeos guardados: la viva queda vacía
        // para no heredar el TROPUSR.DAT de la sesión anterior.
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = liveTrophyDir(vitaRoot)
        File(live, "TROPUSR.DAT").writeText("TROFEOS-A")
        r.restoreTrophyData("PCSE00480", "s1")
        assertFalse(File(live, "TROPUSR.DAT").exists())
    }

    @Test
    fun snapshotWithNoLiveTrophyKeepsStored() {
        // Análogo H1 de la savedata: si la viva no existe, la copia
        // guardada de la sesión NO se toca.
        val r = repo()
        val stored = File(r.sessionDir("s1"), "trophy/NPWR06875_00")
        stored.mkdirs()
        File(stored, "TROPUSR.DAT").writeText("VIEJO")
        r.snapshotTrophyData("PCSE00480", "s1")
        assertEquals("VIEJO", File(stored, "TROPUSR.DAT").readText())
    }

    @Test
    fun clearLiveTrophyDataRemovesLiveDir() {
        val r = repo()
        val vitaRoot = File(tmp.root, "vita3k")
        val live = liveTrophyDir(vitaRoot)
        File(live, "TROPUSR.DAT").writeText("TROFEOS-A")
        r.clearLiveTrophyData("PCSE00480")
        assertFalse(File(live, "TROPUSR.DAT").exists())
        assertFalse(live.exists())
    }
}
