package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.ui.BENCHMARK_PENDING_MAX_AGE_MILLIS
import com.danielsnagustin.childoflight.launcher.ui.isBenchmarkPendingStale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-A2: una prueba de 30 s pendiente caduca a los 15 minutos. La
 * regla es pura (isBenchmarkPendingStale) para poder probarla en JVM:
 * el ViewModel la aplica en recordBenchmarkFps(), en
 * noteReturnedFromGame() y como defensa dentro de completeBenchmark().
 */
class BenchmarkPendingExpiryTest {

    @Test
    fun maxAgeIs15Minutes() {
        assertEquals(15L * 60 * 1000, BENCHMARK_PENDING_MAX_AGE_MILLIS)
    }

    @Test
    fun freshPendingIsNotStale() {
        val now = 1_800_000_000_000L
        assertFalse(isBenchmarkPendingStale(now, now))
        assertFalse(isBenchmarkPendingStale(now - 60 * 1000, now))
        assertFalse(isBenchmarkPendingStale(now - 14 * 60 * 1000, now))
    }

    @Test
    fun pendingOlderThan15MinutesIsStale() {
        val now = 1_800_000_000_000L
        assertTrue(isBenchmarkPendingStale(now - BENCHMARK_PENDING_MAX_AGE_MILLIS - 1, now))
        assertTrue(isBenchmarkPendingStale(now - 16 * 60 * 1000, now))
        assertTrue(isBenchmarkPendingStale(now - 24 * 60 * 60 * 1000, now))
    }

    @Test
    fun futureStartIsNotStale() {
        // Reloj movido atrás: una edad negativa no caduca.
        val now = 1_800_000_000_000L
        assertFalse(isBenchmarkPendingStale(now + 60 * 1000, now))
    }
}
