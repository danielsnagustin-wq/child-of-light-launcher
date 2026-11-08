package com.danielsnagustin.childoflight.launcher.model

/**
 * Anuncio del trofeo recién desbloqueado (el popup "¡Logro desbloqueado!").
 *
 * BUG 2026-11-08 (capturas 50386_0_z5ek / 50383_1_uzjw): el usuario
 * desbloqueó Master Oculist (15) y después Showstopper (17); el popup
 * dijo "Master Oculist", el trofeo equivocado. Causa: el ViewModel
 * escribía el trofeo en un StateFlow plano (`_justUnlocked`) desde
 * corrutinas `unlockTrophy` sin serializar y sin identidad de evento —
 * el último escritor gana, así que un desbloqueo anterior que termina
 * tarde pisa el anuncio del último desbloqueo. Además el trofeo
 * anunciado se releía de `progressFlow.first()` tras el edit de
 * DataStore, una relectura que puede ir por detrás de la escritura.
 *
 * Este anunciador corrige ambas cosas:
 * - Cada desbloqueo lleva un número de secuencia asignado en el
 *   momento de la pulsación (orden de llamada). Un anuncio con
 *   secuencia menor o igual que la última aceptada es un desbloqueo
 *   anterior que terminó tarde y se IGNORA: el popup siempre nombra
 *   el trofeo del desbloqueo más reciente.
 * - El progreso anunciado se construye directamente desde TROPHIES_27
 *   con el número y el instante del desbloqueo: nunca se relee de un
 *   flujo, así que no puede nombrar otro trofeo ni llegar con el
 *   estado equivocado.
 *
 * Puro Kotlin (sin Android ni corrutinas) para poder probarlo en
 * tests unitarios JVM: ver TrophyUnlockAnnouncerTest.
 */
class TrophyUnlockAnnouncer {

    /** Anuncio vigente (null si el usuario ya cerró el popup). */
    var current: TrophyProgress? = null
        private set

    /** Secuencia más alta aceptada hasta ahora (orden de pulsación). */
    private var lastSeq: Long = 0

    /**
     * Anuncia el desbloqueo del trofeo [number] en [atMillis], con la
     * secuencia [seq] asignada al pulsar. Devuelve el progreso
     * anunciado, o null si el anuncio es obsoleto (un desbloqueo
     * anterior que terminó después de uno más reciente) o el número
     * no existe.
     */
    fun announce(number: Int, atMillis: Long, seq: Long): TrophyProgress? {
        if (seq <= lastSeq) return null
        val trophy = TROPHIES_27.firstOrNull { it.number == number } ?: return null
        lastSeq = seq
        return TrophyProgress(trophy, unlocked = true, unlockedAtMillis = atMillis)
            .also { current = it }
    }

    /** El usuario cerró el popup. La marca de secuencia NO se resetea:
     * un anuncio tardío de un desbloqueo anterior sigue ignorándose. */
    fun clear() {
        current = null
    }
}
