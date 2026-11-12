package com.danielsnagustin.childoflight.launcher.core

/**
 * Perfil de frames, Fase L (ronda 0.2.66, INST-P0-2 / INST-P0-3 /
 * INST-P1-1, CPU-09 parte launcher).
 *
 * Espejo de TouchStats / OverlayInputStats: object Kotlin puro,
 * contadores y muestras acotadas, snapshot() para el reporte de bug,
 * reset() solo para tests, throttle de log 1/s. Lo alimenta
 * EmbeddedGameActivity mientras el perfil está activado (ajuste
 * `frame_profile`, OFF por defecto): Choreographer (cadencia de
 * vsync), Window.OnFrameMetricsAvailableListener (tiempo del overlay
 * Views) y sondeo a 4 Hz de getFps() + contador de presentados
 * (gancho nº 1).
 *
 * Responde tres preguntas: "¿la pantalla va a 60 Hz mientras el juego
 * va a 15 fps?" (vsync estable a 16,6 ms con FPS emulado bajo: el
 * problema no es el panel ni el hilo UI), "¿el overlay/UI es el que
 * se atasca?" (TOTAL_DURATION del overlay con p95 mayor que 16,6 ms)
 * y "¿lo que el juego emula llega al panel?" (presents/s frente a
 * fps emulado: presents/s ~0 con fps > 0 es pantalla negra).
 *
 * LÍMITES HONESTOS (la Fase L no sustituye a la Fase P):
 * - Choreographer mide ticks de vsync del panel, NO frames
 *   presentados del juego: un juego a 15 presentados en un panel a
 *   60 Hz sigue recibiendo 60 ticks/s. Un tirón de 200 ms en el
 *   agregado de 1 s del núcleo es no identificable/localizable en
 *   ese agregado (corrección Codex 1 a INST-P0-2): no se dice
 *   "invisible", porque el agregado sí baja; lo que no hace es
 *   identificar ni localizar el tirón.
 * - FrameMetrics mide solo la jerarquía de Views (overlay táctil,
 *   Compose). La superficie del juego la dibuja el núcleo por
 *   Vulkan y no se ve aquí: un overlay en verde no exonera al
 *   render del juego, solo al overlay.
 * - getFps() sigue siendo el agregado de 1 s del núcleo (entero,
 *   frames emulados, con la data race documentada en INST-P2-1):
 *   sondearlo a 4 Hz solo suaviza la lectura, no crea datos por
 *   frame. No se lee `fps` para el profiling por frame.
 * - Frames presentados (INST-P0-3, gancho nº 1, 0.2.66): la serie
 *   `presentedPerSecond` SÍ existe desde esta ronda (contador de
 *   presentKHR + JNI, parche hook1-present-counter.patch):
 *   presents/s = (count2 - count1) / ((timeNs2 - timeNs1) / 1e9),
 *   muestreado a 4 Hz junto a getFps(). LÍMITES HONESTOS: la tasa
 *   es un promedio sobre el intervalo de sondeo (250 ms), no una
 *   medida por frame; sin el parche aplicado el contador es -1 y
 *   no hay muestras; el contador solo avanza cuando presentKHR
 *   presenta una imagen (con pantalla negra NO avanza: esa es
 *   justamente la prueba). No mide latencia por frame.
 * - INST-P1-1: un juego a 30 fps (33,3 ms/frame) puede ser
 *   CORRECTO. Por eso cada serie se agrega contra DOS umbrales
 *   (16,6 y 33,3 ms): juzgar cada frame emulado solo contra
 *   16,6 ms daría un falso positivo permanente.
 * - No se afirma nada sobre instrumentación del núcleo que las
 *   citas no prueben (correcciones Codex 9 y 14): aquí no se dice
 *   si el build del núcleo habilita o no una herramienta de
 *   profiling, ni se niega que exista otra medición fuera de las
 *   rutas citadas.
 *
 * Kotlin puro, sin Android: testeable en JVM
 * (FrameProfileStatsTest). Seguro para hilos: las muestras viven
 * bajo un lock y el snapshot copia antes de ordenar; el ViewModel
 * lee el snapshot desde IO mientras la Activity escribe desde el
 * hilo UI.
 */
object FrameProfileStats {

    /** Ventana mínima entre dos líneas de log de resumen. */
    const val LOG_THROTTLE_MS = 1_000L

