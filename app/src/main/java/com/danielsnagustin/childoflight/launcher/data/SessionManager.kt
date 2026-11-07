package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.GameSession
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Sesiones / partidas múltiples.
 *
 * En Vita el guardado es UNA sola carpeta
 * ux0/user/00/savedata/<TITLE_ID>/, así que el launcher guarda una
 * copia por sesión en sessions/<id>/ (ver SessionRepository) y la
 * carpeta "viva" siempre contiene la sesión ACTIVA.
 *
 * REGLA DE SEGURIDAD (se cumple en todos los cambios):
 * antes de cambiar la sesión activa o de crear una vacía, se hace
 * snapshot de la sesión actual (savedata viva + logros locales +
 * perfil de control) en su carpeta. Nunca se sobrescribe sin avisar:
 * el cambio de sesión guarda la actual y luego carga la otra.
 *
 * Orden al activar una sesión (snapshot PRIMERO, restore DESPUÉS):
 * si el usuario importó saves externos a la carpeta viva, el
 * snapshot los captura antes de que el restore los pueda pisar.
 *
 * Tiempo jugado: se mide en el launcher (desde JUGAR hasta volver a
 * la app). Best-effort: si el proceso muere, el tiempo de esa
 * partida no se registra. Se topa a 12 h por sesión de juego para
 * no sumar basura si el reloj cambia.
 *
 * Capítulo/progreso: campo MANUAL (el usuario lo escribe). Los saves
 * de Vita son binarios y en Etapa 1 no se decodifica el capítulo
 * desde ellos; no se inventa un capítulo leído del save.
 *
 * Llamar siempre desde Dispatchers.IO: hace I/O de archivos.
 */
