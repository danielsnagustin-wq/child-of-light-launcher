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
 * 19 bronce + 1 plata; la lista de 27 era la de Xbox), nunca solo
 * por trophyId: los ids del TRP son 000..N-1, otra numeración. Un trofeo
 * nativo sin correspondencia por nombre se SALTA y queda en
 * [TrophySyncResult.skippedUnknownNames]: nunca se inventa un mapeo.
 *
 * Ronda 0.2.65, W-P1-2: el idioma por defecto del launcher es
 * español (LauncherSettings: sys_lang SPANISH) y el pin lee los
 * nombres del TROPCONF en ese idioma (native_trophies.cpp:37,
 * source.lang = sys_lang). Con solo el nombre exacto en inglés, un
 * TROPCONF en español dejaba los 20 en skippedUnknownNames (0/20
 * sin aviso). Por eso el mapeo ahora cae en cascada:
 * 1) nombre exacto en inglés, 2) nombre normalizado en inglés
 * (insensible a mayúsculas, espacios y tildes), 3) nombre
 * normalizado en ESPAÑOL (Trophy.name de TROPHIES_20, nombres
 * oficiales en español), 4) número por trophyId (id 0..19 -> nº
 * 1..20) SOLO si la colección trae totalCount == 20: el id es el
 * índice 0..N-1 dentro del TROPCONF y TROPHIES_20 sigue ese mismo
 * orden, así que id+1 es el número local. La guarda de totalCount
 * evita mapear por id una colección incompleta o de otro juego, y
 * evita que un trophyId por defecto (0) de un trofeo desconocido
 * se convierta en el nº 1. La confirmación definitiva sigue
 * necesitando el TROPCONF real en español del teléfono: si su
 * orden difiriera de TROPHIES_20, el paso 4 mapearía mal y habría
 * que quitarlo; los pasos 1-3 no dependen del orden.
 * Un trofeo que no casa por ninguna vía se sigue saltando y queda
 * en skippedUnknownNames: el llamador (LauncherViewModel.
 * syncTrophiesFromGame) ya lo registra en el log y debe avisar en
 * la UI cuando no esté vacío (ver informe de la ronda).
 *
 * Timestamp: timestampSec * 1000 (epoch millis). Si es 0 o negativo
 * con earned == true (el núcleo no guardó marca de tiempo), la fecha
 * queda DESCONOCIDA (unlockedAtMillis == null): no se inventa la fecha
 * de la sincronización (ronda 0.2.70, 7-P2-4). La UI muestra "fecha
 * desconocida" en ese caso; el desbloqueo en sí es real (el núcleo
 * dice earned), solo falta su instante exacto.
 */

/**
 * Colecciones nativas capturadas al salir del juego, pendientes de
 * sincronizar al volver. Un solo hueco: la captura pisa cualquier
 * resto anterior (solo puede haber una partida en curso) y el consumo
 * lo vacía. Hilos: lo escribe el hilo principal (onDestroy de
 * EmbeddedGameActivity) y lo lee el hilo IO (syncTrophiesFromGame);
 * el candado serializa ambas.
 *
 * Ronda 0.2.70, P1-5: la captura en onDestroy corre DESPUÉS de
 * MainActivity.onResume (orden real: juego.onPause -> launcher.
 * onResume -> juego.onDestroy), así que el take() de onResume llega
 * casi siempre vacío. Por eso store() AVISA a los oyentes
 * (addListener): el ViewModel se suscribe en su init y dispara
 * syncTrophiesFromGame() en cuanto aparece un pendiente, no solo en
 * onResume. MainActivity además re-chequea ~1 s después por si el
 * aviso se perdiera. Los oyentes se invocan fuera del candado y
 * nunca tumban el store (un oyente que falle no impide guardar).
 */
object TrophySyncPending {
    private val lock = Any()
    private var pending: Array<NativeTrophyCollection>? = null
    private val listeners = mutableSetOf<(Array<NativeTrophyCollection>) -> Unit>()