    /** Umbral de un frame a 60 Hz, en ms. */
    const val OVER_16_MS = 16.6

    /** Umbral de un frame a 30 Hz, en ms (INST-P1-1: 30 fps puede ser correcto). */
    const val OVER_33_MS = 33.3

    /** Intervalo de vsync mayor que esto (p. ej. app en segundo plano) no es un frame: se descarta. */
    const val MAX_VSYNC_INTERVAL_MS = 1_000.0

    /** Muestras guardadas por serie (60 s a 60 Hz / 60 s a 4 Hz): pasado el tope se descarta lo más viejo. */
    const val MAX_VSYNC_SAMPLES = 3_600
    const val MAX_OVERLAY_SAMPLES = 600
    const val MAX_FPS_SAMPLES = 240
    const val MAX_PRESENTED_SAMPLES = 240

    /**
     * Agregados de una serie de intervalos en ms: nº de muestras,
     * media, percentiles y máx, y % que supera cada umbral
     * (16,6 y 33,3 ms). count 0 = sin muestras todavía.
     */
    data class IntervalStats(
        val count: Long,
        val meanMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val maxMs: Double,
        val over16Pct: Double,
        val over33Pct: Double,
    ) {
        companion object {
            val EMPTY = IntervalStats(0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Agregados de las muestras de FPS emulado (agregado de 1 s del núcleo, no por frame). */
    data class FpsStats(
        val samples: Long,
        val mean: Double,
        val min: Double,
        val max: Double,
        val last: Double,
    ) {
        companion object {
            val EMPTY = FpsStats(0L, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /**
     * Agregados de presents/s (gancho nº 1, INST-P0-3): tasa de
     * frames PRESENTADOS por el swapchain, derivada del contador
     * presentKHR + timestamp, muestreada a 4 Hz. Misma forma que
     * FpsStats a propósito: la comparación directa (presents/s vs
     * fps emulado) es la prueba del gancho 1.
     */
    data class PresentedStats(
        val samples: Long,
        val mean: Double,
        val min: Double,
        val max: Double,
        val last: Double,
    ) {
        companion object {
            val EMPTY = PresentedStats(0L, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /**
     * Foto inmutable para el reporte de bug. `totalSamples` suma las
     * cuatro series: 0 = sin ninguna muestra en este proceso.
     */
    data class Snapshot(
        val vsync: IntervalStats,
        val overlay: IntervalStats,
        val emulatedFps: FpsStats,
        val presentedPerSecond: PresentedStats,
        val startedAtMillis: Long,
    ) {
        val totalSamples: Long get() = vsync.count + overlay.count + emulatedFps.samples + presentedPerSecond.samples

        /**
         * Resumen en texto para el reporte de bug (una línea por
         * serie + lectura). No llama "presentado" a nada: la Fase L
         * no mide frames presentados. Un juego a 30 fps puede ser
         * correcto (INST-P1-1): por eso la lectura compara contra
         * los dos umbrales, no solo contra 16,6 ms.
         */
        fun formatSummary(): String {
            val out = StringBuilder()
            out.appendLine(
                "Vsync del panel (ticks de Choreographer, NO frames presentados): " +
                    "${vsync.count} muestras | media ${"%.1f".format(vsync.meanMs)} ms | " +
                    "p50 ${"%.1f".format(vsync.p50Ms)} ms | p95 ${"%.1f".format(vsync.p95Ms)} ms | " +
                    "p99 ${"%.1f".format(vsync.p99Ms)} ms | máx ${"%.1f".format(vsync.maxMs)} ms | " +
                    ">16,6 ms: ${"%.1f".format(vsync.over16Pct)}% | >33,3 ms: ${"%.1f".format(vsync.over33Pct)}%",
            )
            out.appendLine(
                "Overlay Views (FrameMetrics TOTAL_DURATION, solo UI, no la superficie del juego): " +
                    "${overlay.count} muestras | media ${"%.1f".format(overlay.meanMs)} ms | " +
                    "p50 ${"%.1f".format(overlay.p50Ms)} ms | p95 ${"%.1f".format(overlay.p95Ms)} ms | " +
                    "p99 ${"%.1f".format(overlay.p99Ms)} ms | máx ${"%.1f".format(overlay.maxMs)} ms | " +
                    ">16,6 ms: ${"%.1f".format(overlay.over16Pct)}% | >33,3 ms: ${"%.1f".format(overlay.over33Pct)}%",
            )
            out.appendLine(
                "FPS emulado (getFps() a 4 Hz; sigue siendo el agregado de 1 s del núcleo, no por frame): " +
                    "${emulatedFps.samples} muestras | media ${"%.1f".format(emulatedFps.mean)} fps | " +
                    "mín ${"%.1f".format(emulatedFps.min)} | máx ${"%.1f".format(emulatedFps.max)} | último ${"%.1f".format(emulatedFps.last)}",
            )
            out.appendLine(
                "Frames presentados (gancho nº 1, contador presentKHR + timestamp, a 4 Hz): " +
                    "${presentedPerSecond.samples} muestras | media ${"%.1f".format(presentedPerSecond.mean)}/s | " +
                    "mín ${"%.1f".format(presentedPerSecond.min)} | máx ${"%.1f".format(presentedPerSecond.max)} | último ${"%.1f".format(presentedPerSecond.last)}",
            )
            if (presentedPerSecond.samples > 0 && emulatedFps.samples > 0) {
                if (presentedPerSecond.mean < 1.0 && emulatedFps.mean >= 1.0) {
                    out.appendLine(
                        "Lectura: el juego emula a ${"%.1f".format(emulatedFps.mean)} fps pero presenta ~0/s: " +
                            "nada llega al panel (pantalla negra o superficie perdida): es la prueba del gancho 1.",
                    )
                } else if (presentedPerSecond.mean >= 0.5 * emulatedFps.mean) {
                    out.appendLine(
                        "Lectura: presenta ~${"%.1f".format(presentedPerSecond.mean)}/s de " +
                            "${"%.1f".format(emulatedFps.mean)} emulados: el juego presenta lo que emula.",
                    )
                }
            } else if (presentedPerSecond.samples == 0L && emulatedFps.samples > 0) {
                out.appendLine(
                    "Lectura: sin muestras de presents/s (gancho 1 sin parche o contador a 0): " +
                        "la comparación emulado vs presentado no se pudo hacer en esta sesión.",
                )
            }
            if (vsync.count > 0 && emulatedFps.samples > 0) {
                if (vsync.p95Ms <= 17.5 && emulatedFps.mean < 20.0) {
                    out.appendLine(
                        "Lectura: el panel va a ~60 Hz (vsync p95 ≤ 17,5 ms) mientras el juego emula a " +
                            "${"%.1f".format(emulatedFps.mean)} fps: el problema no es el panel ni el hilo UI.",
                    )
                } else if (vsync.p95Ms > 33.3) {
                    out.appendLine(
                        "Lectura: el propio vsync llega tarde (p95 > 33,3 ms): el hilo UI o el sistema se atasca; " +
                            "eso no prueba que el juego emule lento.",
                    )
                }
            }
            if (overlay.count > 0) {
                if (overlay.p95Ms > OVER_16_MS) {
                    out.appendLine(
                        "Lectura: el overlay/Views supera 16,6 ms en el p95: contribuye al lag percibido.",
                    )
                } else {
                    out.appendLine(
                        "Lectura: el overlay no se atasca (p95 ≤ 16,6 ms): queda exonerado con datos; " +
                            "el lag, si lo hay, no viene de la UI.",
                    )
                }
            }
            out.appendLine(
                "Límite: presents/s es un promedio sobre el intervalo de sondeo (250 ms), no una medida " +
                    "por frame; no mide latencia dentro del núcleo, solo si lo emulado llega al panel.",
            )
            return out.toString().trimEnd()
        }
    }

    private val lock = Any()
    private val vsyncIntervalsMs = ArrayDeque<Double>()
    private val overlayDurationsMs = ArrayDeque<Double>()
    private val fpsSamples = ArrayDeque<Double>()
    private val presentedPerSecondSamples = ArrayDeque<Double>()

    @Volatile
    private var lastVsyncNanos: Long = 0L

    @Volatile
    private var lastPresentedCount: Long = -1L

    @Volatile
    private var lastPresentedTimeNs: Long = 0L

    @Volatile
    private var startedAtMillis: Long = 0L

    private val throttleLock = Any()
    private var lastLogMillis: Long = 0L

    /**
     * Registra un tick de vsync de Choreographer (frameTimeNanos, en
     * ns, dominio CLOCK_MONOTONIC). El primer tick solo fija la
     * referencia; cada tick siguiente aporta el intervalo con el
     * anterior. Un intervalo mayor que 1 s (app en segundo plano,
     * pantalla apagada) se descarta: no es un frame.
     */
    fun recordVsync(frameTimeNanos: Long, nowMillis: Long = System.currentTimeMillis()) {
        val previous = lastVsyncNanos
        lastVsyncNanos = frameTimeNanos
        if (previous == 0L) {
            markStarted(nowMillis)
            return
        }
        val intervalMs = (frameTimeNanos - previous) / 1_000_000.0
        if (!intervalMs.isFinite() || intervalMs <= 0.0 || intervalMs > MAX_VSYNC_INTERVAL_MS) return
        synchronized(lock) {
            markStartedLocked(nowMillis)
            addCapped(vsyncIntervalsMs, intervalMs, MAX_VSYNC_SAMPLES)
        }
    }

    /**
     * Registra la duración total de un frame de Views
     * (FrameMetrics.TOTAL_DURATION, en ms). Solo la UI: nunca se
     * interpreta como tiempo del juego.
     */
    fun recordFrameMetrics(totalDurationMs: Double, nowMillis: Long = System.currentTimeMillis()) {
        if (!totalDurationMs.isFinite() || totalDurationMs < 0.0) return
        synchronized(lock) {
            markStartedLocked(nowMillis)
            addCapped(overlayDurationsMs, totalDurationMs, MAX_OVERLAY_SAMPLES)
        }
    }

    /** Registra una lectura de getFps() (solo valores > 0: -1 = sin partida en curso). */
    fun recordEmulatedFps(fps: Float, nowMillis: Long = System.currentTimeMillis()) {
        if (!fps.isFinite() || fps <= 0f) return
        synchronized(lock) {
            markStartedLocked(nowMillis)
            addCapped(fpsSamples, fps.toDouble(), MAX_FPS_SAMPLES)
        }
    }

    /**
     * Registra una lectura del contador de frames presentados
     * (gancho nº 1, INST-P0-3): `count` de
     * [com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge.getPresentedFrameCount]
     * y `timeNs` de `getLastPresentTimeNs`. La primera lectura solo
     * fija la referencia; cada lectura siguiente aporta una tasa
     * (presents/s) sobre el intervalo entre ambas. Valores -1 (sin
     * núcleo o sin parche) se ignoran. Si el contador retrocede
     * (núcleo reiniciado), se re-fija la referencia sin muestra:
     * no es una tasa negativa. Tasas fuera de 0..1000/s se
     * descartan (reloj o contador corruptos). Pura y testeable.
     */
    fun recordPresented(count: Long, timeNs: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (count < 0L || timeNs <= 0L) return
        val previousCount = lastPresentedCount
        val previousTimeNs = lastPresentedTimeNs
        lastPresentedCount = count
        lastPresentedTimeNs = timeNs
        if (previousCount < 0L || previousTimeNs <= 0L) {
            markStarted(nowMillis)
            return
        }
        if (count < previousCount || timeNs <= previousTimeNs) {
            // Reinicio del núcleo (o reloj que no avanza): nueva
            // referencia, sin muestra.
            return
        }
        val intervalSeconds = (timeNs - previousTimeNs) / 1_000_000_000.0
        if (!intervalSeconds.isFinite() || intervalSeconds <= 0.0) return
        val rate = (count - previousCount) / intervalSeconds
        if (!rate.isFinite() || rate < 0.0 || rate > 1000.0) return
        synchronized(lock) {
            markStartedLocked(nowMillis)
            addCapped(presentedPerSecondSamples, rate, MAX_PRESENTED_SAMPLES)
        }
    }

    /**
     * Throttle del resumen de 1 línea/s para el log: true solo la
     * primera vez o si pasó LOG_THROTTLE_MS desde la última.
     */
    fun shouldLogSummary(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return synchronized(throttleLock) {
            if (lastLogMillis == 0L || nowMillis - lastLogMillis >= LOG_THROTTLE_MS) {
                lastLogMillis = nowMillis
                true
            } else {
                false
            }
        }
    }

    /** Una línea agregada para el log (tag frameprof), como mucho 1/s (ver shouldLogSummary). */
    fun summaryLine(): String {
        val snap = snapshot()
        return "[FrameProf] vsync n=${snap.vsync.count} media=${"%.1f".format(snap.vsync.meanMs)}ms " +
            "p95=${"%.1f".format(snap.vsync.p95Ms)}ms max=${"%.1f".format(snap.vsync.maxMs)}ms " +
            ">16,6ms:${"%.1f".format(snap.vsync.over16Pct)}% >33,3ms:${"%.1f".format(snap.vsync.over33Pct)}% | " +
            "overlay n=${snap.overlay.count} p95=${"%.1f".format(snap.overlay.p95Ms)}ms " +
            "max=${"%.1f".format(snap.overlay.maxMs)}ms | " +
            "fps_emu n=${snap.emulatedFps.samples} media=${"%.1f".format(snap.emulatedFps.mean)} " +
            "min=${"%.1f".format(snap.emulatedFps.min)} max=${"%.1f".format(snap.emulatedFps.max)} | " +
            "presents/s n=${snap.presentedPerSecond.samples} media=${"%.1f".format(snap.presentedPerSecond.mean)} " +
            "min=${"%.1f".format(snap.presentedPerSecond.min)} max=${"%.1f".format(snap.presentedPerSecond.max)}"
    }

    /** Foto actual de los agregados (para el reporte de bug). */
    fun snapshot(): Snapshot {
        synchronized(lock) {
            return Snapshot(
                vsync = aggregate(vsyncIntervalsMs),
                overlay = aggregate(overlayDurationsMs),
                emulatedFps = aggregateFps(fpsSamples),
                presentedPerSecond = aggregatePresented(presentedPerSecondSamples),
                startedAtMillis = startedAtMillis,
            )
        }
    }

    /** Pone todos los contadores a cero. Solo para tests. */
    fun reset() {
        synchronized(lock) {
            vsyncIntervalsMs.clear()
            overlayDurationsMs.clear()
            fpsSamples.clear()
            presentedPerSecondSamples.clear()
            lastVsyncNanos = 0L
            lastPresentedCount = -1L
            lastPresentedTimeNs = 0L
            startedAtMillis = 0L
        }
        synchronized(throttleLock) {
            lastLogMillis = 0L
        }
    }

    private fun markStarted(nowMillis: Long) {
        synchronized(lock) { markStartedLocked(nowMillis) }
    }

    private fun markStartedLocked(nowMillis: Long) {
        if (startedAtMillis == 0L) startedAtMillis = nowMillis
    }

    private fun addCapped(deque: ArrayDeque<Double>, value: Double, max: Int) {
        deque.addLast(value)
        while (deque.size > max) deque.removeFirst()
    }

    private fun aggregate(values: ArrayDeque<Double>): IntervalStats {
        if (values.isEmpty()) return IntervalStats.EMPTY
        val sorted = values.sorted()
        val mean = values.sum() / values.size
        return IntervalStats(
            count = values.size.toLong(),
            meanMs = mean,
            p50Ms = percentile(sorted, 50.0),
            p95Ms = percentile(sorted, 95.0),
            p99Ms = percentile(sorted, 99.0),
            maxMs = sorted.last(),
            over16Pct = 100.0 * values.count { it > OVER_16_MS } / values.size,
            over33Pct = 100.0 * values.count { it > OVER_33_MS } / values.size,
        )
    }

    private fun aggregateFps(values: ArrayDeque<Double>): FpsStats {
        if (values.isEmpty()) return FpsStats.EMPTY
        return FpsStats(
            samples = values.size.toLong(),
            mean = values.sum() / values.size,
            min = values.min(),
            max = values.max(),
            last = values.last(),
        )
    }

    private fun aggregatePresented(values: ArrayDeque<Double>): PresentedStats {
        if (values.isEmpty()) return PresentedStats.EMPTY
        return PresentedStats(
            samples = values.size.toLong(),
            mean = values.sum() / values.size,
            min = values.min(),
            max = values.max(),
            last = values.last(),
        )
    }

    /** Percentil por rango más cercano (índice ceil(p/100 * n) - 1, acotado). */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceAtLeast(1) - 1)
            .coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
