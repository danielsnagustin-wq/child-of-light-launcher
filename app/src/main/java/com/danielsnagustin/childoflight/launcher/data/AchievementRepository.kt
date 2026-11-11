package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_20
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Crash al abrir (run #80): mismo arreglo que UiPreferencesRepository
// (ver su comentario): archivo preferences_pb corrupto -> el
// corruptionHandler lo reemplaza por vacío y el catch cubre otros
// fallos de lectura con defaults (todos los trofeos bloqueados).
private val Context.achievementsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "col_achievements",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Progreso de logros LOCAL, en DataStore.
 *
 * Formato guardado: una línea por trofeo desbloqueado,
 * "numero|epochMillis", separadas por ";".
 * Es el mismo formato que se exporta/importa junto con los saves,
 * para que el usuario pueda respaldar su progreso.
 *
 * La fuente de verdad para MOSTRAR el progreso es este DataStore.
 * El directorio de trofeos de Vita3K (ux0/user/00/trophy/) se lee
 * solo como referencia (ver TrophyStoreReader y el diagnóstico TRP
 * en Logros). La sincronización automática existe desde la 0.2.57
 * (puente JNI: EmbeddedGameActivity captura las colecciones al salir
 * del juego y LauncherViewModel.syncTrophiesFromGame las aplica al
 * volver); este repositorio solo guarda lo que la sync desbloquea.
 *
 * MIGRACIÓN 27 -> 20 (0.2.57): la lista vieja tenía 27 trofeos y la
 * correcta de Vita tiene 20, RENUMERADA del 6 al 20 (p. ej. el viejo
 * 7 "Captura de un Capilli" ahora es el 6, y el viejo 19 "Furia
 * alada" ahora es el 17). Las claves guardadas son números de la
 * lista vieja: las 21-27 se descartan solas por el filtro de
 * TROPHIES_20, pero las 6-20 apuntarían a trofeos DISTINTOS y no
 * se puede confiar en ellas. Ningún desbloqueo legítimo pudo
 * existir aún: la sincronización con el núcleo no existía y el
 * marcado manual se eliminó, así que no se pierde progreso real.
 * Por eso la migración BORRA el progreso guardado UNA vez: la
 * clave `trophy_list_version` (por defecto 0) se lee antes de
 * cada acceso; si es menor que TROPHY_LIST_VERSION (2), se borra
 * el texto de desbloqueos y se guarda la versión 2. Las lecturas
 * y escrituras posteriores ya ven la versión 2 y no tocan nada.
 * La decisión (borrar) y su cálculo viven en el companion para
 * poder probarlos sin Android (ver AchievementRepositoryTest en
 * AchievementsAndTouchTest.kt): el edit en DataStore solo se
 * puede verificar en el teléfono.
 */
class AchievementRepository(private val context: Context) {

    private object Keys {
        val UNLOCKED = stringPreferencesKey("unlocked_trophies")
        val VERSION = intPreferencesKey("trophy_list_version")
    }

    val progressFlow: Flow<List<TrophyProgress>> = flow {
        migrateTrophyListIfNeeded()
        emitAll(
            context.achievementsDataStore.data.map { prefs ->
                val unlocked = decode(prefs[Keys.UNLOCKED] ?: "")
                TROPHIES_20.map { trophy ->
                    TrophyProgress(trophy, unlocked.containsKey(trophy.number), unlocked[trophy.number])
                }
            }.catch { e ->
                Log.w("ChildOfLight", "DataStore logros ilegible, usando bloqueados: ${e.message}")
                emit(TROPHIES_20.map { TrophyProgress(it, false, null) })
            },
        )
    }

    /** Serializa TODAS las escrituras del progreso (unlock, resetAll,
     * replaceAll, importText). R2-P2-33: antes solo unlock() lo usaba y
     * un resetAll concurrente con un unlock podía resucitar un trofeo
     * (la comprobación previa de unlock ya había pasado cuando el
     * edit{} del reset borraba). DataStore serializa los edit{} entre
     * sí, pero no el comprobar-y-actuar de unlock(). */
    private val unlockMutex = Mutex()

    /**
     * Migración 27 -> 20: si la versión guardada es menor que
     * TROPHY_LIST_VERSION, borra el progreso (números de la lista
     * vieja, no fiables tras la renumeración) y guarda la versión
     * nueva. Idempotente: con versión 2 o más no toca nada. No usa
     * unlockMutex (la llaman también las escrituras antes de
     * tomarlo, y el Mutex no es reentrante): dos migraciones en
     * paralelo harían el mismo borrado, y DataStore serializa los
     * edit{} entre sí.
     */
    suspend fun migrateTrophyListIfNeeded() {
        val prefs = context.achievementsDataStore.data.first()
        val storedVersion = prefs[Keys.VERSION] ?: 0
        val (newVersion, newText) = applyTrophyListMigration(storedVersion, prefs[Keys.UNLOCKED] ?: "")
        if (newVersion == storedVersion && newText == (prefs[Keys.UNLOCKED] ?: "")) return
        context.achievementsDataStore.edit { editPrefs ->
            editPrefs[Keys.UNLOCKED] = newText
            editPrefs[Keys.VERSION] = newVersion
        }
        Log.i("ChildOfLight", "Lista de trofeos 27 -> 20: progreso guardado borrado (versión $storedVersion -> $newVersion).")
    }

    /** Desbloquea un trofeo. Devuelve true solo si estaba bloqueado (es un desbloqueo nuevo). */
    suspend fun unlock(number: Int, atMillis: Long = System.currentTimeMillis()): Boolean {
        if (TROPHIES_20.none { it.number == number }) return false
        migrateTrophyListIfNeeded()
        return unlockMutex.withLock {
            if (progressFlow.first().any { it.trophy.number == number && it.unlocked }) return@withLock false
            context.achievementsDataStore.edit { prefs ->
                val map = decode(prefs[Keys.UNLOCKED] ?: "").toMutableMap()
                if (!map.containsKey(number)) {
                    map[number] = atMillis
                    prefs[Keys.UNLOCKED] = encode(map)
                }
            }
            true
        }
    }

    suspend fun resetAll() {
        migrateTrophyListIfNeeded()
        unlockMutex.withLock {
            context.achievementsDataStore.edit { prefs ->
                prefs[Keys.UNLOCKED] = ""
            }
        }
    }

    /** Exporta el progreso en el formato "numero|epochMillis;...". */
    suspend fun exportText(): String {
        migrateTrophyListIfNeeded()
        val prefs = context.achievementsDataStore.data.first()
        return prefs[Keys.UNLOCKED] ?: ""
    }

    /**
     * REEMPLAZA el progreso por el texto dado (lo usa el cambio de
     * sesión: cada sesión tiene sus propios logros). Las entradas
     * inválidas se descartan; no fusiona con lo anterior, a diferencia
     * de importText. Un texto vacío deja 0/20.
     *
     * 0.2.57: un texto con alguna clave mayor que 20 es de la lista
     * vieja (27): las claves 6-20 cambiaron de trofeo con la
     * renumeración y no se puede confiar en ellas, así que el texto
     * entero se descarta en vez de reinterpretarlo (ver
     * isOldFormatTrophyText).
     */
    suspend fun replaceAll(raw: String) {
        migrateTrophyListIfNeeded()
        val valid = if (isOldFormatTrophyText(raw)) emptyMap()
        else decode(raw).filterKeys { n -> TROPHIES_20.any { it.number == n } }
        unlockMutex.withLock {
            context.achievementsDataStore.edit { prefs ->
                prefs[Keys.UNLOCKED] = encode(valid)
            }
        }
    }

    /** Importa progreso exportado. Las entradas inválidas se ignoran, las válidas se fusionan. Un texto de la lista vieja (alguna clave mayor que 20) se descarta entero (ver isOldFormatTrophyText). */
    suspend fun importText(raw: String): ImportAchievementsResult {
        migrateTrophyListIfNeeded()
        val incoming = decode(raw)
        val valid = if (isOldFormatTrophyText(raw)) emptyMap()
        else incoming.filterKeys { n -> TROPHIES_20.any { it.number == n } }
        val ignored = incoming.size - valid.size
        var added = 0
        unlockMutex.withLock {
            context.achievementsDataStore.edit { prefs ->
                val map = decode(prefs[Keys.UNLOCKED] ?: "").toMutableMap()
                for ((number, at) in valid) {
                    if (!map.containsKey(number)) added++
                    map[number] = map[number] ?: at
                }
                prefs[Keys.UNLOCKED] = encode(map)
            }
        }
        return ImportAchievementsResult(addedOrKept = valid.size, ignoredInvalid = ignored, newlyUnlocked = added)
    }

    companion object {
        /** Versión actual de la lista de trofeos: 2 = lista Vita de 20 (0.2.57). La 1 era la lista vieja de 27. */
        const val TROPHY_LIST_VERSION = 2

        /** true si la versión guardada es anterior a la lista actual y hay que migrar (borrar). */
        fun shouldMigrateTrophyList(storedVersion: Int): Boolean = storedVersion < TROPHY_LIST_VERSION

        /**
         * ¿Es [raw] un texto de la lista vieja (27 trofeos)? Una sola
         * clave mayor que 20 lo delata: la lista nueva solo llega al
         * 20. Las claves 6-20 de ese texto apuntarían a trofeos
         * distintos tras la renumeración, así que quien lo recibe
         * descarta el texto entero en vez de reinterpretarlo.
         */
        fun isOldFormatTrophyText(raw: String): Boolean =
            decode(raw).keys.any { it > TROPHIES_20.size }

        /**
         * Cálculo puro de la migración (sin Android): devuelve la
         * versión y el texto de desbloqueos resultantes. Con versión
         * vieja (< 2) borra el texto (números de la lista de 27, no
         * fiables tras la renumeración) y sube a la versión 2; con
         * versión actual o mayor deja todo intacto.
         */
        fun applyTrophyListMigration(storedVersion: Int, storedUnlockedText: String): Pair<Int, String> =
            if (shouldMigrateTrophyList(storedVersion)) TROPHY_LIST_VERSION to "" else storedVersion to storedUnlockedText

        fun encode(map: Map<Int, Long>): String =
            map.entries.sortedBy { it.key }.joinToString(";") { "${it.key}|${it.value}" }

        fun decode(raw: String): Map<Int, Long> {
            if (raw.isBlank()) return emptyMap()
            val out = mutableMapOf<Int, Long>()
            for (entry in raw.split(";")) {
                val parts = entry.split("|")
                if (parts.size != 2) continue
                val number = parts[0].toIntOrNull() ?: continue
                val at = parts[1].toLongOrNull() ?: continue
                out[number] = at
            }
            return out
        }
    }
}

data class ImportAchievementsResult(
    val addedOrKept: Int,
    val ignoredInvalid: Int,
    val newlyUnlocked: Int,
)