    fun store(collections: Array<NativeTrophyCollection>) {
        val toNotify: List<(Array<NativeTrophyCollection>) -> Unit>
        synchronized(lock) {
            pending = collections
            toNotify = listeners.toList()
        }
        for (listener in toNotify) {
            runCatching { listener(collections) }
        }
    }

    /** Devuelve las colecciones pendientes y vacía el hueco (null si no hay nada). */
    fun take(): Array<NativeTrophyCollection>? {
        synchronized(lock) {
            val taken = pending
            pending = null
            return taken
        }
    }

    /** Suscribe un aviso cuando store() deja un pendiente nuevo. */
    fun addListener(listener: (Array<NativeTrophyCollection>) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: (Array<NativeTrophyCollection>) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }
}

/** Un trofeo ganado según el núcleo, ya mapeado a número local 1..20.
 * [unlockedAtMillis] es null cuando el núcleo no guardó marca de
 * tiempo (timestampSec <= 0): fecha desconocida, nunca inventada. */
data class TrophySyncEntry(
    val number: Int,
    val unlockedAtMillis: Long?,
)

data class TrophySyncResult(
    /** Ganados con correspondencia local, ordenados por número. */
    val unlocked: List<TrophySyncEntry>,
    /** Nombres del núcleo sin correspondencia local (se saltaron). */
    val skippedUnknownNames: List<String>,
    /** npComId de colecciones descartadas por no ser de este juego. */
    val skippedCollections: List<String> = emptyList(),
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
 * Nombre normalizado (minúsculas, espacios colapsados, sin tildes)
 * en inglés -> número local. Cubre variantes de capitalización o
 * espaciado del TROPCONF sin tocar el mapa exacto.
 */
private val NORMALIZED_ENGLISH_NAME_TO_NUMBER: Map<String, Int> =
    TROPHIES_20.filter { it.englishName.isNotBlank() }.associate { normalizeTrophyName(it.englishName) to it.number }

/**
 * Nombre normalizado en ESPAÑOL (Trophy.name, nombres oficiales en
 * español de TROPHIES_20) -> número local. El TROPCONF en español
 * trae estos nombres; sin este mapa, los 20 caían en
 * skippedUnknownNames (W-P1-2).
 */
private val NORMALIZED_SPANISH_NAME_TO_NUMBER: Map<String, Int> =
    TROPHIES_20.filter { it.name.isNotBlank() }.associate { normalizeTrophyName(it.name) to it.number }

/**
 * Normaliza un nombre de trofeo para comparar sin que importen
 * mayúsculas, espacios de más ni tildes ("Décimo cumpleaños" casa
 * con "decimo cumpleanos"). Puro JVM: java.text.Normalizer existe
 * en Android y en los tests unitarios.
 */
internal fun normalizeTrophyName(name: String): String {
    val withoutDiacritics = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return withoutDiacritics.lowercase().trim().replace(Regex("\\s+"), " ")
}

/**
 * Mapea las colecciones nativas a desbloqueos locales. Puro JVM.
 *
 * - earned == false no desbloquea (se ignora en silencio: aún no
 *   ganado, no es un error).
 * - Cascada por trofeo ganado: nombre exacto en inglés, nombre
 *   normalizado en inglés, nombre normalizado en español y, solo
 *   si la colección trae totalCount == 20, trophyId + 1 (el id del
 *   TRP es el índice 0..19 en el orden del TROPCONF, el mismo orden
 *   que TROPHIES_20).
 * - Nombre sin correspondencia por ninguna vía: se salta y se
 *   registra en skippedUnknownNames (nunca se inventa un mapeo).
 * - El mismo número local en varias colecciones (no debería pasar
 *   con un solo juego) se fusiona en una entrada con el instante
 *   más antiguo conocido: el primer desbloqueo es el que cuenta;
 *   si ningún duplicado trae instante, la fecha queda desconocida.
 * - [expectedNpComId]: si se conoce el <npcommid> del TRP
 *   empaquetado (TrophyStoreReader.resolveNpComId), una colección
 *   con npComId distinto y no vacío se descarta entera (7-P2-2) y
 *   queda en skippedCollections; una colección con npComId vacío
 *   no se descarta (el puente viejo puede no rellenarlo).
 */
fun mapNativeTrophies(
    collections: Array<NativeTrophyCollection>,
    // 7-P2-4: se conserva por compatibilidad con los llamadores, pero
    // YA NO se usa para sustituir fechas: un timestampSec <= 0 deja
    // la fecha como desconocida (null), nunca "ahora".
    nowMillis: Long = System.currentTimeMillis(),
    expectedNpComId: String? = null,
): TrophySyncResult {
    val byNumber = mutableMapOf<Int, Long?>()
    val skipped = mutableListOf<String>()
    val skippedCollections = mutableListOf<String>()
    for (collection in collections) {
        if (expectedNpComId != null && collection.npComId.isNotBlank() &&
            !collection.npComId.equals(expectedNpComId, ignoreCase = true)
        ) {
            skippedCollections.add(collection.npComId)
            continue
        }
        for (trophy in collection.trophies) {
            if (!trophy.earned) continue
            val name = trophy.name
            val number = ENGLISH_NAME_TO_NUMBER[name]
                ?: NORMALIZED_ENGLISH_NAME_TO_NUMBER[normalizeTrophyName(name)]
                ?: NORMALIZED_SPANISH_NAME_TO_NUMBER[normalizeTrophyName(name)]
                ?: numberByTrophyId(collection, trophy.trophyId)
            if (number == null) {
                skipped.add(if (name.isBlank()) "<sin nombre>" else name)
                continue
            }
            // 7-P2-4: timestampSec <= 0 -> fecha desconocida (null),
            // nunca la fecha de la sync.
            val atMillis: Long? = if (trophy.timestampSec > 0) trophy.timestampSec * 1000 else null
            val previous = byNumber[number]
            if (number !in byNumber) {
                byNumber[number] = atMillis
            } else if (previous == null) {
                // Ya había un duplicado sin fecha: el instante
                // conocido (si lo hay) es el primer desbloqueo.
                if (atMillis != null) byNumber[number] = atMillis
            } else if (atMillis != null && atMillis < previous) {
                byNumber[number] = atMillis
            }
        }
    }
    val unlocked = byNumber.entries
        .sortedBy { it.key }
        .map { TrophySyncEntry(it.key, it.value) }
    return TrophySyncResult(unlocked, skipped, skippedCollections)
}

/**
 * Número local 1..20 por trophyId del TRP (id 0..19 -> nº 1..20),
 * o null si no se puede usar el id. La guarda de totalCount == 20
 * es lo que hace seguro el mapeo por id: solo se aplica a la
 * colección completa de Child of Light (20 trofeos), donde el orden
 * del TROPCONF es el de TROPHIES_20. Sin ella, un trophyId 0 por
 * defecto (p. ej. un objeto construido a mano o una colección de
 * otro juego) se convertiría en el nº 1 sin serlo.
 *
 * Ronda 0.2.70, 7-P2-3 (SIN cambio de código, decisión
 * documentada): el paso 4 asume que el orden del TROPCONF real es
 * el de TROPHIES_20 y eso solo lo confirma el TROPCONF.SFM del
 * teléfono, que no está en este árbol. Se mantiene el paso 4
 * porque la evidencia disponible apunta al mismo orden: el orden
 * de TROPHIES_20 coincide exactamente con el listado oficial de
 * PSN para Vita (psmania.net, 20/20, verificado en la auditoría
 * de segunda pasada) y el orden PSN y el orden TROPCONF derivan
 * del mismo set de trofeos; además los pasos 1-3 (por nombre)
 * resuelven la inmensa mayoría y el paso 4 solo cubre el resto
 * cuando la colección está completa (totalCount == 20). Si el
 * TROPCONF real del teléfono mostrara otro orden, hay que quitar
 * este paso; hasta entonces, quitarlo perdería mapeos reales.
 */
private fun numberByTrophyId(collection: NativeTrophyCollection, trophyId: Int): Int? {
    if (collection.totalCount != TROPHIES_20.size) return null
    if (trophyId !in 0 until TROPHIES_20.size) return null
    return trophyId + 1
}
