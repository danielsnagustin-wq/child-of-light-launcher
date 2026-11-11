package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.TouchStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contadores y throttle del táctil libre del juego (0.2.63, ítem D).
 * Kotlin puro, sin Android: corre en JVM. El reloj se inyecta
 * (nowMillis) para probar el throttle sin esperas reales.
 */
class TouchStatsTest {

    @Before
    fun setUp() {
        TouchStats.reset()
    }

    @After
    fun tearDown() {
        TouchStats.reset()
    }

    @Test
    fun snapshotStartsEmpty() {
        val snapshot = TouchStats.snapshot()
        assertEquals(0L, snapshot.freeDownOk)
        assertEquals(0L, snapshot.freeDownLost)
        assertEquals(0L, snapshot.freeUpOk)
        assertEquals(0L, snapshot.forwardedDownOk)
        assertEquals(0L, snapshot.forwardedDownLost)
        assertEquals(0L, snapshot.forwardedUpOk)
        assertEquals(TouchStats.NO_POSITION, snapshot.lastX)
        assertEquals(TouchStats.NO_POSITION, snapshot.lastY)
        assertEquals("", snapshot.lastAction)
        assertEquals(TouchStats.NO_EVENT_MILLIS, snapshot.lastEventMillis)
    }

    @Test
    fun deliveredFreeDownIncrementsOkAndLogsFirstTouch() {
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 500f, y = 300f, nowMillis = 10_000L))
        val snapshot = TouchStats.snapshot()
        assertEquals(1L, snapshot.freeDownOk)
        assertEquals(0L, snapshot.freeDownLost)
        assertEquals(500f, snapshot.lastX)
        assertEquals(300f, snapshot.lastY)
        assertEquals("DOWN", snapshot.lastAction)
        assertTrue(snapshot.lastDelivered)
        assertEquals(10_000L, snapshot.lastEventMillis)
    }

    @Test
    fun lostFreeDownIncrementsLostAndNeverLogs() {
        assertFalse(TouchStats.recordFreeDown(delivered = false, x = 500f, y = 300f, nowMillis = 10_000L))
        val snapshot = TouchStats.snapshot()
        assertEquals(0L, snapshot.freeDownOk)
        assertEquals(1L, snapshot.freeDownLost)
        assertFalse(snapshot.lastDelivered)
    }

    @Test
    fun freeDownWithinThrottleWindowDoesNotLogButStillCounts() {
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_000L))
        assertFalse(TouchStats.recordFreeDown(delivered = true, x = 200f, y = 200f, nowMillis = 10_500L))
        assertFalse(TouchStats.recordFreeDown(delivered = true, x = 300f, y = 300f, nowMillis = 10_999L))
        assertEquals(3L, TouchStats.snapshot().freeDownOk)
    }

    @Test
    fun freeDownAfterThrottleWindowLogsAgain() {
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_000L))
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 200f, y = 200f, nowMillis = 11_000L))
    }

    @Test
    fun lostDownDoesNotConsumeThrottleWindow() {
        assertFalse(TouchStats.recordFreeDown(delivered = false, x = 100f, y = 100f, nowMillis = 10_000L))
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_100L))
    }

    @Test
    fun freeUpCountsOnlyWhenDelivered() {
        TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_000L)
        TouchStats.recordFreeUp(delivered = true, x = 100f, y = 100f, nowMillis = 10_100L)
        assertEquals(1L, TouchStats.snapshot().freeUpOk)
        TouchStats.recordFreeUp(delivered = false, x = 100f, y = 100f, nowMillis = 10_200L)
        assertEquals(1L, TouchStats.snapshot().freeUpOk)
        assertEquals("UP", TouchStats.snapshot().lastAction)
    }

    @Test
    fun freeMoveUpdatesPositionWithoutCounting() {
        TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_000L)
        TouchStats.recordFreeMove(x = 150f, y = 120f, nowMillis = 10_100L)
        val snapshot = TouchStats.snapshot()
        assertEquals(1L, snapshot.freeDownOk)
        assertEquals(150f, snapshot.lastX)
        assertEquals(120f, snapshot.lastY)
        assertEquals("MOVE", snapshot.lastAction)
    }

    @Test
    fun forwardedDownHasItsOwnCounterAndThrottle() {
        // El reenvío sintético (2º dedo, zona de Igniculus) no se
        // mezcla con el toque libre: si no, un dedo en la zona
        // inflaría el contador de toques libres y el reporte mentiría.
        assertTrue(TouchStats.recordForwardedDown(delivered = true, x = 900f, y = 800f, nowMillis = 10_000L))
        assertFalse(TouchStats.recordForwardedDown(delivered = true, x = 910f, y = 810f, nowMillis = 10_100L))
        val snapshot = TouchStats.snapshot()
        assertEquals(2L, snapshot.forwardedDownOk)
        assertEquals(0L, snapshot.freeDownOk)
        assertEquals(0L, snapshot.freeDownLost)
    }

    @Test
    fun forwardedThrottleIsIndependentFromFreeThrottle() {
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_000L))
        assertTrue(TouchStats.recordForwardedDown(delivered = true, x = 900f, y = 800f, nowMillis = 10_000L))
        assertFalse(TouchStats.recordFreeDown(delivered = true, x = 110f, y = 110f, nowMillis = 10_100L))
        assertFalse(TouchStats.recordForwardedDown(delivered = true, x = 910f, y = 810f, nowMillis = 10_100L))
    }

    @Test
    fun forwardedUpCountsOnlyWhenDelivered() {
        TouchStats.recordForwardedDown(delivered = true, x = 900f, y = 800f, nowMillis = 10_000L)
        TouchStats.recordForwardedUp(delivered = true, x = 900f, y = 800f, nowMillis = 10_100L)
        assertEquals(1L, TouchStats.snapshot().forwardedUpOk)
        TouchStats.recordForwardedUp(delivered = false, x = 900f, y = 800f, nowMillis = 10_200L)
        assertEquals(1L, TouchStats.snapshot().forwardedUpOk)
    }

    @Test
    fun resetClearsCountersAndThrottle() {
        TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_000L)
        TouchStats.recordForwardedDown(delivered = true, x = 900f, y = 800f, nowMillis = 10_000L)
        TouchStats.reset()
        val snapshot = TouchStats.snapshot()
        assertEquals(0L, snapshot.freeDownOk)
        assertEquals(0L, snapshot.forwardedDownOk)
        assertEquals(TouchStats.NO_POSITION, snapshot.lastX)
        assertEquals(TouchStats.NO_EVENT_MILLIS, snapshot.lastEventMillis)
        assertTrue(TouchStats.recordFreeDown(delivered = true, x = 100f, y = 100f, nowMillis = 10_100L))
    }
}
