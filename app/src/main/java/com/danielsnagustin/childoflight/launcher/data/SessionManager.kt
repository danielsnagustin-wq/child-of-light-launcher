package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.GameSession
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_20
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * snapshot de la sesión actual (savedata viva + trofeos del núcleo
 * + logros locales + perfil de control) en su carpeta. Nunca se
 * sobrescribe sin avisar: el cambio de sesión guarda la actual y
 * luego carga la otra.
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
    // Ronda 0.2.65 (T-P2-3): StateFlow (no solo Flow) para que el
    // informe de bug pueda leer los nombres de sesión de forma
    // síncrona al exportar y sanitizarlos; los consumidores como Flow
    // no cambian.
    val sessionsFlow: StateFlow<List<GameSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionIdFlow: Flow<String?> = _activeSessionId.asStateFlow()

    val activeSession: GameSession?
        get() = _sessions.value.firstOrNull { it.id == _activeSessionId.value }

    /** Tiempo de juego en curso (ms desde JUGAR), en memoria. */
    private var playStartMillis: Long? = null

    /**
     * R2-P2-33: serializa todas las escrituras del índice de sesiones.
     * Cada operación lee `_sessions.value`, transforma y escribe; sin
     * este mutex dos toques rápidos (Duplicar + Crear, Renombrar +
     * cambio de sesión) podían entrelazar lectura-modificación-
     * escritura y perder una sesión del índice. El mutex solo se toma
     * en los tramos síncronos de lectura-escritura (nunca se mantiene
     * durante un snapshot/restore, que suspenden); `updateSession` es
     * el punto único de escritura del índice en memoria y siempre lo
     * toma. Por eso `updateSession`, `renameSession` y
     * `notePlayStarted` son suspend: todos sus llamadores ya corrían
     * en corrutinas (ViewModel / PauseMenuActivity).
     */
    private val indexMutex = Mutex()

    /**
     * R2-P1-11(b): huella de la savedata viva en el último snapshot
     * (archivos, bytes, última modificación). Permite que
     * `notePlayEnded()` sin medición abierta haga el snapshot final
     * solo si la viva cambió desde el último snapshot, en vez de
     * copiar toda la savedata en cada vuelta al launcher. En memoria:
     * tras reiniciar el proceso es null y el primer snapshot copia
     * siempre (opción segura).
     */
    private var lastSnapshotFingerprint: Triple<Int, Long, Long>? = null

    /**
     * Vía del juego en curso (P2-A7): la embebida termina cuando su
     * Activity se cierra, así que al volver al launcher la medición se
     * puede cerrar; la externa sigue viva cuando el usuario vuelve al
     * launcher (Recientes) sin salir del juego. La fija quien llama a
     * notePlayStarted(); por defecto es la vía embebida (comportamiento
     * actual) hasta que el ViewModel pase la vía real (oleada 2).
     */
    private var playExternal: Boolean = false

    /** Recarga índice + activa desde disco; recalcula stats de savedata. */
    suspend fun refresh() {
        // R2-P2-33: toda la recarga va bajo indexMutex: sin él un
        // refresh() concurrente con una creación podía leer el índice
        // de disco a medias y borrar la sesión nueva de la memoria.
        // Ningún llamador mantiene el mutex al llamar a refresh().
        indexMutex.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        // savedataStats y setActiveSessionId hacen I/O que puede lanzar;
        // por eso van con runCatching: cada sesión que falle se muestra
        // con stats 0 en vez de tumbarlo todo.
        val sessions = repo.listSessions().map { session ->
            // R2-P2-32: un .bak-* huérfano (proceso muerto a mitad de
            // snapshot) se restaura o se limpia antes de medir: sin esto
            // refresh() mostraba la sesión con 0 archivos ("vacía")
            // aunque existiera el respaldo.
            runCatching { repo.reconcileSavedataBackups(session.id) }
            val stats = runCatching { repo.savedataStats(session.id) }.getOrDefault(0 to 0L)
            session.copy(savedataFiles = stats.first, savedataBytes = stats.second)
        }
        _sessions.value = sessions
        val activeId = repo.getActiveSessionId()
        // R2-P2-36: si la activa guardada ya no existe se cae a la
        // primera, pero hay que restaurar su savedata: sin esto la viva
        // seguía teniendo los saves de la sesión desaparecida y el
        // siguiente snapshot los sobrescribía sobre la primera sesión.
        var fellBackToFirst = false
        if (sessions.any { it.id == activeId }) {
            _activeSessionId.value = activeId
        } else {
            // M5: la activa guardada ya no existe (índice corrupto o editado).
            // Se cae a la primera sesión, pero se registra: antes era
            // silencioso y el usuario jugaba otra partida sin saberlo.
            if (activeId != null) {
                LauncherLog.append(context, "session", "Sesión activa guardada ($activeId) no existe; se activó la primera de la lista.")
                fellBackToFirst = true
            }
            _activeSessionId.value = sessions.firstOrNull()?.id
        }
        if (_activeSessionId.value != null) {
            runCatching { repo.setActiveSessionId(_activeSessionId.value) }
        }
        if (fellBackToFirst) {
            // Solo en el camino de caída (id guardado que ya no existe):
            // si nunca hubo activa guardada no se toca la viva.
            val newlyActive = activeSession
            if (newlyActive != null) {
                runCatching { restoreSession(newlyActive) }.onFailure {
                    LauncherLog.append(context, "session", "No se pudo restaurar la savedata de la sesión activada (${newlyActive.name}): ${it.message}")
                }
            }
        }
    }

    // Title ID del juego EXTRAÍDO, no del .pkg: las sesiones gestionan
    // la savedata del launcher, y con un .pkg los saves viven en Vita3K.
    // Si se usara el título del .pkg, se crearía una "Partida 1" vacía
    // y se harían snapshots de una savedata que no existe.
    private fun installedTitleId(): String? = lightExtractedTitleId()

    /**
     * R4-P0-8: Title ID del juego EXTRAÍDO por lectura LIGERA, sin
     * scanStatus() completo. Antes cada llamada era un escaneo entero
     * (tamaños, DLCs, saves, .pkg, MediaStore) solo para devolver un
     * String, y se llama desde al menos 8 sitios. La lectura ligera
     * es EXACTA para este dato: scanStatus decide "extraído" con esta
     * misma comprobación (app/<TITLE>/ con eboot.bin, sin importar
     * mayúsculas) en el ux0 efectivo; aquí se hace solo eso, sin medir
     * nada. Pública para que el ViewModel (freshExtractedTitleId) use
     * la misma lectura sin otro escaneo.
     */
    fun lightExtractedTitleId(): String? = try {
        TitleId.entries.firstOrNull { candidate ->
            val dir = library.appDir(candidate.id)
            dir.exists() && ImportTreeSearch.findEboot(dir) != null
        }?.id
    } catch (_: Exception) {
        null
    }

    /**
     * Garantiza que exista al menos una sesión. Si no hay ninguna, crea
     * "Partida 1" con lo que haya en la savedata viva (migración de los
     * usuarios que ya jugaban sin sesiones). Devuelve la sesión activa.
     */
    suspend fun ensureDefaultSession(): GameSession? {
        // Cualquier fallo de I/O se contiene aquí y devuelve null: el
        // init del ViewModel no debe tumbarse por un fallo de disco.
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
        // R2-P2-33: la escritura del índice va bajo indexMutex.
        indexMutex.withLock {
            repo.saveSessions(listOf(session))
            _sessions.value = listOf(session)
        }
        repo.setActiveSessionId(session.id)
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
        // R2-P2-33: leer la lista actual y escribir el índice es una
        // sola operación atómica bajo indexMutex: dos creaciones
        // concurrentes no pueden leer la misma lista y perder una.
        indexMutex.withLock {
            val fresh = _sessions.value + session
            repo.saveSessions(fresh)
            _sessions.value = fresh
        }
        repo.setActiveSessionId(session.id)
        _activeSessionId.value = session.id
        // 3. La viva queda vacía (nuevo juego desde cero) y los logros a 0/20.
        if (titleId.isNotBlank()) repo.clearLiveSavedata(titleId)
        // Ronda 0.2.70, P1-6: la sesión nueva también empieza con el
        // TROPUSR.DAT vacío (el snapshot de la anterior ya se hizo
        // arriba, en snapshotActive); sin esto heredaba sus trofeos.
        if (titleId.isNotBlank()) repo.clearLiveTrophyData(titleId)
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

    // R2-P2-33: suspend para que updateSession pueda tomar indexMutex.
    // Todos los llamadores ya corren en corrutinas (ViewModel).
    suspend fun renameSession(id: String, name: String, chapter: String): Result<Unit> {
        return try {
            renameSessionImpl(id, name, chapter)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun renameSessionImpl(id: String, name: String, chapter: String): Result<Unit> {
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
        // R2-P1-12: si se duplica la sesión ACTIVA, primero snapshot de
        // la viva: sin esto la copia nacía con los saves y logros del
        // último snapshot, no con la partida actual (mismo patrón que
        // exportSession).
        if (id == _activeSessionId.value) snapshotActive()
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
            // R2-P2-33: leer la lista actual y escribir el índice es una
            // sola operación atómica bajo indexMutex.
            indexMutex.withLock {
                val fresh = _sessions.value + copy
                repo.saveSessions(fresh)
                _sessions.value = fresh
            }
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
        if (wasActive) {
            // R2-P1-13: la siguiente sesión se RESTAURA ANTES de borrar
            // la activa de disco. Antes el orden era borrar -> activar
            // -> restaurar, y si el restore fallaba la sesión ya estaba
            // borrada, el mensaje mentía ("no se pudo borrar") y la viva
            // seguía con los saves de la borrada (el siguiente JUGAR los
            // sobrescribía sobre la sesión siguiente). Si el restore
            // falla aquí no se borra nada y se devuelve el fallo real.
            // El rollback de la id activa basta: restoreSavedata ya
            // restaura su propio respaldo de la viva si la copia falla.
            val remaining = sessions.filter { it.id != id }
            val next = remaining.maxByOrNull { it.lastPlayedAtMillis } ?: remaining.first()
            try {
                repo.setActiveSessionId(next.id)
                _activeSessionId.value = next.id
                restoreSession(next)
            } catch (e: Exception) {
                runCatching {
                    repo.setActiveSessionId(id)
                    _activeSessionId.value = id
                }
                return Result.failure(
                    IllegalStateException(
                        "No se pudo activar la siguiente sesión (\"${next.name}\"): ${e.message}. " +
                            "La sesión \"${target.name}\" NO se borró.",
                    ),
                )
            }
        }
        // La siguiente ya está activa y restaurada (o no se borraba la
        // activa): ahora sí se borra. Si ESTE paso falla, el mensaje lo
        // distingue del fallo de activación anterior.
        try {
            repo.deleteSession(id)
            // R2-P2-33: la lista restante se relee dentro del mutex para
            // no perder una sesión creada en paralelo.
            indexMutex.withLock {
                val fresh = _sessions.value.filter { it.id != id }
                repo.saveSessions(fresh)
                _sessions.value = fresh
            }
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException(
                    "No se pudo borrar la sesión \"${target.name}\" de disco o del índice: ${e.message}.",
                ),
            )
        }
        LauncherLog.append(context, "session", "Sesión borrada: \"${target.name}\"")
        refresh()
        return Result.success(Unit)
    }

    /**
     * Activa la sesión correcta ANTES de lanzar el juego.
     * La llama Vita3KLauncher.launch: garantiza sesión por defecto,
     * snapshot de la viva y restore de la activa. Si falla la savedata,
     * el lanzamiento se bloquea con el motivo (mejor no lanzar con la
     * savedata equivocada que pisar una partida); el fallo de logros
     * o de perfil de control no bloquea (P2-A3: restoreSession).
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

    /**
     * R2-P1-11(a): si ya hay una medición abierta (JUGAR otra vez con
     * el juego en segundo plano, o volver por Recientes), se cierra
     * primero: el tiempo transcurrido se acumula en la sesión activa
     * ANTES de sobrescribir el inicio. Antes se perdía ese intervalo.
     * R2-P2-33: suspend para que updateSession pueda tomar indexMutex;
     * los dos llamadores (ViewModel.play / startBenchmark) ya corren
     * en corrutinas. No se hace snapshot aquí: el juego sigue vivo y
     * copiar la savedata mientras escribe es arriesgado; el snapshot
     * lo hace notePlayEnded().
     */
    suspend fun notePlayStarted(external: Boolean = false) {
        val start = playStartMillis
        if (start != null) {
            val elapsed = (System.currentTimeMillis() - start).coerceIn(0L, 12 * 60 * 60 * 1000L)
            val active = activeSession
            if (active != null && elapsed > 0) {
                runCatching {
                    updateSession(active.id) {
                        it.copy(
                            playTimeMillis = it.playTimeMillis + elapsed,
                            lastPlayedAtMillis = System.currentTimeMillis(),
                        )
                    }
                }
                LauncherLog.append(
                    context, "session",
                    "Medición anterior cerrada al relanzar: +${elapsed / 60_000} min en \"${active.name}\"",
                )
            }
        }
        playStartMillis = System.currentTimeMillis()
        playExternal = external
    }

    /**
     * Al volver del juego: suma el tiempo jugado a la sesión activa,
     * actualiza "última vez" y hace snapshot (auto-backup de lo jugado).
     * No hace nada si no hay una partida en curso registrada.
     *
     * P2-A7: en la vía EXTERNA el usuario puede volver al launcher sin
     * salir del juego; cerrar aquí cortaría el tiempo de lo que siga
     * jugando fuera. En ese caso se registra lo jugado hasta ahora
     * (checkpoint: tiempo + snapshot) y la medición SIGUE desde este
     * momento, así que el próximo notePlayEnded sumará solo lo jugado
     * desde aquí. Límite conocido (best-effort): el tiempo pasado en
     * el launcher entre dos checkpoints también se suma, y la medición
     * solo se cierra al lanzar de nuevo o al morir el proceso, porque
     * Android no permite saber si la app externa sigue en primer
     * plano sin el permiso especial de estadísticas de uso. En la vía
     * embebida el comportamiento no cambia: la partida terminó y la
     * medición se cierra.
     */
    suspend fun notePlayEnded() {
        val start = playStartMillis
        if (start == null) {
            // R2-P1-11(b): sin medición abierta (se volvió al juego por
            // Recientes y se salió por el menú de pausa) antes esto era
            // un no-op y no había snapshot final: la copia de la sesión
            // quedaba con los saves de antes de pulsar Inicio. Ahora se
            // hace snapshot aunque no haya tiempo que sumar, pero solo
            // si la viva cambió desde el último snapshot: snapshotActive
            // copia TODA la savedata cada vez, y hacerlo en cada vuelta
            // al launcher sería copiar MBs por nada. La huella (nº de
            // archivos, bytes, última modificación) es barata de
            // calcular (un paseo por la carpeta viva) y decide.
            val active = activeSession
            if (active != null) {
                runCatching { snapshotActiveIfLiveChanged() }.onFailure {
                    LauncherLog.append(context, "session", "No se pudo guardar el snapshot final al salir del juego: ${it.message}")
                }
            }
            return
        }
        val elapsed = (System.currentTimeMillis() - start).coerceIn(0L, 12 * 60 * 60 * 1000L)
        val active = activeSession
        if (active == null) {
            // Sin sesión activa no hay dónde registrar el tiempo. En la
            // vía embebida la partida terminó y se cierra la medición;
            // en la externa se reinicia el reloj para no acumular
            // tiempo sin sesión mientras el juego pueda seguir.
            if (playExternal) {
                playStartMillis = System.currentTimeMillis()
            } else {
                playStartMillis = null
                playExternal = false
            }
            return
        }
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
        } catch (e: Exception) {
            // Best-effort: no bloquear el regreso del juego por un fallo de disco.
            // P2-41: el fallo queda en el log (antes se tragaba sin rastro).
            LauncherLog.append(context, "session", "No se pudo guardar el snapshot al volver del juego: ${e.message}")
        }
        if (playExternal) {
            playStartMillis = System.currentTimeMillis()
            LauncherLog.append(
                context, "session",
                "Partida en curso (app externa): +${elapsed / 60_000} min en \"${active.name}\" (checkpoint; la medición sigue)",
            )
        } else {
            playStartMillis = null
            playExternal = false
            LauncherLog.append(
                context, "session",
                "Partida terminada: +${elapsed / 60_000} min en \"${active.name}\" (snapshot guardado)",
            )
        }
    }

    /** Borra TODAS las sesiones (para "Borrar datos del juego"). */
    suspend fun deleteAllSessions() {
        playStartMillis = null
        playExternal = false
        lastSnapshotFingerprint = null
        runCatching { repo.deleteAll() }
        indexMutex.withLock {
            _sessions.value = emptyList()
            _activeSessionId.value = null
        }
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
            // R2-P1-14: el conteo de logros del zip NO se confía: un zip
            // manipulado podía traer 20/20 con logros vacíos. Se
            // recomputa desde el achievements.txt importado.
            // R7-P2-01: el recompute usa decodeVerified (firma válida):
            // un achievements.txt fabricado a mano cuenta 0, igual que
            // el restore (replaceAll) que viene después lo descarta.
            val importedText = repo.readAchievementsText(imported.id)
            val recomputedUnlocked = achievements.decodeVerified(importedText)
                .keys.count { n -> TROPHIES_20.any { it.number == n } }
            val clean = imported.copy(
                name = imported.name.trim().take(40).ifBlank { "Sesión importada" },
                unlockedTrophies = recomputedUnlocked,
            )
            // R2-P1-14: importar ACTIVA la sesión importada: snapshot de
            // la activa actual (como switchTo) y luego restore de la
            // importada. Antes la sesión quedaba en la lista pero JUGAR
            // seguía jugando con los saves de la anterior.
            val previous = activeSession
            val previousId = _activeSessionId.value
            snapshotActive()
            // R2-P2-33: leer la lista actual y escribir el índice es una
            // sola operación atómica bajo indexMutex.
            indexMutex.withLock {
                val fresh = _sessions.value + clean
                repo.saveSessions(fresh)
                _sessions.value = fresh
            }
            try {
                repo.setActiveSessionId(clean.id)
                _activeSessionId.value = clean.id
                restoreSession(clean)
            } catch (e: Exception) {
                // La activación falló: rollback a la sesión anterior con
                // sus saves (mismo patrón que switchTo). La importada
                // QUEDA en el índice (no se borra lo importado) pero no
                // activa: la viva no queda a medias.
                runCatching {
                    if (previous != null) {
                        repo.setActiveSessionId(previousId)
                        _activeSessionId.value = previousId
                        restoreSession(previous)
                    }
                }
                LauncherLog.append(context, "session", "Sesión importada pero no se pudo activar: ${e.message}")
                return Result.failure(
                    IllegalStateException(
                        "La sesión \"${clean.name}\" se importó pero no se pudo activar: ${e.message}. " +
                            "Sigue en la lista: pulsa Continuar sobre ella para jugarla.",
                    ),
                )
            }
            LauncherLog.append(context, "session", "Sesión importada y activa: \"${clean.name}\"")
            refresh()
            Result.success(clean)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No se pudo importar la sesión: ${e.message}"))
        }
    }

    /**
     * R2-P2-30: recomputa el contador `unlockedTrophies` de la sesión
     * activa desde el almacén global de logros y persiste el índice
     * (y el achievements.txt de la sesión). Lo llama el ViewModel tras
     * `resetTrophies`, `importAchievements` y (R7-P2-05) al final de
     * `syncTrophiesFromGame`: sin esto la lista de sesiones mostraba
     * "Logros: X/20" viejo hasta el próximo snapshot.
     * Best-effort: si falla, el contador queda viejo pero la app sigue.
     */
    suspend fun refreshActiveTrophyCount() {
        val active = activeSession ?: return
        try {
            snapshotExtras(active, active.savedataFiles to active.savedataBytes)
        } catch (e: Exception) {
            // Best-effort: no bloquear al llamador por un fallo de disco.
            // P2-41: el fallo queda en el log (antes se tragaba sin rastro).
            LauncherLog.append(context, "session", "No se pudo actualizar el contador de trofeos de la sesión activa: ${e.message}")
        }
    }

    /**
     * Respaldo de la sesión activa (snapshot viva + logros + perfil) antes
     * de una operación arriesgada (p. ej. importar saves). Devuelve false
     * si el respaldo falló: el llamador debe abortar en vez de seguir.
     */
    suspend fun backupActiveSession(): Boolean {
        // P1-2: sin sesión activa no hay nada que respaldar: devolver
        // false para que el botón nunca afirme un guardado que no
        // ocurrió. Antes snapshotActive() no hacía nada con
        // activeSession == null y aun así se devolvía true
        // ("Sesión guardada.").
        if (activeSession == null) return false
        return runCatching { snapshotActive(); true }.getOrDefault(false)
    }

    // --- Internos ---

    /** Snapshot: viva -> carpeta de la sesión activa + logros + perfil de control. */
    private suspend fun snapshotActive() {
        val active = activeSession ?: return
        val titleId = installedTitleId() ?: active.titleId
        if (titleId.isBlank()) return
        val stats = repo.snapshotSavedata(titleId, active.id)
        // Ronda 0.2.70, P1-6: el TROPUSR.DAT del núcleo también se
        // snapshottea (si no, era compartido y la sesión siguiente
        // heredaba los trofeos). Best-effort: un fallo de disco en
        // los trofeos no debe abortar el snapshot de la savedata.
        runCatching { repo.snapshotTrophyData(titleId, active.id) }.onFailure {
            LauncherLog.append(context, "session", "No se pudieron guardar los trofeos del núcleo de la sesión \"${active.name}\": ${it.message}")
        }
        // R2-P1-11(b): la viva acaba de copiarse: su huella es la del
        // snapshot. Un snapshot posterior sin cambios puede saltarse la
        // copia (ver snapshotActiveIfLiveChanged).
        lastSnapshotFingerprint = runCatching { repo.liveSavedataFingerprint(titleId) }.getOrNull()
        snapshotExtras(active, stats)
    }

    /**
     * R2-P1-11(b): snapshot final cuando no hay medición abierta, pero
     * solo si la viva cambió desde el último snapshot. La parte barata
     * (logros + perfil + contador) se actualiza siempre; la copia
     * completa de la savedata solo si la huella viva difiere. Coste de
     * la comprobación: un paseo por la carpeta viva (KBs-MBs de
     * metadatos), frente a copiarla entera en cada vuelta al launcher.
     * Sin huella previa (proceso recién arrancado) se copia siempre.
     */
    private suspend fun snapshotActiveIfLiveChanged() {
        val active = activeSession ?: return
        val titleId = installedTitleId() ?: active.titleId
        if (titleId.isBlank()) return
        val liveFingerprint = runCatching { repo.liveSavedataFingerprint(titleId) }.getOrNull()
        val previousFingerprint = lastSnapshotFingerprint
        if (liveFingerprint != null && previousFingerprint != null && liveFingerprint == previousFingerprint) {
            // La viva no cambió: solo refrescar logros/perfil/contador.
            // Los trofeos del núcleo SÍ se snapshottean siempre (son
            // KBs y pueden haber cambiado sin tocar la savedata).
            runCatching { repo.snapshotTrophyData(titleId, active.id) }.onFailure {
                LauncherLog.append(context, "session", "No se pudieron guardar los trofeos del núcleo de la sesión \"${active.name}\": ${it.message}")
            }
            snapshotExtras(active, active.savedataFiles to active.savedataBytes)
            return
        }
        snapshotActive()
    }

    /**
     * Parte barata del snapshot (logros + perfil de control + stats de
     * la sesión en el índice). La comparten `snapshotActive` (tras
     * copiar la savedata) y `refreshActiveTrophyCount` (que no toca la
     * savedata). `stats` son los (archivos, bytes) que se persisten.
     */
    private suspend fun snapshotExtras(active: GameSession, stats: Pair<Int, Long>) {
        val unlocked = achievements.progressFlow.first().count { it.unlocked }
        val profileId = currentControlProfileId()
        // M38: si exportText() falla (DataStore corrupto), no debe abortar
        // el snapshot de saves: se conserva el texto ya guardado de la sesión.
        val achievementsText = runCatching { achievements.exportText() }
            .getOrDefault(repo.readAchievementsText(active.id))
        repo.writeSessionExtras(active.id, achievementsText, profileId)
        val fixedTitle = if (active.titleId.isBlank()) installedTitleId() ?: active.titleId else active.titleId
        updateSession(active.id) {
            it.copy(
                titleId = fixedTitle,
                savedataFiles = stats.first,
                savedataBytes = stats.second,
                unlockedTrophies = unlocked,
            )
        }
    }

    /**
     * Restore: carpeta de la sesión -> viva + logros + perfil de control.
     *
     * P2-A3: la savedata es lo crítico y su fallo sigue bloqueando el
     * lanzamiento (o revierte el cambio de sesión); los logros y el
     * perfil de control son best-effort: un DataStore corrupto o un
     * fallo de I/O al leerlos no debe impedir jugar con la savedata
     * intacta. Esos fallos solo se registran en LauncherLog y el
     * lanzamiento continúa.
     */
    private suspend fun restoreSession(session: GameSession) {
        val titleId = installedTitleId() ?: session.titleId
        if (titleId.isBlank()) return
        repo.restoreSavedata(titleId, session.id)
        // Ronda 0.2.70, P1-6: restore del TROPUSR.DAT de la sesión
        // ANTES de lanzar el juego (esta función corre al cambiar o
        // crear sesión, nunca con el juego en curso). Best-effort
        // como los logros: un fallo no bloquea el lanzamiento; la
        // captura de salida ya fue al snapshot de la sesión que se
        // estaba jugando (snapshotActive antes del cambio).
        runCatching { repo.restoreTrophyData(titleId, session.id) }.onFailure {
            LauncherLog.append(context, "session", "No se pudieron restaurar los trofeos del núcleo de la sesión \"${session.name}\": ${it.message}")
        }
        // R2-P1-11(b): tras el restore la viva ES la copia de la sesión:
        // su huella es la del último snapshot válido, así que un
        // snapshot posterior sin cambios puede saltarse la copia.
        lastSnapshotFingerprint = runCatching { repo.liveSavedataFingerprint(titleId) }.getOrNull()
        restoreSessionExtras(session)
    }

    /** Logros + perfil de control de la sesión: best-effort, nunca bloquea. */
    private suspend fun restoreSessionExtras(session: GameSession) {
        runCatching {
            val text = repo.readAchievementsText(session.id)
            achievements.replaceAll(text)
        }.onFailure {
            LauncherLog.append(context, "session", "No se pudieron restaurar los logros de la sesión \"${session.name}\": ${it.message}")
        }
        runCatching {
            val profileId = repo.readControlProfileId(session.id)
            if (profileId != null) {
                val exists = touch.profilesFlow.first().any { it.id == profileId }
                if (exists) touch.setActiveProfile(profileId)
            }
        }.onFailure {
            LauncherLog.append(context, "session", "No se pudo restaurar el perfil de control de la sesión \"${session.name}\": ${it.message}")
        }
    }

    private suspend fun currentControlProfileId(): String? {
        return try {
            touch.activeProfileIdFlow.first()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Punto único de escritura del índice en memoria + disco.
     * R2-P2-33: la lectura de `_sessions.value`, la transformación y la
     * escritura van juntas bajo indexMutex: sin él dos operaciones
     * concurrentes podían leer la misma lista y perder una sesión.
     */
    private suspend fun updateSession(id: String, transform: (GameSession) -> GameSession) {
        indexMutex.withLock {
            val updated = _sessions.value.map { if (it.id == id) transform(it) else it }
            _sessions.value = updated
            repo.saveSessions(updated)
        }
    }
}
