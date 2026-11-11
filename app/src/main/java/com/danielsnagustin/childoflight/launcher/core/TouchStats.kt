package com.danielsnagustin.childoflight.launcher.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Contadores del táctil libre del juego (0.2.63, ítem D).
 *
 * Espejo de OverlayInputStats, pero para los toques que NO caen
 * sobre ningún botón del overlay: el dedo en el área libre del
 * juego (que la superficie SDL recibe como táctil de Vita) y los
 * toques reenviados como sintéticos (segundo dedo sobre un control
 * y zona de Igniculus). El reporte de bug
 * (LauncherViewModel.buildBugReportText) lee snapshot(): es la única
 * forma de distinguir "el usuario no tocó el área libre" de "tocó
 * y el toque se perdió" (hasta 0.2.62 el táctil libre no se registraba
 * en ningún sitio: ni contadores, ni log, ni reporte).
 *
 * Alimentación (sin doble conteo):
 * - EmuSurface.dispatchTouchEvent: el toque libre que llega a la
 *   superficie SDL (super.dispatchTouchEvent). Los reenvíos
 *   sintéticos pasan por ahí también, pero con
 *   forwardingSyntheticTouch activo: EmuSurface NO los registra
 *   (ya se cuentan en su origen).
 * - GamepadOverlayView.forwardPointerDownToSdl / forwardPointerUpToSdl:
 *   segundo dedo reenviado como sintético (router multi-toque).
 * - ZoneView.forwardTouch: toque de la zona de Igniculus reenviado
 *   como sintético a la Activity.
 *
 * También decide el throttle del log de depuración por toque: como
 * mucho una línea por tipo y segundo. Los toques PERDIDOS nunca se
 * throttlean (su línea "perdido" sale siempre); el throttle solo
 * gobierna las líneas de éxito.
 *
 * Kotlin puro, sin Android: testeable en JVM (TouchStatsTest).
 * Seguro para hilos: los contadores son AtomicLong y los últimos
 * valores @Volatile; el ViewModel lee el snapshot desde IO mientras
 * la Activity escribe desde el hilo UI.
 */
object TouchStats {

    /** Ventana mínima entre dos líneas de log de éxito del mismo tipo. */
    const val LOG_THROTTLE_MS = 1_000L

    /** Sin toque todavía. */
    const val NO_POSITION = -1f

    /** Sin evento todavía. */
    const val NO_EVENT_MILLIS = 0L

    /**
     * Foto inmutable de los contadores para el reporte de bug.
     * lastEventMillis es NO_EVENT_MILLIS si aún no hubo ningún toque
     * en este proceso (el reporte lo muestra como "ningún toque
     * todavía": un 0/0 con último toque "nunca" no se puede confundir
     * con un reporte viejo).
     */
    data class Snapshot(
        val freeDownOk: Long,
        val freeDownLost: Long,
        val freeUpOk: Long,
        val forwardedDownOk: Long,
        val forwardedDownLost: Long,
        val forwardedUpOk: Long,
        val lastX: Float,
        val lastY: Float,
        val lastAction: String,
        val lastDelivered: Boolean,
        val lastEventMillis: Long,
    )

    private val freeDownOk = AtomicLong(0L)
    private val freeDownLost = AtomicLong(0L)
    private val freeUpOk = AtomicLong(0L)
    private val forwardedDownOk = AtomicLong(0L)
    private val forwardedDownLost = AtomicLong(0L)
    private val forwardedUpOk = AtomicLong(0L)

    @Volatile
    private var lastX: Float = NO_POSITION

    @Volatile
    private var lastY: Float = NO_POSITION

    @Volatile
    private var lastAction: String = ""

    @Volatile
    private var lastDelivered: Boolean = false

    @Volatile
    private var lastEventMillis: Long = NO_EVENT_MILLIS

    private val throttleLock = Any()
    private val lastLogMillis = mutableMapOf<String, Long>()

