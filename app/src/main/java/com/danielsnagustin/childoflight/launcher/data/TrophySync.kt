package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.model.TROPHIES_20
import org.vita3k.emulator.data.NativeTrophyCollection

/**
 * Sincronización de trofeos con el TROPHY.TRP real del núcleo (Ronda
 * 0.2.57, puente JNI de trofeos). Solo lectura: el juego desbloquea,
 * el launcher solo observa; los trofeos SOLO se ganan jugando y el
 * marcado manual sigue eliminado.
 *
 * Por qué hay dos piezas:
 * - [TrophySyncPending]: la lectura JNI exige sesión viva (el núcleo
 *   devuelve vacío sin emuenv y destroySession lo destruye), así que
 *   EmbeddedGameActivity captura las colecciones en onDestroy ANTES
 *   de destroySession y las deja aquí. El ViewModel las consume al
 *   volver (syncTrophiesFromGame) y las mapea + desbloquea + anuncia.
 * - [mapNativeTrophies]: mapeo puro JVM (testeable sin Android):
 *   de la lista nativa a números locales 1..20.
 *
 * Mapeo POR NOMBRE EN INGLÉS EXACTO contra Trophy.englishName
 * (TROPHIES_20, lista verificada de S14: Vita tiene 20 trofeos,
 * 19 bronce + 1 plata; la lista de 27 era la de Xbox), nunca por
 * trophyId: los ids del TRP son 000..N-1, otra numeración. Un trofeo
 * nativo sin correspondencia por nombre se SALTA y queda en
 * [TrophySyncResult.skippedUnknownNames]: nunca se inventa un mapeo.
 *
 * Timestamp: timestampSec * 1000 (epoch millis). Si es 0 o negativo
 * con earned == true (el núcleo no guardó marca de tiempo), se usa
 * el instante de la sincronización ([nowMillis]): el desbloqueo es
 * real (el núcleo dice earned), solo falta su instante exacto.
 */

/**
 * Colecciones nativas capturadas al salir del juego, pendientes de
 * sincronizar al volver. Un solo hueco: la captura pisa cualquier
 * resto anterior (solo puede haber una partida en curso) y el consumo
 * lo vacía. Hilos: lo escribe el hilo principal (onDestroy de
 * EmbeddedGameActivity) y lo lee el hilo IO (syncTrophiesFromGame);
 * el candado serializa ambas.
 */
object TrophySyncPending {
    private val lock = Any()
    private var pending: Array<NativeTrophyCollection>? = null

    fun store(collections: Array<NativeTrophyCollection>) {
        synchronized(lock) { pending = collections }
    }

    /** Devuelve las colecciones pendientes y vacía el hueco (null si no hay nada). */
    fun take(): Array<NativeTrophyCollection>? {
        synchronized(lock) {
            val taken = pending
            pending = null
            return taken
        }
    }
}

/** Un trofeo ganado según el núcleo, ya mapeado a número local 1..20. */
data class TrophySyncEntry(
    val number: Int,
    val unlockedAtMillis: Long,
)

data class TrophySyncResult(
    /** Ganados con correspondencia local, ordenados por número. */
    val unlocked: List<TrophySyncEntry>,
    /** Nombres en inglés del núcleo sin correspondencia local (se saltaron). */
    val skippedUnknownNames: List<String>,
)

/**
 * Nombre en inglés exacto del núcleo -> número local 1..20.
 * Derivada de TROPHIES_20 por Trophy.englishName (lista
 * verificada de S14): una sola fuente de verdad, sin copiar la tabla
 * en dos sitios. La numeración es la de Vita (20): NO coincide con
 * la antigua lista de 27 (p. ej. "Capilli Catch" es el 6 aquí y era
 * el 7 en la de 27, porque la de 27 incluía "The Girl and the
 * Firefly" como 6, trofeo que Vita no trae). Las entradas sin nombre
 * en inglés no entran en el mapa: un trofeo nativo sin nombre no
 * puede mapearse y se salta.
 */
private val ENGLISH_NAME_TO_NUMBER: Map<String, Int> =
    TROPHIES_20.filter { it.englishName.isNotBlank() }.associate { it.englishName to it.number }

/**
 * Mapea las colecciones nativas a desbloqueos locales. Puro JVM.
 *
 * - earned == false no desbloquea (se ignora en silencio: aún no
 *   ganado, no es un error).
 * - Nombre sin correspondencia: se salta y se registra en
 *   skippedUnknownNames (nunca se inventa un mapeo).
 * - El mismo número local en varias colecciones (no debería pasar
 *   con un solo juego) se fusiona en una entrada con el instante
 *   más antiguo: el primer desbloqueo es el que cuenta.
 */
fun mapNativeTrophies(
    collections: Array<NativeTrophyCollection>,
    nowMillis: Long = System.currentTimeMillis(),
): TrophySyncResult {
    val byNumber = mutableMapOf<Int, Long>()
    val skipped = mutableListOf<String>()
    for (collection in collections) {
        for (trophy in collection.trophies) {
            if (!trophy.earned) continue
            val name = trophy.name
            val number = ENGLISH_NAME_TO_NUMBER[name]
            if (number == null) {
                skipped.add(if (name.isBlank()) "<sin nombre>" else name)
                continue
            }
            val atMillis = if (trophy.timestampSec > 0) trophy.timestampSec * 1000 else nowMillis
            val previous = byNumber[number]
            if (previous == null || atMillis < previous) byNumber[number] = atMillis
        }
    }
    val unlocked = byNumber.entries
        .sortedBy { it.key }
        .map { TrophySyncEntry(it.key, it.value) }
    return TrophySyncResult(unlocked, skipped)
}
