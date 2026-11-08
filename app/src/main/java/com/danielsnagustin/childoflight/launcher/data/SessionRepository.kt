package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.model.GameSession
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Capa de archivos de las sesiones / partidas.
 *
 * Sin dependencias Android: solo java.io/java.util.zip, para poder
 * testearla en unit tests con un directorio temporal.
 *
 * Estructura dentro del vita_fs (<filesDir>/vita3k):
 * ```
 * sessions/
 *   index.txt               <- una línea por sesión (formato v1, ver abajo)
 *   active.txt              <- id de la sesión activa (una línea)
 *   <id>/
 *     savedata/             <- copia de ux0/user/00/savedata/<TITLE_ID>/
 *     achievements.txt      <- progreso local "n|epoch;..." (mismo formato que AchievementRepository)
 *     control-profile.txt   <- id del perfil de control activo (una línea)
 * ```
 * La carpeta "viva" ux0/user/00/savedata/<TITLE_ID>/ siempre contiene
 * la sesión ACTIVA. El resto de sesiones viven solo en sessions/<id>/.
 *
 * Formato index.txt (v1), una línea por sesión, campos separados por `|`:
 * `id|nombre|titleId|creadoMs|ultimaVezMs|tiempoMs|capitulo|archivos|bytes|logros`
 * Escape: `%` -> `%25` primero, `|` -> `%7C`, saltos de línea -> espacio.
 * Las líneas malformadas se ignoran al leer (nunca se rompe el índice
 * entero por una línea mala).
 */
class SessionRepository(private val vitaRoot: File) {

    val sessionsRoot: File get() = File(vitaRoot, "sessions")
    private val indexFile: File get() = File(sessionsRoot, "index.txt")
    private val activeFile: File get() = File(sessionsRoot, "active.txt")

    fun sessionDir(id: String): File = File(sessionsRoot, sanitizeId(id))

    /** Id nuevo, seguro para usar como nombre de carpeta. */
    // BUG (auditoría): solo currentTimeMillis() colisiona si se crean dos
    // sesiones en el mismo milisegundo. Se añade nanoTime como sufijo.
    fun newSessionId(): String = "s${System.currentTimeMillis()}-${System.nanoTime() % 100000}"

