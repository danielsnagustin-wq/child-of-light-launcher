package com.danielsnagustin.childoflight.launcher.core

/**
 * Gota de agua al presionar un control del overlay: una onda que nace
 * en el punto del toque, se expande y se desvanece en 350 ms.
 *
 * Logica pura, sin Android: la vista solo le pregunta el radio y el
 * alfa para el instante actual y dibuja el anillo. Probada en
 * RippleStateTest (JVM, sin emulador).
 *
 * Ciclo de vida: start guarda el origen y el instante; la animacion
 * dura sus 350 ms completos aunque el dedo se levante antes (al
 * reves que pressAmount, que invierte al soltar). Cuando isDone es
 * true la vista deja de dibujar nada y puede limpiar el estado.
 *
 * Sin asignaciones por frame: los getters solo hacen aritmetica con
 * Float y Long, sin listas ni objetos nuevos.
 */
class RippleState {

    companion object {
        /** Duracion completa de la gota, en milisegundos. */
        const val DURATION_MS: Long = 350L
    }

    /** Punto del toque donde nace la gota, en coordenadas de la vista. */
    var originX: Float = 0f
        private set

    /** Punto del toque donde nace la gota, en coordenadas de la vista. */
    var originY: Float = 0f
        private set

    /** Instante del toque en ms, o -1 si no hay ninguna gota activa. */
    private var startMs: Long = -1L

    /** True mientras hay una gota en curso (aunque ya este terminada). */
    val isActive: Boolean
        get() = startMs >= 0L

    /**
     * Empieza una gota nueva en (x, y). Si habia otra en curso se
     * reinicia: el origen y el instante son los del toque nuevo.
     */
    fun start(x: Float, y: Float, nowMs: Long) {
        originX = x
        originY = y
        startMs = nowMs
    }

    /**
     * Progreso 0..1 de la gota en el instante dado. 0 antes de
     * empezar (o sin gota activa) y 1 al cumplirse la duracion.
     */
    fun progress(nowMs: Long): Float {
        if (!isActive) return 0f
        val elapsed = nowMs - startMs
        if (elapsed <= 0L) return 0f
        if (elapsed >= DURATION_MS) return 1f
        return elapsed.toFloat() / DURATION_MS.toFloat()
    }

    /**
     * Radio de la gota en el instante dado: 0 al empezar y
     * maxRadiusPx al final, con easing ease-out cuadratico
     * (1 - (1 - t)^2): arranca rapido y se frena al llegar al borde.
     */
    fun radiusAt(nowMs: Long, maxRadiusPx: Float): Float {
        val t = progress(nowMs)
        val eased = 1f - (1f - t) * (1f - t)
        return maxRadiusPx * eased
    }

    /** Alfa 1..0 de la gota: opaca al empezar, invisible al final. */
    fun alphaAt(nowMs: Long): Float = 1f - progress(nowMs)

    /** True cuando la gota ya cumplio sus 350 ms. */
    fun isDone(nowMs: Long): Boolean = isActive && progress(nowMs) >= 1f

    /** Olvida la gota: la vista deja de dibujar nada. */
    fun clear() {
        startMs = -1L
    }
}
