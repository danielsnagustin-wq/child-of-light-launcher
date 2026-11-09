package com.danielsnagustin.childoflight.launcher.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Contadores del mando virtual táctil (R4-P0-3 / R4-P1-2).
 *
 * Cada pulsación de botón o movimiento de eje que pasa por
 * EmbeddedGameActivity.forwardOverlayButton/forwardOverlayAxis deja
 * aquí su resultado: entregada al núcleo o perdida. El reporte de bug
 * (LauncherViewModel.buildBugReportText) lee snapshot(): es la única
 * forma de distinguir "el usuario no pulsó nada" de "pulsó y la
 * entrada se perdió" (en 0.2.49 el log no tenía ni una línea de
 * pulsación).
 *
 * También decide el throttle del log de depuración por pulsación:
 * como mucho una línea por botón/eje y segundo. Las pulsaciones
 * PERDIDAS nunca se throttlean (su línea "perdido" sale siempre);
 * el throttle solo gobierna las líneas de éxito.
 *
 * Kotlin puro, sin Android: testeable en JVM (OverlayInputStatsTest).
 * Seguro para hilos: los contadores son AtomicLong y los últimos
 * valores @Volatile; el ViewModel lee el snapshot desde IO mientras
 * la Activity escribe desde el hilo UI.
 */
object OverlayInputStats {

    /** Ventana mínima entre dos líneas de log de éxito del mismo botón/eje. */
    const val LOG_THROTTLE_MS = 1_000L

    /** Sin pulsación de botón todavía. */
    const val NO_BUTTON = -1

    /** Sin movimiento de eje todavía. */
    const val NO_AXIS = -1

    /**
     * Foto inmutable de los contadores para el reporte de bug.
     * lastButtonSdl/lastAxisSdl son NO_BUTTON/NO_AXIS si aún no hubo
     * ninguna pulsación/eje en este proceso.
     */
    data class Snapshot(
        val buttonsOk: Long,
        val buttonsLost: Long,
        val axesOk: Long,
        val axesLost: Long,
        val lastButtonSdl: Int,
        val lastButtonPressed: Boolean,
        val lastButtonDelivered: Boolean,
        val lastAxisSdl: Int,
        val lastAxisValue: Short,
        val lastAxisDelivered: Boolean,
    )

    private val buttonsOk = AtomicLong(0L)
    private val buttonsLost = AtomicLong(0L)
    private val axesOk = AtomicLong(0L)
    private val axesLost = AtomicLong(0L)

    @Volatile
    private var lastButtonSdl: Int = NO_BUTTON

    @Volatile
    private var lastButtonPressed: Boolean = false

    @Volatile
    private var lastButtonDelivered: Boolean = false

    @Volatile
    private var lastAxisSdl: Int = NO_AXIS

    @Volatile
    private var lastAxisValue: Short = 0

    @Volatile
    private var lastAxisDelivered: Boolean = false

    private val throttleLock = Any()
    private val lastButtonLogMillis = mutableMapOf<Int, Long>()
    private val lastAxisLogMillis = mutableMapOf<Int, Long>()

    /**
     * Registra una pulsación de botón. Devuelve true si el llamador
     * debe escribir la línea de depuración (éxito + fuera de la
     * ventana de throttle). Una pulsación perdida siempre devuelve
     * false: su línea "perdido" la escribe el llamador aparte, sin
     * throttle, y no consume la ventana de throttle del botón.
     */
    fun recordButton(sdlButton: Int, pressed: Boolean, delivered: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (delivered) {
            buttonsOk.incrementAndGet()
        } else {
            buttonsLost.incrementAndGet()
        }
        lastButtonSdl = sdlButton
        lastButtonPressed = pressed
        lastButtonDelivered = delivered
        if (!delivered) return false
        return shouldLogThrottled(lastButtonLogMillis, sdlButton, nowMillis)
    }

    /**
     * Registra un movimiento de eje. Igual que recordButton: true
     * solo para éxitos fuera de la ventana de throttle.
     */
    fun recordAxis(sdlAxis: Int, value: Short, delivered: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (delivered) {
            axesOk.incrementAndGet()
        } else {
            axesLost.incrementAndGet()
        }
        lastAxisSdl = sdlAxis
        lastAxisValue = value
        lastAxisDelivered = delivered
        if (!delivered) return false
        return shouldLogThrottled(lastAxisLogMillis, sdlAxis, nowMillis)
    }

    /**
     * Throttle por tecla/eje: true si es la primera línea de esa
     * tecla/eje o si pasó LOG_THROTTLE_MS desde la última.
     */
    private fun shouldLogThrottled(lastLogMillis: MutableMap<Int, Long>, key: Int, nowMillis: Long): Boolean {
        return synchronized(throttleLock) {
            val last = lastLogMillis[key]
            if (last == null || nowMillis - last >= LOG_THROTTLE_MS) {
                lastLogMillis[key] = nowMillis
                return@synchronized true
            }
            return@synchronized false
        }
    }

    /** Foto actual de los contadores (para el reporte de bug). */
    fun snapshot(): Snapshot {
        return Snapshot(
            buttonsOk = buttonsOk.get(),
            buttonsLost = buttonsLost.get(),
            axesOk = axesOk.get(),
            axesLost = axesLost.get(),
            lastButtonSdl = lastButtonSdl,
            lastButtonPressed = lastButtonPressed,
            lastButtonDelivered = lastButtonDelivered,
            lastAxisSdl = lastAxisSdl,
            lastAxisValue = lastAxisValue,
            lastAxisDelivered = lastAxisDelivered,
        )
    }

    /** Pone todos los contadores a cero. Solo para tests. */
    fun reset() {
        buttonsOk.set(0L)
        buttonsLost.set(0L)
        axesOk.set(0L)
        axesLost.set(0L)
        lastButtonSdl = NO_BUTTON
        lastButtonPressed = false
        lastButtonDelivered = false
        lastAxisSdl = NO_AXIS
        lastAxisValue = 0
        lastAxisDelivered = false
        synchronized(throttleLock) {
            lastButtonLogMillis.clear()
            lastAxisLogMillis.clear()
        }
    }
}