    fun listSessions(): List<GameSession> {
        if (!indexFile.isFile) return emptyList()
        return try {
            indexFile.readLines().mapNotNull { parseLine(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSessions(sessions: List<GameSession>) {
        sessionsRoot.mkdirs()
        writeTextAtomic(indexFile, sessions.joinToString("\n") { formatLine(it) })
    }

    fun getActiveSessionId(): String? {
        return try {
            activeFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun setActiveSessionId(id: String?) {
        sessionsRoot.mkdirs()
        if (id == null) {
            activeFile.delete()
        } else {
            writeTextAtomic(activeFile, id)
        }
    }

    /**
     * Escritura atómica de texto: se escribe a un temporal y se renombra
     * sobre el destino. Un corte a mitad (cierre, batería) nunca deja el
     * archivo a medias: el índice entero no se corrompe (H/M3).
     */
    private fun writeTextAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp-" + System.currentTimeMillis())
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("No se pudo guardar ${file.name}.")
        }
    }

    /** Copia la savedata viva a la carpeta de la sesión. Devuelve (archivos, bytes). */
    fun snapshotSavedata(titleId: String, sessionId: String): Pair<Int, Long> {
        require(titleId.isNotBlank()) { "Title ID vacío: no se puede localizar la savedata." }
        val dest = File(sessionDir(sessionId), "savedata")
        val live = liveSaveDir(titleId)
        if (!live.isDirectory) {
            // H1: si la viva no existe (el juego aún no creó savedata), la
            // copia guardada de la sesión NO se toca: borrarla aquí perdería
            // los saves de una partida anterior. Se devuelve lo guardado.
            return savedataStats(sessionId)
        }
        // BUG (auditoría): antes se borraba la copia guardada de la sesión
        // ANTES de copiar; si la copia fallaba, la sesión perdía sus saves.
        // Ahora se respalda y se restaura si la copia falla.
        val backup = File(sessionDir(sessionId), "savedata.bak-" + System.currentTimeMillis())
        val hadDest = dest.exists()
        if (hadDest && !dest.renameTo(backup)) {
            throw IllegalStateException("No se pudo respaldar la savedata de la sesión.")
        }
        try {
            dest.mkdirs()
            live.copyRecursively(dest, overwrite = true)
            if (hadDest) backup.deleteRecursively()
        } catch (e: Exception) {
            dest.deleteRecursively()
            if (hadDest) backup.renameTo(dest)
            throw e
        }
        return savedataStats(sessionId)
    }

    /** Restaura la savedata de la sesión a la carpeta viva. Devuelve (archivos, bytes). */
    fun restoreSavedata(titleId: String, sessionId: String): Pair<Int, Long> {
        require(titleId.isNotBlank()) { "Title ID vacío: no se puede localizar la savedata." }
        val src = File(sessionDir(sessionId), "savedata")
        val live = liveSaveDir(titleId)
        // BUG (auditoría): antes se borraba la savedata viva ANTES de
        // comprobar que existiera la copia de la sesión y antes de copiar;
        // si la copia fallaba, la partida viva se perdía. Primero se
        // comprueba la copia; la viva se respalda y se restaura si falla.
        if (!src.isDirectory) {
            live.deleteRecursively()
            return 0 to 0L
        }
        val backup = File(live.parentFile, live.name + ".bak-" + System.currentTimeMillis())
        val hadLive = live.exists()
        if (hadLive && !live.renameTo(backup)) {
            throw IllegalStateException("No se pudo respaldar la savedata viva antes de restaurar.")
        }
        try {
            live.mkdirs()
            src.copyRecursively(live, overwrite = true)
            if (hadLive) backup.deleteRecursively()
        } catch (e: Exception) {
            live.deleteRecursively()
            if (hadLive) backup.renameTo(live)
            throw e
        }
        return savedataStats(sessionId)
    }

    /** Borra solo la savedata viva (la de la sesión activa se conserva aparte). */
    fun clearLiveSavedata(titleId: String) {
        require(titleId.isNotBlank()) { "Title ID vacío: no se puede localizar la savedata." }
        liveSaveDir(titleId).deleteRecursively()
    }

    /** Borra la savedata guardada de una sesión (su carpeta savedata/), sin tocar la viva. */
    fun clearSessionSavedata(sessionId: String) {
        File(sessionDir(sessionId), "savedata").deleteRecursively()
    }

    fun savedataStats(sessionId: String): Pair<Int, Long> {
        val dir = File(sessionDir(sessionId), "savedata")
        if (!dir.isDirectory) return 0 to 0L
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.size to files.sumOf { it.length() }
    }

    fun writeSessionExtras(sessionId: String, achievementsText: String, controlProfileId: String?) {
        val dir = sessionDir(sessionId)
        dir.mkdirs()
        writeTextAtomic(File(dir, "achievements.txt"), achievementsText)
        val profileFile = File(dir, "control-profile.txt")
        if (controlProfileId.isNullOrBlank()) profileFile.delete() else writeTextAtomic(profileFile, controlProfileId)
    }

    fun readAchievementsText(sessionId: String): String {
        return try {
            File(sessionDir(sessionId), "achievements.txt").takeIf { it.isFile }?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun readControlProfileId(sessionId: String): String? {
        return try {
            File(sessionDir(sessionId), "control-profile.txt").takeIf { it.isFile }
                ?.readText()?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteSession(id: String) {
        sessionDir(id).deleteRecursively()
    }

    fun duplicateSessionDir(fromId: String, toId: String) {
        // M1: si los ids coinciden, el borrado del destino borraría el origen.
        require(fromId != toId) { "No se puede duplicar una sesión sobre sí misma." }
        val src = sessionDir(fromId)
        val dest = sessionDir(toId)
        dest.deleteRecursively()
        if (src.isDirectory) {
            dest.mkdirs()
            src.copyRecursively(dest, overwrite = true)
        } else {
            dest.mkdirs()
        }
    }

    /** Borra TODAS las sesiones (índice + activa + carpetas). Para "Borrar datos del juego". */
    fun deleteAll() {
        sessionsRoot.deleteRecursively()
    }

    /** Exporta una sesión a un .zip: session.txt (metadatos) + savedata/ + extras. */
    fun exportSession(session: GameSession, out: OutputStream) {
        val dir = sessionDir(session.id)
        require(dir.isDirectory) { "La sesión ${session.id} no tiene carpeta en disco." }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("session.txt"))
            zip.write(("COL-SESSION-v1\n" + formatLine(session) + "\n").toByteArray())
            zip.closeEntry()
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zip, LARGE_FILE_COPY_BUFFER_BYTES) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Importa una sesión desde un .zip exportado. Genera un id nuevo
     * (nunca reutiliza el del zip, para no chocar con una existente).
     * Devuelve la sesión importada (sin registrar en el índice: eso lo
     * hace el SessionManager). Rechaza zips sin session.txt y entradas
     * fuera de la carpeta destino (zip-slip).
     */
    fun importSession(input: InputStream): GameSession {
        val tmp = File(sessionsRoot, "import_tmp_${System.currentTimeMillis()}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                var entries = 0
                var totalBytes = 0L
                while (entry != null) {
                    // M7: tope anti zip-bomb / llenar disco (una sesión son
                    // saves de KBs-MBs; 1 GB y 10.000 entradas sobran).
                    entries++
                    if (entries > 10_000) throw IllegalArgumentException("El zip trae demasiadas entradas (> 10.000).")
                    if (!entry.isDirectory) {
                        val dest = File(tmp, entry.name)
                        requireInside(tmp, dest)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            totalBytes += zip.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES)
                            if (totalBytes > 1_000_000_000L) throw IllegalArgumentException("El zip es demasiado grande (> 1 GB).")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val metaFile = File(tmp, "session.txt")
            require(metaFile.isFile) { "El zip no es una sesión exportada: falta session.txt." }
            val lines = metaFile.readLines()
            require(lines.firstOrNull() == "COL-SESSION-v1") { "El zip no es una sesión exportada (cabecera inválida)." }
            val imported = lines.getOrNull(1)?.let { parseLine(it) }
                ?: throw IllegalArgumentException("El zip no trae metadatos de sesión válidos.")
            val newId = newSessionId()
            val dest = sessionDir(newId)
            dest.deleteRecursively()
            dest.mkdirs()
            tmp.copyRecursively(dest, overwrite = true)
            File(dest, "session.txt").delete()
            val stats = savedataStats(newId)
            return imported.copy(
                id = newId,
                createdAtMillis = System.currentTimeMillis(),
                lastPlayedAtMillis = 0L,
                savedataFiles = stats.first,
                savedataBytes = stats.second,
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // --- Formato index.txt ---

    companion object {
        fun formatLine(s: GameSession): String {
            return listOf(
                s.id,
                escape(s.name),
                s.titleId,
                s.createdAtMillis.toString(),
                s.lastPlayedAtMillis.toString(),
                s.playTimeMillis.toString(),
                escape(s.chapter),
                s.savedataFiles.toString(),
                s.savedataBytes.toString(),
                s.unlockedTrophies.toString(),
            ).joinToString("|")
        }

        fun parseLine(line: String): GameSession? {
            val parts = line.split("|")
            if (parts.size != 10) return null
            return try {
                // H5/M4: el índice es un archivo editable/importable; un
                // titleId o id malicioso ("../../x") haría que snapshot/
                // restore borraran fuera de savedata/. Se valida todo y se
                // descarta la línea si algo no cuadra (nunca se rompe el
                // índice entero por una línea mala).
                val id = parts[0].takeIf { it.isNotBlank() } ?: return null
                // Misma regla que sanitizeId(): solo letras, dígitos, '-' y '_'.
                if (id.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
                val titleId = parts[2]
                if (TitleId.fromString(titleId) == null) return null
                val created = parts[3].toLong()
                val lastPlayed = parts[4].toLong()
                val playTime = parts[5].toLong()
                val files = parts[7].toInt()
                val bytes = parts[8].toLong()
                val trophies = parts[9].toInt()
                if (created < 0 || lastPlayed < 0 || playTime < 0 || files < 0 || bytes < 0) return null
                if (trophies !in 0..27) return null
                GameSession(
                    id = id,
                    name = unescape(parts[1]),
                    titleId = titleId,
                    createdAtMillis = created,
                    lastPlayedAtMillis = lastPlayed,
                    playTimeMillis = playTime,
                    chapter = unescape(parts[6]),
                    savedataFiles = files,
                    savedataBytes = bytes,
                    unlockedTrophies = trophies,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun escape(raw: String): String =
            raw.replace("%", "%25").replace("|", "%7C").replace("\n", " ").replace("\r", " ")

        private fun unescape(raw: String): String =
            raw.replace("%7C", "|").replace("%25", "%")
    }

    // --- Utilidades ---

    private fun liveSaveDir(titleId: String): File {
        // H5: punto único de acceso a la savedata viva; un titleId inválido
        // nunca llega a formar una ruta fuera de savedata/.
        require(TitleId.fromString(titleId) != null) { "Title ID no válido: \"$titleId\"." }
        return File(EmbeddedCoreLayout.resolveUx0Dir(vitaRoot), "user/00/savedata/$titleId")
    }

    private fun sanitizeId(id: String): String {
        val clean = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(clean.isNotBlank()) { "Id de sesión no válido: \"$id\"." }
        return clean
    }

    private fun requireInside(base: File, dest: File) {
        val basePath = base.canonicalFile
        val destPath = dest.canonicalFile
        require(destPath.path.startsWith(basePath.path + File.separator)) {
            "Entrada de zip fuera de la carpeta destino: ${dest.name}"
        }
    }
}