class SessionManager(
    private val context: Context,
    private val library: GameLibraryRepository,
    private val achievements: AchievementRepository,
    private val touch: TouchLayoutRepository,
) {
    private val repo = SessionRepository(library.vitaRoot)

    private val _sessions = MutableStateFlow<List<GameSession>>(emptyList())
    val sessionsFlow: Flow<List<GameSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionIdFlow: Flow<String?> = _activeSessionId.asStateFlow()

    val activeSession: GameSession?
        get() = _sessions.value.firstOrNull { it.id == _activeSessionId.value }

    /** Tiempo de juego en curso (ms desde JUGAR), en memoria. */
    private var playStartMillis: Long? = null

    /** Recarga índice + activa desde disco; recalcula stats de savedata. */
    suspend fun refresh() {
        // BUG (auditoría): savedataStats y setActiveSessionId hacen I/O que
        // puede lanzar; refresh() se llama desde el ViewModel sin try/catch.
        // Cada sesión que falle se muestra con stats 0 en vez de tumbarlo todo.
        val sessions = repo.listSessions().map { session ->
            val stats = runCatching { repo.savedataStats(session.id) }.getOrDefault(0 to 0L)
            session.copy(savedataFiles = stats.first, savedataBytes = stats.second)
        }
        _sessions.value = sessions
        val activeId = repo.getActiveSessionId()
        if (sessions.any { it.id == activeId }) {
            _activeSessionId.value = activeId
        } else {
            // M5: la activa guardada ya no existe (índice corrupto o editado).
            // Se cae a la primera sesión, pero se registra: antes era
            // silencioso y el usuario jugaba otra partida sin saberlo.
            if (activeId != null) {
                LauncherLog.append(context, "session", "Sesión activa guardada ($activeId) no existe; se activó la primera de la lista.")
            }
            _activeSessionId.value = sessions.firstOrNull()?.id
        }
        if (_activeSessionId.value != null) {
            runCatching { repo.setActiveSessionId(_activeSessionId.value) }
        }
    }

    // Title ID del juego EXTRAÍDO, no del .pkg: las sesiones gestionan
    // la savedata del launcher, y con un .pkg los saves viven en Vita3K.
    // Si se usara el título del .pkg, se crearía una "Partida 1" vacía
    // y se harían snapshots de una savedata que no existe.
    private fun installedTitleId(): String? = try {
        library.scanStatus().extractedTitleId?.id
    } catch (_: Exception) {
        null
    }

    /**
     * Garantiza que exista al menos una sesión. Si no hay ninguna, crea
     * "Partida 1" con lo que haya en la savedata viva (migración de los
     * usuarios que ya jugaban sin sesiones). Devuelve la sesión activa.
     */
    suspend fun ensureDefaultSession(): GameSession? {
        // BUG (auditoría): se llama desde el init del ViewModel sin
        // try/catch; cualquier fallo de I/O tumbaba esa corrutina.
        return try {
            ensureDefaultSessionImpl()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureDefaultSessionImpl(): GameSession? {
        refresh()
        if (_sessions.value.isNotEmpty()) return activeSession
        val titleId = installedTitleId() ?: return null
        val now = System.currentTimeMillis()
        val session = GameSession(
            id = repo.newSessionId(),
            name = "Partida 1",
            titleId = titleId,
            createdAtMillis = now,
            lastPlayedAtMillis = 0L,
            playTimeMillis = 0L,
        )
        repo.saveSessions(listOf(session))
        repo.setActiveSessionId(session.id)
        _sessions.value = listOf(session)
        _activeSessionId.value = session.id
        snapshotActive()
        refresh()
        LauncherLog.append(context, "session", "Sesión por defecto creada: Partida 1 ($titleId)")
        return activeSession
    }

    /** Crea una sesión VACÍA nueva SIN borrar la anterior: snapshot primero, luego limpiar la viva. */
    suspend fun createSession(name: String): Result<GameSession> {
        // BUG (auditoría): devolvía Result pero el cuerpo podía lanzar
        // (snapshot/saveSessions) y tumbar la corrutina del llamador.
        return try {
            createSessionImpl(name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createSessionImpl(name: String): Result<GameSession> {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Ponle un nombre a la sesión."))
        val titleId = installedTitleId() ?: activeSession?.titleId ?: ""
        // Sin juego EXTRAÍDO no hay savedata en el launcher que gestionar:
        // con un .pkg los saves viven en Vita3K. Crear una sesión con el
        // Title ID vacío dejaba una sesión huérfana sin juego.
        if (titleId.isBlank()) {
            return Result.failure(
                IllegalStateException("Instala primero el juego extraído para crear sesiones. Con un .pkg los saves viven dentro de la app externa."),
            )
        }
        // 1. Snapshot de la sesión actual (si la hay): no se pierde nada.
        snapshotActive()
        // 2. Nueva sesión vacía, activa.
        val now = System.currentTimeMillis()
        val currentProfile = currentControlProfileId()
        val session = GameSession(
            id = repo.newSessionId(),
            name = cleanName,
            titleId = titleId,
            createdAtMillis = now,
            lastPlayedAtMillis = 0L,
            playTimeMillis = 0L,
        )
        val updated = _sessions.value + session
        repo.saveSessions(updated)
        repo.setActiveSessionId(session.id)
        _sessions.value = updated
        _activeSessionId.value = session.id
        // 3. La viva queda vacía (nuevo juego desde cero) y los logros a 0/27.
        if (titleId.isNotBlank()) repo.clearLiveSavedata(titleId)
        repo.writeSessionExtras(session.id, "", currentProfile)
        achievements.resetAll()
        LauncherLog.append(context, "session", "Nueva sesión creada: \"$cleanName\" (la anterior quedó guardada)")
        refresh()
        return Result.success(activeSession ?: session)
    }

    /** "Continuar" / "Cambiar sesión": guarda la actual y carga la otra. */
    suspend fun switchTo(id: String): Result<GameSession> {
        return try {
            switchToImpl(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun switchToImpl(id: String): Result<GameSession> {
        val target = _sessions.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        val installed = installedTitleId()
        if (target.titleId.isNotBlank() && installed != null && target.titleId != installed) {
            return Result.failure(
                IllegalStateException(
                    "Esa sesión es de ${target.titleId} y el juego instalado es $installed. " +
                        "Instala el juego de esa región para continuar esa sesión.",
                ),
            )
        }
        if (target.id == _activeSessionId.value) return Result.success(target)
        // H2: si el restore falla a mitad, la activa ya habría cambiado y la
        // viva podría quedar a medias. Se guarda la anterior para volver a
        // ella (id + savedata) antes de tocar nada.
        val previous = activeSession
        val previousId = _activeSessionId.value
        snapshotActive()
        try {
            repo.setActiveSessionId(target.id)
            _activeSessionId.value = target.id
            restoreSession(target)
        } catch (e: Exception) {
            // Rollback best-effort: volver a la sesión anterior con sus saves.
            runCatching {
                if (previous != null) {
                    repo.setActiveSessionId(previousId)
                    _activeSessionId.value = previousId
                    restoreSession(previous)
                }
            }
            throw e
        }
        updateSession(target.id) { it.copy(lastPlayedAtMillis = System.currentTimeMillis()) }
        LauncherLog.append(context, "session", "Cambiada a sesión \"${target.name}\"")
        refresh()
        return Result.success(target)
    }

    fun renameSession(id: String, name: String, chapter: String): Result<Unit> {
        return try {
            renameSessionImpl(id, name, chapter)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun renameSessionImpl(id: String, name: String, chapter: String): Result<Unit> {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("El nombre no puede estar vacío."))
        val found = _sessions.value.any { it.id == id }
        if (!found) return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        updateSession(id) { it.copy(name = cleanName, chapter = chapter.trim().take(80)) }
        LauncherLog.append(context, "session", "Sesión renombrada a \"$cleanName\"")
        return Result.success(Unit)
    }

    suspend fun duplicateSession(id: String): Result<GameSession> {
        return try {
            duplicateSessionImpl(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun duplicateSessionImpl(id: String): Result<GameSession> {
        val source = _sessions.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        val now = System.currentTimeMillis()
        // M8: el nombre "(copia)" puede colisionar con otra copia anterior;
        // se numera hasta que no exista.
        val existingNames = _sessions.value.map { it.name }.toSet()
        var copyName = (source.name + " (copia)").take(40)
        var n = 2
        while (copyName in existingNames) {
            copyName = (source.name + " (copia $n)").take(40)
            n++
        }
        val copy = source.copy(
            id = repo.newSessionId(),
            name = copyName,
            createdAtMillis = now,
            lastPlayedAtMillis = 0L,
            playTimeMillis = source.playTimeMillis,
        )
        try {
            repo.duplicateSessionDir(source.id, copy.id)
            val updated = _sessions.value + copy
            repo.saveSessions(updated)
            _sessions.value = updated
        } catch (e: Exception) {
            // M8: si el índice no se pudo guardar, la carpeta copiada
            // quedaría huérfana: se borra y se reporta el fallo.
            runCatching { repo.deleteSession(copy.id) }
            throw e
        }
        LauncherLog.append(context, "session", "Sesión duplicada: \"${copy.name}\"")
        refresh()
        return Result.success(copy)
    }

    /**
     * Borra una sesión con confirmación en la UI.
     * - Nunca borra la ÚNICA sesión sin avisar: si solo queda una,
     *   falla y la UI debe mostrar la advertencia.
     * - Si se borra la ACTIVA: primero se hace snapshot de la viva en
     *   ella, se borra su carpeta y se activa otra sesión (restaurándola).
     */
    suspend fun deleteSession(id: String): Result<Unit> {
        return try {
            deleteSessionImpl(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun deleteSessionImpl(id: String): Result<Unit> {
        val sessions = _sessions.value
        if (sessions.size <= 1) {
            return Result.failure(
                IllegalStateException(
                    "Es la única sesión que queda. Si la borras, se pierden sus saves. " +
                        "Expórtala antes o crea otra sesión.",
                ),
            )
        }
        val target = sessions.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        val wasActive = id == _activeSessionId.value
        if (wasActive) snapshotActive()
        repo.deleteSession(id)
        val remaining = sessions.filter { it.id != id }
        repo.saveSessions(remaining)
        _sessions.value = remaining
        if (wasActive) {
            val next = remaining.maxByOrNull { it.lastPlayedAtMillis } ?: remaining.first()
            repo.setActiveSessionId(next.id)
            _activeSessionId.value = next.id
            restoreSession(next)
        }
        LauncherLog.append(context, "session", "Sesión borrada: \"${target.name}\"")
        refresh()
        return Result.success(Unit)
    }

    /**
     * Activa la sesión correcta ANTES de lanzar el juego.
     * La llama Vita3KLauncher.launch: garantiza sesión por defecto,
     * snapshot de la viva y restore de la activa. Si falla, el
     * lanzamiento se bloquea con el motivo (mejor no lanzar con la
     * savedata equivocada que pisar una partida).
     */
    suspend fun activateForLaunch(): Result<Unit> {
        return try {
            ensureDefaultSession()
                ?: return Result.failure(IllegalStateException("No hay juego instalado: no hay sesión que activar."))
            snapshotActive()
            val active = activeSession
                ?: return Result.failure(IllegalStateException("No hay sesión activa."))
            restoreSession(active)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo activar la sesión: ${e.message}"))
        }
    }

    fun notePlayStarted() {
        playStartMillis = System.currentTimeMillis()
    }

    /**
     * Al volver del juego: suma el tiempo jugado a la sesión activa,
     * actualiza "última vez" y hace snapshot (auto-backup de lo jugado).
     * No hace nada si no hay una partida en curso registrada.
     */
    suspend fun notePlayEnded() {
        val start = playStartMillis ?: return
        playStartMillis = null
        val elapsed = (System.currentTimeMillis() - start).coerceIn(0L, 12 * 60 * 60 * 1000L)
        val active = activeSession ?: return
        // BUG (auditoría): updateSession/snapshot hacen I/O que puede lanzar
        // y se llama sin try/catch desde el ViewModel. El tiempo jugado es
        // best-effort: si falla, se pierde el registro, no la app.
        try {
            updateSession(active.id) {
                it.copy(
                    playTimeMillis = it.playTimeMillis + elapsed,
                    lastPlayedAtMillis = System.currentTimeMillis(),
                )
            }
            snapshotActive()
            refresh()
        } catch (_: Exception) {
            // Best-effort: no bloquear el regreso del juego por un fallo de disco.
        }
        LauncherLog.append(
            context, "session",
            "Partida terminada: +${elapsed / 60_000} min en \"${active.name}\" (snapshot guardado)",
        )
    }

    /** Borra la savedata viva Y la copia guardada de la sesión activa (para "Borrar saves"). */
    suspend fun clearActiveSessionSavedata() {
        try {
            val active = activeSession ?: return
            val titleId = installedTitleId() ?: active.titleId
            repo.clearLiveSavedata(titleId)
            repo.clearSessionSavedata(active.id)
            updateSession(active.id) { it.copy(savedataFiles = 0, savedataBytes = 0L) }
            refresh()
        } catch (_: Exception) {
            // Best-effort: el borrado de saves no debe tumbar la corrutina.
        }
    }

    /** Borra TODAS las sesiones (para "Borrar datos del juego"). */
    suspend fun deleteAllSessions() {
        playStartMillis = null
        runCatching { repo.deleteAll() }
        _sessions.value = emptyList()
        _activeSessionId.value = null
    }

    suspend fun exportSession(id: String, out: OutputStream): Result<Unit> {
        val session = _sessions.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        return try {
            if (id == _activeSessionId.value) snapshotActive()
            val fresh = _sessions.value.firstOrNull { it.id == id } ?: session
            repo.exportSession(fresh, out)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo exportar la sesión: ${e.message}"))
        }
    }

    suspend fun importSession(input: InputStream): Result<GameSession> {
        return try {
            val imported = repo.importSession(input)
            // M6: validar lo que trae el zip (titleId y nombre) antes de
            // registrarlo: parseLine ya valida rangos, pero el titleId debe
            // ser uno de los dos del juego y el nombre no puede ser vacío.
            if (TitleId.fromString(imported.titleId) == null) {
                return Result.failure(
                    IllegalArgumentException("La sesión importada es de otro juego (${imported.titleId}): solo se aceptan PCSE00480 / PCSB00598."),
                )
            }
            val clean = imported.copy(name = imported.name.trim().take(40).ifBlank { "Sesión importada" })
            val updated = _sessions.value + clean
            repo.saveSessions(updated)
            _sessions.value = updated
            LauncherLog.append(context, "session", "Sesión importada: \"${clean.name}\"")
            refresh()
            Result.success(clean)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo importar la sesión: ${e.message}"))
        }
    }

    /**
     * Respaldo de la sesión activa (snapshot viva + logros + perfil) antes
     * de una operación arriesgada (p. ej. importar saves). Devuelve false
     * si el respaldo falló: el llamador debe abortar en vez de seguir.
     */
    suspend fun backupActiveSession(): Boolean {
        return runCatching { snapshotActive(); true }.getOrDefault(false)
    }

    // --- Internos ---

    /** Snapshot: viva -> carpeta de la sesión activa + logros + perfil de control. */
    private suspend fun snapshotActive() {
        val active = activeSession ?: return
        val titleId = installedTitleId() ?: active.titleId
        if (titleId.isBlank()) return
        val stats = repo.snapshotSavedata(titleId, active.id)
        val unlocked = achievements.progressFlow.first().count { it.unlocked }
        val profileId = currentControlProfileId()
        // M38: si exportText() falla (DataStore corrupto), no debe abortar
        // el snapshot de saves: se conserva el texto ya guardado de la sesión.
        val achievementsText = runCatching { achievements.exportText() }
            .getOrDefault(repo.readAchievementsText(active.id))
        repo.writeSessionExtras(active.id, achievementsText, profileId)
        val fixedTitle = if (active.titleId.isBlank()) titleId else active.titleId
        updateSession(active.id) {
            it.copy(
                titleId = fixedTitle,
                savedataFiles = stats.first,
                savedataBytes = stats.second,
                unlockedTrophies = unlocked,
            )
        }
    }

    /** Restore: carpeta de la sesión -> viva + logros + perfil de control. */
    private suspend fun restoreSession(session: GameSession) {
        val titleId = installedTitleId() ?: session.titleId
        if (titleId.isBlank()) return
        repo.restoreSavedata(titleId, session.id)
        val text = repo.readAchievementsText(session.id)
        achievements.replaceAll(text)
        val profileId = repo.readControlProfileId(session.id)
        if (profileId != null) {
            val exists = touch.profilesFlow.first().any { it.id == profileId }
            if (exists) touch.setActiveProfile(profileId)
        }
    }

    private suspend fun currentControlProfileId(): String? {
        return try {
            touch.activeProfileIdFlow.first()
        } catch (_: Exception) {
            null
        }
    }

    private fun updateSession(id: String, transform: (GameSession) -> GameSession) {
        val updated = _sessions.value.map { if (it.id == id) transform(it) else it }
        _sessions.value = updated
        repo.saveSessions(updated)
    }
}