    /**
     * Registra un DOWN de toque libre que llegó a la superficie SDL.
     * Devuelve true si el llamador debe escribir la línea de
     * depuración (éxito + fuera de la ventana de throttle). Un DOWN
     * perdido siempre devuelve false: su línea "perdido" la escribe
     * el llamador aparte, sin throttle, y no consume la ventana.
     */
    fun recordFreeDown(delivered: Boolean, x: Float, y: Float, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (delivered) {
            freeDownOk.incrementAndGet()
        } else {
            freeDownLost.incrementAndGet()
        }
        recordLast("DOWN", delivered, x, y, nowMillis)
        if (!delivered) return false
        return shouldLogThrottled("free-down", nowMillis)
    }

    /** Registra un UP de toque libre que llegó a la superficie SDL. No throttlea el log: el UP cierra el toque. */
    fun recordFreeUp(delivered: Boolean, x: Float, y: Float, nowMillis: Long = System.currentTimeMillis()) {
        if (delivered) freeUpOk.incrementAndGet()
        recordLast("UP", delivered, x, y, nowMillis)
    }

    /** Actualiza solo la última posición (MOVE de toque libre): no cuenta como toque nuevo. */
    fun recordFreeMove(x: Float, y: Float, nowMillis: Long = System.currentTimeMillis()) {
        recordLast("MOVE", true, x, y, nowMillis)
    }

    /**
     * Registra un DOWN reenviado como sintético (segundo dedo o zona
     * de Igniculus). Igual que recordFreeDown: true solo para éxitos
     * fuera de la ventana de throttle.
     */
    fun recordForwardedDown(delivered: Boolean, x: Float, y: Float, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (delivered) {
            forwardedDownOk.incrementAndGet()
        } else {
            forwardedDownLost.incrementAndGet()
        }
        recordLast("DOWN", delivered, x, y, nowMillis)
        if (!delivered) return false
        return shouldLogThrottled("forwarded-down", nowMillis)
    }

    /** Registra un UP reenviado como sintético. No throttlea el log. */
    fun recordForwardedUp(delivered: Boolean, x: Float, y: Float, nowMillis: Long = System.currentTimeMillis()) {
        if (delivered) forwardedUpOk.incrementAndGet()
        recordLast("UP", delivered, x, y, nowMillis)
    }

    /** Actualiza solo la última posición (MOVE reenviado como sintético). */
    fun recordForwardedMove(x: Float, y: Float, nowMillis: Long = System.currentTimeMillis()) {
        recordLast("MOVE", true, x, y, nowMillis)
    }

    private fun recordLast(action: String, delivered: Boolean, x: Float, y: Float, nowMillis: Long) {
        lastAction = action
        lastDelivered = delivered
        lastX = x
        lastY = y
        lastEventMillis = nowMillis
    }

    /**
     * Throttle por tipo: true si es la primera línea de ese tipo o si
     * pasó LOG_THROTTLE_MS desde la última.
     */
    private fun shouldLogThrottled(key: String, nowMillis: Long): Boolean {
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
            freeDownOk = freeDownOk.get(),
            freeDownLost = freeDownLost.get(),
            freeUpOk = freeUpOk.get(),
            forwardedDownOk = forwardedDownOk.get(),
            forwardedDownLost = forwardedDownLost.get(),
            forwardedUpOk = forwardedUpOk.get(),
            lastX = lastX,
            lastY = lastY,
            lastAction = lastAction,
            lastDelivered = lastDelivered,
            lastEventMillis = lastEventMillis,
        )
    }

    /** Pone todos los contadores a cero. Solo para tests. */
    fun reset() {
        freeDownOk.set(0L)
        freeDownLost.set(0L)
        freeUpOk.set(0L)
        forwardedDownOk.set(0L)
        forwardedDownLost.set(0L)
        forwardedUpOk.set(0L)
        lastX = NO_POSITION
        lastY = NO_POSITION
        lastAction = ""
        lastDelivered = false
        lastEventMillis = NO_EVENT_MILLIS
        synchronized(throttleLock) {
            lastLogMillis.clear()
        }
    }
}
