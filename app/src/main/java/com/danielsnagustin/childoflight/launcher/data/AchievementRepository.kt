package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_27
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
 * solo como referencia (ver TrophyStoreReader): Vita3K tiene soporte
 * parcial de trofeos y su formato TROPHY.TRP no se decodifica en
 * Etapa 1, así que no se promete sincronización automática con él.
 */
class AchievementRepository(private val context: Context) {

    private object Keys {
        val UNLOCKED = stringPreferencesKey("unlocked_trophies")
    }

    val progressFlow: Flow<List<TrophyProgress>> = context.achievementsDataStore.data.map { prefs ->
        val unlocked = decode(prefs[Keys.UNLOCKED] ?: "")
        TROPHIES_27.map { trophy ->
            TrophyProgress(trophy, unlocked.containsKey(trophy.number), unlocked[trophy.number])
        }
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore logros ilegible, usando bloqueados: ${e.message}")
        emit(TROPHIES_27.map { TrophyProgress(it, false, null) })
    }

    /** Serializa TODAS las escrituras del progreso (unlock, resetAll,
     * replaceAll, importText). R2-P2-33: antes solo unlock() lo usaba y
     * un resetAll concurrente con un unlock podía resucitar un trofeo
     * (la comprobación previa de unlock ya había pasado cuando el
     * edit{} del reset borraba). DataStore serializa los edit{} entre
     * sí, pero no el comprobar-y-actuar de unlock(). */
    private val unlockMutex = Mutex()

    /** Desbloquea un trofeo. Devuelve true solo si estaba bloqueado (es un desbloqueo nuevo). */
    suspend fun unlock(number: Int, atMillis: Long = System.currentTimeMillis()): Boolean {
        if (TROPHIES_27.none { it.number == number }) return false
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
        unlockMutex.withLock {
            context.achievementsDataStore.edit { prefs ->
                prefs[Keys.UNLOCKED] = ""
            }
        }
    }

    /** Exporta el progreso en el formato "numero|epochMillis;...". */
    suspend fun exportText(): String {
        val prefs = context.achievementsDataStore.data.first()
        return prefs[Keys.UNLOCKED] ?: ""
    }

    /**
     * REEMPLAZA el progreso por el texto dado (lo usa el cambio de
     * sesión: cada sesión tiene sus propios logros). Las entradas
     * inválidas se descartan; no fusiona con lo anterior, a diferencia
     * de importText. Un texto vacío deja 0/27.
     */
    suspend fun replaceAll(raw: String) {
        val valid = decode(raw).filterKeys { n -> TROPHIES_27.any { it.number == n } }
        unlockMutex.withLock {
            context.achievementsDataStore.edit { prefs ->
                prefs[Keys.UNLOCKED] = encode(valid)
            }
        }
    }

    /** Importa progreso exportado. Las entradas inválidas se ignoran, las válidas se fusionan. */
    suspend fun importText(raw: String): ImportAchievementsResult {
        val incoming = decode(raw)
        val valid = incoming.filterKeys { n -> TROPHIES_27.any { it.number == n } }
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
