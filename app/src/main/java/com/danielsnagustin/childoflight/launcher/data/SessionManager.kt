package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.GameSession
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
        val sessions = repo.listSessions().map { session ->
            val stats = repo.savedataStats(session.id)
            session.copy(savedataFiles = stats.first, savedataBytes = stats.second)
        }
        _sessions.value = sessions
        val activeId = repo.getActiveSessionId()
        _activeSessionId.value = if (sessions.any { it.id == activeId }) activeId else sessions.firstOrNull()?.id
        if (_activeSessionId.value != null) repo.setActiveSessionId(_activeSessionId.value)
    }

    private fun installedTitleId(): String? = try {
        library.scanStatus().titleId?.id
    } catch (_: Exception) {
        null
    }

    /**
     * Garantiza que exista al menos una sesión. Si no hay ninguna, crea
     * "Partida 1" con lo que haya en la savedata viva (migración de los
     * usuarios que ya jugaban sin sesiones). Devuelve la sesión activa.
     */
    suspend fun ensureDefaultSession(): GameSession? {
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
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Ponle un nombre a la sesión."))
        val titleId = installedTitleId() ?: activeSession?.titleId ?: ""
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
        snapshotActive()
        repo.setActiveSessionId(target.id)
        _activeSessionId.value = target.id
        restoreSession(target)
        updateSession(target.id) { it.copy(lastPlayedAtMillis = System.currentTimeMillis()) }
        LauncherLog.append(context, "session", "Cambiada a sesión \"${target.name}\"")
        refresh()
        return Result.success(target)
    }

    fun renameSession(id: String, name: String, chapter: String): Result<Unit> {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("El nombre no puede estar vacío."))
        val found = _sessions.value.any { it.id == id }
        if (!found) return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        updateSession(id) { it.copy(name = cleanName, chapter = chapter.trim().take(80)) }
        LauncherLog.append(context, "session", "Sesión renombrada a \"$cleanName\"")
        return Result.success(Unit)
    }

    suspend fun duplicateSession(id: String): Result<GameSession> {
        val source = _sessions.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Esa sesión ya no existe."))
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = repo.newSessionId(),
            name = (source.name + " (copia)").take(40),
            createdAtMillis = now,
            lastPlayedAtMillis = 0L,
            playTimeMillis = source.playTimeMillis,
        )
        repo.duplicateSessionDir(source.id, copy.id)
        val updated = _sessions.value + copy
        repo.saveSessions(updated)
        _sessions.value = updated
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
        updateSession(active.id) {
            it.copy(
                playTimeMillis = it.playTimeMillis + elapsed,
                lastPlayedAtMillis = System.currentTimeMillis(),
            )
        }
        snapshotActive()
        refresh()
        LauncherLog.append(
            context, "session",
            "Partida terminada: +${elapsed / 60_000} min en \"${active.name}\" (snapshot guardado)",
        )
    }

    /** Borra la savedata viva Y la copia guardada de la sesión activa (para "Borrar saves"). */
    suspend fun clearActiveSessionSavedata() {
        val active = activeSession ?: return
        val titleId = installedTitleId() ?: active.titleId
        repo.clearLiveSavedata(titleId)
        repo.clearSessionSavedata(active.id)
        updateSession(active.id) { it.copy(savedataFiles = 0, savedataBytes = 0L) }
        refresh()
    }

    /** Borra TODAS las sesiones (para "Borrar datos del juego"). */
    suspend fun deleteAllSessions() {
        playStartMillis = null
        repo.deleteAll()
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
            val updated = _sessions.value + imported
            repo.saveSessions(updated)
            _sessions.value = updated
            LauncherLog.append(context, "session", "Sesión importada: \"${imported.name}\"")
            refresh()
            Result.success(imported)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo importar la sesión: ${e.message}"))
        }
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
        repo.writeSessionExtras(active.id, achievements.exportText(), profileId)
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
