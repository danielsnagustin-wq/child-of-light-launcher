package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.model.GameSession
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
    fun newSessionId(): String = "s${System.currentTimeMillis()}"

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
        indexFile.writeText(sessions.joinToString("\n") { formatLine(it) })
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
            activeFile.writeText(id)
        }
    }

    /** Copia la savedata viva a la carpeta de la sesión. Devuelve (archivos, bytes). */
    fun snapshotSavedata(titleId: String, sessionId: String): Pair<Int, Long> {
        require(titleId.isNotBlank()) { "Title ID vacío: no se puede localizar la savedata." }
        val dest = File(sessionDir(sessionId), "savedata")
        val live = liveSaveDir(titleId)
        if (!live.isDirectory) {
            dest.deleteRecursively()
            return 0 to 0L
        }
        dest.deleteRecursively()
        dest.mkdirs()
        live.copyRecursively(dest, overwrite = true)
        return savedataStats(sessionId)
    }

    /** Restaura la savedata de la sesión a la carpeta viva. Devuelve (archivos, bytes). */
    fun restoreSavedata(titleId: String, sessionId: String): Pair<Int, Long> {
        require(titleId.isNotBlank()) { "Title ID vacío: no se puede localizar la savedata." }
        val src = File(sessionDir(sessionId), "savedata")
        val live = liveSaveDir(titleId)
        live.deleteRecursively()
        if (!src.isDirectory) return 0 to 0L
        live.mkdirs()
        src.copyRecursively(live, overwrite = true)
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
        File(dir, "achievements.txt").writeText(achievementsText)
        val profileFile = File(dir, "control-profile.txt")
        if (controlProfileId.isNullOrBlank()) profileFile.delete() else profileFile.writeText(controlProfileId)
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
                file.inputStream().use { it.copyTo(zip) }
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
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val dest = File(tmp, entry.name)
                        requireInside(tmp, dest)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> zip.copyTo(out) }
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
                GameSession(
                    id = parts[0].takeIf { it.isNotBlank() } ?: return null,
                    name = unescape(parts[1]),
                    titleId = parts[2],
                    createdAtMillis = parts[3].toLong(),
                    lastPlayedAtMillis = parts[4].toLong(),
                    playTimeMillis = parts[5].toLong(),
                    chapter = unescape(parts[6]),
                    savedataFiles = parts[7].toInt(),
                    savedataBytes = parts[8].toLong(),
                    unlockedTrophies = parts[9].toInt(),
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

    private fun liveSaveDir(titleId: String): File =
        File(vitaRoot, "ux0/user/00/savedata/$titleId")

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
