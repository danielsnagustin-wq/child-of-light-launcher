package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.RippleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.54: gota al presionar un control del overlay. La maquina de
 * estados es logica pura (sin Android): 350 ms, radio con easing
 * ease-out cuadratico, alfa lineal 1..0, y dura sus 350 ms completos
 * aunque el dedo se levante antes. Puro JVM.
 */
class RippleStateTest {

    private fun rippleAt(x: Float = 40f, y: Float = 60f, startMs: Long = 1000L): RippleState {
        val ripple = RippleState()
        ripple.start(x, y, startMs)
        return ripple
    }

    @Test
    fun inactiveBeforeStart() {
        val ripple = RippleState()
        assertFalse(ripple.isActive)
        assertFalse(ripple.isDone(1000L))
        assertEquals(0f, ripple.progress(1000L), 0f)
        assertEquals(0f, ripple.radiusAt(1000L, 100f), 0f)
        assertEquals(1f, ripple.alphaAt(1000L), 0f)
    }

    @Test
    fun startStoresTouchOrigin() {
        val ripple = rippleAt(x = 40f, y = 60f, startMs = 1000L)
        assertTrue(ripple.isActive)
        assertEquals(40f, ripple.originX, 0f)
        assertEquals(60f, ripple.originY, 0f)
    }

    @Test
    fun progressAtStartIsZero() {
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(0f, ripple.progress(1000L), 0f)
        assertFalse(ripple.isDone(1000L))
    }

    @Test
    fun progressAtHalfDurationIsHalf() {
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(0.5f, ripple.progress(1000L + RippleState.DURATION_MS / 2), 0.001f)
    }

    @Test
    fun progressAtEndIsOneAndIsDone() {
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(1f, ripple.progress(1000L + RippleState.DURATION_MS), 0f)
        assertTrue(ripple.isDone(1000L + RippleState.DURATION_MS))
        assertTrue(ripple.isDone(1000L + RippleState.DURATION_MS + 500L))
    }

    @Test
    fun alphaFadesLinearlyFromOneToZero() {
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(1f, ripple.alphaAt(1000L), 0f)
        assertEquals(0.5f, ripple.alphaAt(1000L + RippleState.DURATION_MS / 2), 0.001f)
        assertEquals(0f, ripple.alphaAt(1000L + RippleState.DURATION_MS), 0f)
    }

    @Test
    fun radiusStartsAtZeroAndEndsAtMax() {
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(0f, ripple.radiusAt(1000L, 100f), 0f)
        assertEquals(100f, ripple.radiusAt(1000L + RippleState.DURATION_MS, 100f), 0f)
    }

    @Test
    fun radiusUsesEaseOutQuadratic() {
        // A mitad de la gota (t = 0.5) el easing ease-out 1 - (1-t)^2
        // vale 0.75: la gota ya recorrio 3/4 del radio, no la mitad.
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(75f, ripple.radiusAt(1000L + RippleState.DURATION_MS / 2, 100f), 0.001f)
        // A un cuarto (t = 0.25) el easing vale 0.4375, pero
        // DURATION_MS (350) no es divisible entre 4: la division
        // entera da 87 ms, t = 87/350 = 0.2486 y el radio real es
        // 43.5355, no 43.75. La implementacion es correcta; el
        // valor esperado es el del instante real.
        assertEquals(43.5355f, ripple.radiusAt(1000L + RippleState.DURATION_MS / 4, 100f), 0.001f)
    }

    @Test
    fun radiusNeverExceedsMaxAfterDuration() {
        val ripple = rippleAt(startMs = 1000L)
        assertEquals(100f, ripple.radiusAt(1000L + RippleState.DURATION_MS + 1000L, 100f), 0f)
    }

    @Test
    fun secondStartRestartsWithNewOrigin() {
        val ripple = rippleAt(x = 10f, y = 20f, startMs = 1000L)
        assertEquals(0.5f, ripple.progress(1000L + RippleState.DURATION_MS / 2), 0.001f)
        ripple.start(70f, 80f, 2000L)
        assertEquals(70f, ripple.originX, 0f)
        assertEquals(80f, ripple.originY, 0f)
        assertEquals(0f, ripple.progress(2000L), 0f)
        assertFalse(ripple.isDone(2000L))
        assertEquals(0f, ripple.radiusAt(2000L, 100f), 0f)
    }

    @Test
    fun clearDeactivates() {
        val ripple = rippleAt(startMs = 1000L)
        ripple.clear()
        assertFalse(ripple.isActive)
        assertFalse(ripple.isDone(1000L + RippleState.DURATION_MS))
        assertEquals(0f, ripple.progress(1000L + RippleState.DURATION_MS), 0f)
    }

    @Test
    fun durationIs350Ms() {
        assertEquals(350L, RippleState.DURATION_MS)
    }
}
