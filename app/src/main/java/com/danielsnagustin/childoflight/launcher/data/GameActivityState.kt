package com.danielsnagustin.childoflight.launcher.data

/**
 * Estado vivo de la Activity del juego embebido (R2-P1-11, parte núcleo).
 *
 * Bandera en memoria, sin persistencia: la escribe SOLO
 * [com.danielsnagustin.childoflight.launcher.core.EmbeddedGameActivity]
 * (true en onResume, false en onPause/onDestroy solo cuando la
 * Activity se cierra de verdad, isFinishing) y la lee MainActivity
 * para no cerrar la medición de tiempo de juego al pasar por Inicio
 * o Recientes mientras el juego sigue vivo en la pila. Ir a segundo
 * plano (Inicio, llamada, menú de pausa) NO limpia la bandera:
 * isFinishing es false en esos casos y la Activity sigue viva.
 *
 * No es una medición ni reabre ninguna: solo dice si la Activity
 * del juego sigue viva. La medición de tiempo la cierran
 * MainActivity.onResume / notePlayEnded, no este objeto.
 */
object GameActivityState {
    @Volatile
    var gameActivityAlive: Boolean = false
}
