package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.OverlayInputStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contadores y throttle del mando virtual (R4-P0-3 / R4-P1-2).
 * Kotlin puro, sin Android: corre en JVM. El reloj se inyecta
 * (nowMillis) para probar el throttle sin esperas reales.
 */
class OverlayInputStatsTest {

    @Before
    fun setUp() {
        OverlayInputStats.reset()
    }

    @After
    fun tearDown() {
        OverlayInputStats.reset()
    }

    @Test
    fun snapshotStartsEmpty() {
        val snapshot = OverlayInputStats.snapshot()
        assertEquals(0L, snapshot.buttonsOk)
        assertEquals(0L, snapshot.buttonsLost)
        assertEquals(0L, snapshot.axesOk)
        assertEquals(0L, snapshot.axesLost)
        assertEquals(OverlayInputStats.NO_BUTTON, snapshot.lastButtonSdl)
        assertEquals(OverlayInputStats.NO_AXIS, snapshot.lastAxisSdl)
    }

    @Test
    fun deliveredButtonIncrementsOkAndLogsFirstPress() {
        assertTrue(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_000L))
        val snapshot = OverlayInputStats.snapshot()
        assertEquals(1L, snapshot.buttonsOk)
        assertEquals(0L, snapshot.buttonsLost)
        assertEquals(0, snapshot.lastButtonSdl)
        assertTrue(snapshot.lastButtonPressed)
        assertTrue(snapshot.lastButtonDelivered)
    }

    @Test
    fun lostButtonIncrementsLostAndNeverLogs() {
        assertFalse(OverlayInputStats.recordButton(0, true, delivered = false, nowMillis = 10_000L))
        val snapshot = OverlayInputStats.snapshot()
        assertEquals(0L, snapshot.buttonsOk)
        assertEquals(1L, snapshot.buttonsLost)
        assertFalse(snapshot.lastButtonDelivered)
    }

    @Test
    fun sameButtonWithinThrottleWindowDoesNotLogButStillCounts() {
        assertTrue(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_000L))
        assertFalse(OverlayInputStats.recordButton(0, false, delivered = true, nowMillis = 10_500L))
        assertFalse(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_999L))
        assertEquals(3L, OverlayInputStats.snapshot().buttonsOk)
    }

    @Test
    fun sameButtonAfterThrottleWindowLogsAgain() {
        assertTrue(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_000L))
        assertTrue(OverlayInputStats.recordButton(0, false, delivered = true, nowMillis = 11_000L))
    }

    @Test
    fun throttleIsIndependentPerButton() {
        assertTrue(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_000L))
        assertTrue(OverlayInputStats.recordButton(1, true, delivered = true, nowMillis = 10_000L))
        assertFalse(OverlayInputStats.recordButton(0, false, delivered = true, nowMillis = 10_100L))
        assertFalse(OverlayInputStats.recordButton(1, false, delivered = true, nowMillis = 10_100L))
    }

    @Test
    fun lostPressDoesNotConsumeThrottleWindow() {
        assertFalse(OverlayInputStats.recordButton(0, true, delivered = false, nowMillis = 10_000L))
        assertTrue(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_100L))
    }

    @Test
    fun axisCountsAndThrottlesLikeButton() {
        assertTrue(OverlayInputStats.recordAxis(0, 12000, delivered = true, nowMillis = 10_000L))
        assertFalse(OverlayInputStats.recordAxis(0, 13000, delivered = true, nowMillis = 10_200L))
        assertTrue(OverlayInputStats.recordAxis(0, 0, delivered = true, nowMillis = 11_000L))
        assertTrue(OverlayInputStats.recordAxis(1, -5000, delivered = true, nowMillis = 11_000L))
        val snapshot = OverlayInputStats.snapshot()
        assertEquals(4L, snapshot.axesOk)
        assertEquals(0L, snapshot.axesLost)
        assertEquals(1, snapshot.lastAxisSdl)
        assertEquals(-5000, snapshot.lastAxisValue.toInt())
        assertTrue(snapshot.lastAxisDelivered)
    }

    @Test
    fun lostAxisIncrementsLostAndNeverLogs() {
        assertFalse(OverlayInputStats.recordAxis(2, 8000, delivered = false, nowMillis = 10_000L))
        val snapshot = OverlayInputStats.snapshot()
        assertEquals(0L, snapshot.axesOk)
        assertEquals(1L, snapshot.axesLost)
        assertEquals(2, snapshot.lastAxisSdl)
    }

    @Test
    fun resetClearsCountersAndThrottle() {
        OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_000L)
        OverlayInputStats.recordAxis(0, 5000, delivered = true, nowMillis = 10_000L)
        OverlayInputStats.reset()
        val snapshot = OverlayInputStats.snapshot()
        assertEquals(0L, snapshot.buttonsOk)
        assertEquals(0L, snapshot.axesOk)
        assertEquals(OverlayInputStats.NO_BUTTON, snapshot.lastButtonSdl)
        assertEquals(OverlayInputStats.NO_AXIS, snapshot.lastAxisSdl)
        assertTrue(OverlayInputStats.recordButton(0, true, delivered = true, nowMillis = 10_100L))
    }
}
