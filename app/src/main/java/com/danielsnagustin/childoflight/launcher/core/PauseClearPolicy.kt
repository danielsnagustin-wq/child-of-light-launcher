package com.danielsnagustin.childoflight.launcher.core

/**
 * Resultado de un intento de quitar la pausa de fondo (R4-P0-4).
 *
 * El núcleo descarta el clear si la fase aún no es Running
 * (session_controller.cpp: set_pause_reason devuelve false sin tocar
 * nada), así que el llamador no puede fiarse del Boolean de
 * setPauseReasonEnabled: tiene que verificar con isAppPaused().
 */
enum class PauseClearOutcome {
    /** El juego ya no está pausado (el clear funcionó, o ya lo estaba). */
    CLEARED,

    /**
     * El motivo BACKGROUND quedó quitado pero el juego sigue pausado
     * por OTRO motivo (usuario/menú). No se reintenta: ese motivo no
     * pertenece a este camino.
     */
    STILL_PAUSED_OTHER_REASON,

    /** El núcleo rechazó el clear (fase distinta de Running): reintentar. */
    CLEAR_REJECTED_RETRY,

    /** El núcleo rechazó el clear en el último intento: no quedan reintentos. */
    CLEAR_REJECTED_FAILED,
}

/**
 * Decide qué hacer tras un intento de quitar la pausa de fondo.
 *
 * setOk es lo que devolvió setPauseReasonEnabled(BACKGROUND, false);
 * stillPaused es isAppPaused() JUSTO DESPUÉS del intento; attempt es
 * el número de intento (1 = primero) y maxAttempts el total.
 *
 * La verificación manda sobre el Boolean: si el juego ya no está
 * pausado, el objetivo está cumplido aunque el set devolviera false
 * (p. ej. el motivo lo quitó otro camino entre el set y la lectura).
 * Si sigue pausado y el set fue aceptado, el motivo que queda es otro
 * (BACKGROUND ya no está): no se reintenta un clear que ya funcionó.
 */
fun decidePauseClearAttempt(setOk: Boolean, stillPaused: Boolean, attempt: Int, maxAttempts: Int): PauseClearOutcome {
    if (!stillPaused) return PauseClearOutcome.CLEARED
    if (setOk) return PauseClearOutcome.STILL_PAUSED_OTHER_REASON
    if (attempt < maxAttempts) return PauseClearOutcome.CLEAR_REJECTED_RETRY
    return PauseClearOutcome.CLEAR_REJECTED_FAILED
}
