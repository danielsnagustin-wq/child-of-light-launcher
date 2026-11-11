package com.danielsnagustin.childoflight.launcher.model

/**
 * Cola de popups de trofeos recién desbloqueados (R7-P1-06, 0.2.58).
 *
 * Antes el ViewModel guardaba un solo hueco (`_justUnlocked`): si en
 * una partida se ganaban varios trofeos nuevos, cada desbloqueo
 * sobrescribía el anterior y el popup solo mostraba el último; los
 * demás nunca aparecían (quedaban guardados y el contador subía por
 * todos, pero sin su popup). Ahora cada desbloqueo nuevo se encola y
 * la UI muestra un popup tras otro, en el orden en que se encolaron
 * (la sync los encola en orden numérico: ver mapNativeTrophies, que
 * devuelve los desbloqueos ordenados por número). Al descartar el
 * popup visible aparece el siguiente, hasta vaciar la cola.
 *
 * Puro Kotlin (sin Android ni corrutinas) para poder probarlo en
 * tests unitarios JVM: ver TrophyPopupQueueTest. El ViewModel solo
 * refleja `current` en su StateFlow `justUnlocked` (la UI no cambia:
 * sigue mostrando un solo popup y llamando a clearJustUnlocked al
 * descartarlo); el orden y la cola viven aquí.
 *
 * No se deduplica: el único llamador (syncTrophiesFromGame) solo
 * encola desbloqueos NUEVOS (unlock() devolvió true), así que un
 * número no puede entrar dos veces por la misma partida.
 */
class TrophyPopupQueue {

    private val pending = ArrayDeque<TrophyProgress>()

    /** Popup visible ahora (null si la cola está vacía). */
    val current: TrophyProgress?
        get() = pending.firstOrNull()

    /** Cuántos popups quedan por mostrar (incluido el visible). */
    val size: Int
        get() = pending.size

    /** Añade un desbloqueo al final de la cola. */
    fun enqueue(progress: TrophyProgress) {
        pending.addLast(progress)
    }

    /**
     * Descarta el popup visible y devuelve el siguiente (null si la
     * cola se vació). En cola vacía no hace nada y devuelve null.
     */
    fun dismiss(): TrophyProgress? {
        if (pending.isNotEmpty()) pending.removeFirst()
        return current
    }

    /** Vacía toda la cola (al borrar el progreso de logros). */
    fun clear() {
        pending.clear()
    }
}
