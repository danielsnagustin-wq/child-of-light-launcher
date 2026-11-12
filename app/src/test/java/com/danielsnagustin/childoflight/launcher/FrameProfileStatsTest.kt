package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.FrameProfileStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Perfil de frames, Fase L (ronda 0.2.66, INST-P0-2/INST-P0-3/
 * INST-P1-1). Kotlin puro, sin Android: corre en JVM. Los ticks de
 * vsync se inyectan en nanos (frameTimeNanos) y el resto en ms o
 * fps, sin esperas reales.
 */
class FrameProfileStatsTest {

    @Before
    fun setUp() {
        FrameProfileStats.reset()
    }

    @After
    fun tearDown() {
        FrameProfileStats.reset()
    }

    @Test
    fun snapshotStartsEmpty() {
        val snapshot = FrameProfileStats.snapshot()
        assertEquals(0L, snapshot.totalSamples)
        assertEquals(0L, snapshot.vsync.count)
        assertEquals(0L, snapshot.overlay.count)
        assertEquals(0L, snapshot.emulatedFps.samples)
    }

    @Test
    fun firstVsyncTickProducesNoInterval() {
        FrameProfileStats.recordVsync(1_000_000_000L, nowMillis = 10_000L)
        assertEquals(0L, FrameProfileStats.snapshot().vsync.count)
    }

