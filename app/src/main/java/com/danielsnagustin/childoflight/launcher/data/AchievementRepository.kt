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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 * R7-P2-01 (0.2.58): el EXPORTADO va firmado (ver signExport /
 * verifySignedExport): "COL-ACH-v1", el payload y un HMAC-SHA256
 * con una clave aleatoria por dispositivo guardada en este mismo
 * DataStore privado (EXPORT_SIGNING_KEY). importText y replaceAll
 * solo aceptan respaldos firmados por ESTA app en ESTE dispositivo:
 * un texto "N|epochMillis" escrito a mano (o un achievements.txt
 * fabricado dentro de un zip de sesión) se rechaza y no desbloquea
 * nada. Los respaldos viejos SIN firma (pre-0.2.58) también se
 * rechazan: es el coste aceptado por diseño. El achievements.txt de
 * cada sesión también va firmado, porque se escribe desde
 * exportText(): las sesiones reales exportadas por la app se siguen
 * restaurando. Esto es DETECCIÓN de archivos fabricados a mano, no
 * seguridad real: quien tenga acceso al almacenamiento privado de
 * la app puede leer la clave igual que el progreso; no protege
 * contra un dispositivo rooteado, solo contra un .txt editado.
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
        /** Clave HMAC por dispositivo para firmar los respaldos (R7-P2-01). */
        val EXPORT_SIGNING_KEY = stringPreferencesKey("achievement_export_key")
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

    /**
     * Clave HMAC por dispositivo (R7-P2-01): se genera UNA vez (32
     * bytes aleatorios) y se guarda en este mismo DataStore privado
     * de la app. Es el mecanismo más simple que ya usa el repo para
     * datos privados locales (DataStore Preferences: el mismo donde
     * vive el progreso); no hay Keystore ni EncryptedSharedPreferences
     * en el proyecto. El Mutex serializa la generación: dos
     * exportText/importText concurrentes en un arranque sin clave no
     * pueden generar (y guardar) dos claves distintas.
     */
    private val signingKeyMutex = Mutex()

    private suspend fun signingKeyHex(): String {
        signingKeyMutex.withLock {
            val stored = context.achievementsDataStore.data.first()[Keys.EXPORT_SIGNING_KEY]
            if (!stored.isNullOrBlank()) return stored
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val hex = bytes.joinToString("") { "%02x".format(it) }
            context.achievementsDataStore.edit { prefs -> prefs[Keys.EXPORT_SIGNING_KEY] = hex }
            return hex
        }
    }

    /**
     * Devuelve el payload de un respaldo firmado si la firma es
     * válida; null si el texto no es un respaldo firmado por esta
     * app en este dispositivo (sin firma, manipulado o firmado con
     * otra clave). Un fallo al leer la clave también es null: no se
     * importa nada a ciegas.
     */
    suspend fun verifiedPayload(signed: String): String? {
        val key = runCatching { signingKeyHex() }.getOrNull() ?: return null
        return verifySignedExport(signed, key)
    }

    /**
     * Decodifica un respaldo firmado y verificado (para la
     * restauración de sesiones: SessionManager). Mapa vacío si la
     * firma no es válida: un achievements.txt fabricado cuenta 0
     * trofeos y no restaura ninguno.
     */
    suspend fun decodeVerified(signed: String): Map<Int, Long> {
        val payload = verifiedPayload(signed) ?: return emptyMap()
        return decode(payload)
    }

    /**
     * Exporta el progreso FIRMADO (R7-P2-01): cabecera, payload
     * "numero|epochMillis;..." y HMAC con la clave del dispositivo.
     * El achievements.txt de cada sesión también sale de aquí (vía
     * SessionManager.snapshotExtras), así que las sesiones
     * exportadas por la app llevan el texto firmado y su restauración
     * sigue funcionando.
     */
    suspend fun exportText(): String {
        migrateTrophyListIfNeeded()
        val prefs = context.achievementsDataStore.data.first()
        val payload = prefs[Keys.UNLOCKED] ?: ""
        return signExport(payload, signingKeyHex())
    }

    /**
     * REEMPLAZA el progreso por el texto dado (lo usa el cambio de
     * sesión: cada sesión tiene sus propios logros). Las entradas
     * inválidas se descartan; no fusiona con lo anterior, a diferencia
     * de importText. Un texto vacío deja 0/20.
     *
     * R7-P2-01: solo se acepta un respaldo FIRMADO por esta app (ver
     * verifiedPayload). Un achievements.txt sin firma válida (de una
     * sesión pre-0.2.58, o fabricado dentro de un zip) se descarta
     * entero: la sesión restaura sus saves, pero sus logros quedan
     * en 0/20 hasta que la próxima sync al volver del juego los
     * vuelva a desbloquear desde el TROPHY.TRP real del núcleo.
     *
     * 0.2.57: un texto con alguna clave mayor que 20 es de la lista
     * vieja (27): las claves 6-20 cambiaron de trofeo con la
     * renumeración y no se puede confiar en ellas, así que el texto
     * entero se descarta en vez de reinterpretarlo (ver
     * isOldFormatTrophyText).
     */
    suspend fun replaceAll(raw: String) {
        migrateTrophyListIfNeeded()
        val payload = verifiedPayload(raw) ?: ""
        val valid = if (isOldFormatTrophyText(payload)) emptyMap()
        else decode(payload).filterKeys { n -> TROPHIES_20.any { it.number == n } }
        unlockMutex.withLock {
            context.achievementsDataStore.edit { prefs ->
                prefs[Keys.UNLOCKED] = encode(valid)
            }
        }
    }

    /**
     * Importa progreso exportado. R7-P2-01: solo se aceptan respaldos
     * FIRMADOS por esta app (ver verifiedPayload): un texto sin firma
     * válida (escrito a mano, manipulado, de otro dispositivo, o un
     * respaldo viejo pre-0.2.58 sin firma) se rechaza entero
     * (rejectedInvalidSignature = true) y no desbloquea nada. Las
     * entradas inválidas de un respaldo firmado se ignoran, las
     * válidas se fusionan. Un texto firmado de la lista vieja (alguna
     * clave mayor que 20) se descarta entero (ver
     * isOldFormatTrophyText).
     */
    suspend fun importText(raw: String): ImportAchievementsResult {
        migrateTrophyListIfNeeded()
        val payload = verifiedPayload(raw)
            ?: return ImportAchievementsResult(
                addedOrKept = 0,
                ignoredInvalid = 0,
                newlyUnlocked = 0,
                rejectedInvalidSignature = true,
            )
        val incoming = decode(payload)
        val valid = if (isOldFormatTrophyText(payload)) emptyMap()
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

        /**
         * R7-P2-01: cabecera del respaldo FIRMADO de logros (0.2.58).
         * Formato de 3 líneas: cabecera, payload "N|epoch;..." (vacío
         * si 0 trofeos) y HMAC-SHA256 hex del payload con la clave por
         * dispositivo. El payload nunca trae saltos de línea (solo
         * dígitos, "|" y ";"), así que el formato no es ambiguo.
         *
         * Esto es DETECCIÓN de archivos fabricados a mano, no
         * seguridad real: la clave vive en el almacenamiento privado
         * de la app, al alcance de cualquiera con acceso a ese
         * almacenamiento. No protege contra un dispositivo rooteado.
         */
        const val SIGNED_EXPORT_HEADER = "COL-ACH-v1"

        /** Firma [payload] con la clave [keyHex] (hex de 32 bytes). Pura (JVM). */
        fun signExport(payload: String, keyHex: String): String =
            "$SIGNED_EXPORT_HEADER\n$payload\n${hmacSha256Hex(keyHex, payload)}"

        /**
         * Devuelve el payload de un respaldo firmado si la cabecera y
         * el HMAC son válidos; null si no es un respaldo firmado (sin
         * firma, cabecera distinta, payload o firma manipulados, o
         * clave distinta). Pura (JVM): ver AchievementSigningTest. La
         * comparación del HMAC es en tiempo constante (MessageDigest.
         * isEqual): el valor no es secreto, pero no cuesta nada.
         */
        fun verifySignedExport(signed: String, keyHex: String): String? {
            val parts = signed.split("\n")
            if (parts.size != 3 || parts[0] != SIGNED_EXPORT_HEADER) return null
            val payload = parts[1]
            val hmac = parts[2]
            val expected = runCatching { hmacSha256Hex(keyHex, payload) }.getOrNull() ?: return null
            if (hmac.length != expected.length) return null
            return if (MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8),
                    hmac.toByteArray(Charsets.UTF_8),
                )
            ) {
                payload
            } else {
                null
            }
        }

        private fun hmacSha256Hex(keyHex: String, payload: String): String {
            val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

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
    /** R7-P2-01: true si el texto no era un respaldo firmado válido y se rechazó entero (sin tocar el progreso). */
    val rejectedInvalidSignature: Boolean = false,
)