    @Test
    fun vsyncIntervalIsMeasuredBetweenConsecutiveTicks() {
        FrameProfileStats.recordVsync(1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordVsync(1_016_600_000L, nowMillis = 10_016L)
        val vsync = FrameProfileStats.snapshot().vsync
        assertEquals(1L, vsync.count)
        assertEquals(16.6, vsync.meanMs, 0.001)
        assertEquals(16.6, vsync.maxMs, 0.001)
        assertEquals(0.0, vsync.over16Pct, 0.001)
        assertEquals(0.0, vsync.over33Pct, 0.001)
    }

    @Test
    fun vsyncGapOverOneSecondIsDiscardedNotACountedFrame() {
        FrameProfileStats.recordVsync(1_000_000_000L, nowMillis = 10_000L)
        // 2 s después (app en segundo plano): no es un frame.
        FrameProfileStats.recordVsync(3_000_000_000L, nowMillis = 12_000L)
        FrameProfileStats.recordVsync(3_016_600_000L, nowMillis = 12_016L)
        val vsync = FrameProfileStats.snapshot().vsync
        assertEquals(1L, vsync.count)
        assertEquals(16.6, vsync.meanMs, 0.001)
    }

    @Test
    fun percentilesAndBothThresholds() {
        // 95 intervalos a 16,6 ms + 5 a 50 ms: p50 16,6, p95 16,6,
        // p99 50, >16,6 ms: 5%, >33,3 ms: 5% (INST-P1-1: dos
        // umbrales, no solo 16,6 ms).
        var t = 1_000_000_000L
        FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        repeat(95) {
            t += 16_600_000L
            FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        }
        repeat(5) {
            t += 50_000_000L
            FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        }
        val vsync = FrameProfileStats.snapshot().vsync
        assertEquals(100L, vsync.count)
        assertEquals(16.6, vsync.p50Ms, 0.001)
        assertEquals(16.6, vsync.p95Ms, 0.001)
        assertEquals(50.0, vsync.p99Ms, 0.001)
        assertEquals(50.0, vsync.maxMs, 0.001)
        assertEquals(18.27, vsync.meanMs, 0.001)
        assertEquals(5.0, vsync.over16Pct, 0.001)
        assertEquals(5.0, vsync.over33Pct, 0.001)
    }

    @Test
    fun steadyThirtyThreeMsIntervalsAreNotOverThirtyThree() {
        // 30 fps correctos (INST-P1-1): 33,3 ms no supera 33,3 ms.
        var t = 1_000_000_000L
        FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        repeat(10) {
            t += 33_300_000L
            FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        }
        val vsync = FrameProfileStats.snapshot().vsync
        assertEquals(10L, vsync.count)
        assertEquals(0.0, vsync.over33Pct, 0.001)
        assertEquals(100.0, vsync.over16Pct, 0.001)
    }

    @Test
    fun overlayDurationsAggregateLikeVsync() {
        FrameProfileStats.recordFrameMetrics(2.0, nowMillis = 10_000L)
        FrameProfileStats.recordFrameMetrics(20.0, nowMillis = 10_000L)
        val overlay = FrameProfileStats.snapshot().overlay
        assertEquals(2L, overlay.count)
        assertEquals(11.0, overlay.meanMs, 0.001)
        assertEquals(20.0, overlay.p95Ms, 0.001)
        assertEquals(50.0, overlay.over16Pct, 0.001)
        assertEquals(0.0, overlay.over33Pct, 0.001)
    }

    @Test
    fun invalidOverlayDurationsAreIgnored() {
        FrameProfileStats.recordFrameMetrics(-1.0, nowMillis = 10_000L)
        FrameProfileStats.recordFrameMetrics(Double.NaN, nowMillis = 10_000L)
        assertEquals(0L, FrameProfileStats.snapshot().overlay.count)
    }

    @Test
    fun emulatedFpsSamplesAggregateAndIgnoreNoGame() {
        FrameProfileStats.recordEmulatedFps(30f, nowMillis = 10_000L)
        FrameProfileStats.recordEmulatedFps(15f, nowMillis = 10_250L)
        FrameProfileStats.recordEmulatedFps(-1f, nowMillis = 10_500L)
        FrameProfileStats.recordEmulatedFps(0f, nowMillis = 10_750L)
        val fps = FrameProfileStats.snapshot().emulatedFps
        assertEquals(2L, fps.samples)
        assertEquals(22.5, fps.mean, 0.001)
        assertEquals(15.0, fps.min, 0.001)
        assertEquals(30.0, fps.max, 0.001)
        assertEquals(15.0, fps.last, 0.001)
    }

    @Test
    fun summaryThrottleAllowsOneLinePerSecond() {
        assertTrue(FrameProfileStats.shouldLogSummary(nowMillis = 10_000L))
        assertFalse(FrameProfileStats.shouldLogSummary(nowMillis = 10_500L))
        assertFalse(FrameProfileStats.shouldLogSummary(nowMillis = 10_999L))
        assertTrue(FrameProfileStats.shouldLogSummary(nowMillis = 11_000L))
    }

    @Test
    fun summaryLineHasAllThreeSeries() {
        var t = 1_000_000_000L
        FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        t += 16_600_000L
        FrameProfileStats.recordVsync(t, nowMillis = 10_016L)
        FrameProfileStats.recordFrameMetrics(3.0, nowMillis = 10_016L)
        FrameProfileStats.recordEmulatedFps(30f, nowMillis = 10_016L)
        val line = FrameProfileStats.summaryLine()
        assertTrue(line.contains("[FrameProf]"))
        assertTrue(line.contains("vsync n=1"))
        assertTrue(line.contains("overlay n=1"))
        assertTrue(line.contains("fps_emu n=1"))
        assertTrue(line.contains(">33,3ms:"))
    }

    @Test
    fun formatSummaryReadsPanelSixtyWhileGameFifteen() {
        // Panel a 60 Hz (vsync p95 16,6) y juego a 15 fps emulados:
        // la lectura dice que el problema no es el panel ni el
        // hilo UI. Nunca dice "invisible" (corrección Codex 1).
        var t = 1_000_000_000L
        FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        repeat(60) {
            t += 16_600_000L
            FrameProfileStats.recordVsync(t, nowMillis = 10_000L)
        }
        repeat(8) { FrameProfileStats.recordEmulatedFps(15f, nowMillis = 10_000L) }
        val text = FrameProfileStats.snapshot().formatSummary()
        assertTrue(text.contains("NO frames presentados"))
        assertTrue(text.contains("33,3"))
        assertTrue(text.contains("no es el panel ni el hilo UI"))
        assertFalse(text.contains("invisible"))
    }

    @Test
    fun formatSummaryFlagsOverlayJank() {
        repeat(10) { FrameProfileStats.recordFrameMetrics(20.0, nowMillis = 10_000L) }
        val text = FrameProfileStats.snapshot().formatSummary()
        assertTrue(text.contains("contribuye al lag percibido"))
    }

    @Test
    fun formatSummaryExoneratesOverlayWhenFast() {
        repeat(10) { FrameProfileStats.recordFrameMetrics(2.0, nowMillis = 10_000L) }
        val text = FrameProfileStats.snapshot().formatSummary()
        assertTrue(text.contains("el overlay no se atasca"))
    }

    @Test
    fun resetClearsCountersAndThrottle() {
        FrameProfileStats.recordVsync(1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordVsync(1_016_600_000L, nowMillis = 10_016L)
        FrameProfileStats.recordEmulatedFps(30f, nowMillis = 10_016L)
        assertTrue(FrameProfileStats.shouldLogSummary(nowMillis = 10_000L))
        FrameProfileStats.reset()
        assertEquals(0L, FrameProfileStats.snapshot().totalSamples)
        assertTrue(FrameProfileStats.shouldLogSummary(nowMillis = 10_100L))
    }

    // Gancho nº 1 (INST-P0-3, ronda 0.2.66, integración del
    // coordinador): presents/s a partir del contador presentKHR +
    // timestamp. El primer tick solo fija la referencia.

    @Test
    fun presentedRateIsComputedFromCounterAndTimestamp() {
        FrameProfileStats.recordPresented(100L, 1_000_000_000L, nowMillis = 10_000L)
        assertEquals(0L, FrameProfileStats.snapshot().presentedPerSecond.samples)
        // +30 presents en 1 s = 30/s.
        FrameProfileStats.recordPresented(130L, 2_000_000_000L, nowMillis = 11_000L)
        val presented = FrameProfileStats.snapshot().presentedPerSecond
        assertEquals(1L, presented.samples)
        assertEquals(30.0, presented.mean, 0.001)
        assertEquals(30.0, presented.last, 0.001)
    }

    @Test
    fun presentedStubValuesAreIgnored() {
        FrameProfileStats.recordPresented(-1L, -1L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(5L, -1L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(-1L, 1_000_000_000L, nowMillis = 10_000L)
        assertEquals(0L, FrameProfileStats.snapshot().presentedPerSecond.samples)
    }

    @Test
    fun presentedCounterRestartRebaselinesWithoutSample() {
        FrameProfileStats.recordPresented(100L, 1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(130L, 2_000_000_000L, nowMillis = 11_000L)
        assertEquals(1L, FrameProfileStats.snapshot().presentedPerSecond.samples)
        // El contador vuelve a 5 (núcleo reiniciado): re-fija la
        // referencia sin muestra, no es una tasa negativa.
        FrameProfileStats.recordPresented(5L, 3_000_000_000L, nowMillis = 12_000L)
        assertEquals(1L, FrameProfileStats.snapshot().presentedPerSecond.samples)
        FrameProfileStats.recordPresented(35L, 4_000_000_000L, nowMillis = 13_000L)
        val presented = FrameProfileStats.snapshot().presentedPerSecond
        assertEquals(2L, presented.samples)
        assertEquals(30.0, presented.mean, 0.001)
    }

    @Test
    fun summaryLineHasPresentedSeries() {
        FrameProfileStats.recordEmulatedFps(30f, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(100L, 1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(130L, 2_000_000_000L, nowMillis = 11_000L)
        val line = FrameProfileStats.summaryLine()
        assertTrue(line.contains("fps_emu n=1"))
        assertTrue(line.contains("presents/s n=1"))
        assertTrue(line.contains("media=30.0"))
    }

    @Test
    fun formatSummaryFlagsBlackScreen() {
        // El juego emula a 30 fps pero presenta ~0/s: nada llega
        // al panel (pantalla negra o superficie perdida).
        repeat(8) { FrameProfileStats.recordEmulatedFps(30f, nowMillis = 10_000L) }
        FrameProfileStats.recordPresented(100L, 1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(100L, 2_000_000_000L, nowMillis = 11_000L)
        FrameProfileStats.recordPresented(100L, 3_000_000_000L, nowMillis = 12_000L)
        val text = FrameProfileStats.snapshot().formatSummary()
        assertTrue(text.contains("nada llega al panel"))
        assertTrue(text.contains("presents/s"))
    }

    @Test
    fun formatSummaryReadsPresentedMatchesEmulated() {
        repeat(8) { FrameProfileStats.recordEmulatedFps(30f, nowMillis = 10_000L) }
        FrameProfileStats.recordPresented(100L, 1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(130L, 2_000_000_000L, nowMillis = 11_000L)
        val text = FrameProfileStats.snapshot().formatSummary()
        assertTrue(text.contains("presenta lo que emula"))
    }

    @Test
    fun resetClearsPresentedSeries() {
        FrameProfileStats.recordPresented(100L, 1_000_000_000L, nowMillis = 10_000L)
        FrameProfileStats.recordPresented(130L, 2_000_000_000L, nowMillis = 11_000L)
        assertEquals(1L, FrameProfileStats.snapshot().presentedPerSecond.samples)
        FrameProfileStats.reset()
        assertEquals(0L, FrameProfileStats.snapshot().presentedPerSecond.samples)
        assertEquals(0L, FrameProfileStats.snapshot().totalSamples)
        // Tras el reset, la primera lectura vuelve a ser referencia.
        FrameProfileStats.recordPresented(200L, 5_000_000_000L, nowMillis = 20_000L)
        assertEquals(0L, FrameProfileStats.snapshot().presentedPerSecond.samples)
    }
}
