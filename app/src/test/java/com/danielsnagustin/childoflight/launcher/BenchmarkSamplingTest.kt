package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.averageBenchmarkFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Media de FPS de una prueba de 30 s medida por el núcleo
 * (auditoría de funcionalidad 2026-11-08). Función pura:
 * EmbeddedGameActivity muestrea getFps() cada segundo y guarda
 * esta media al salir del juego.
 */
class BenchmarkSamplingTest {

    @Test
    fun emptySamplesGiveNull() {
        assertNull(averageBenchmarkFps(emptyList()))
    }

    @Test
    fun invalidSamplesAreIgnored() {
        // -1 es "sin juego" (getFps sin núcleo) y 0 es "sin medir".
        assertNull(averageBenchmarkFps(listOf(-1f, 0f, -1f)))
        assertEquals(30f, averageBenchmarkFps(listOf(-1f, 0f, 30f))!!, 0.001f)
    }

    @Test
    fun meanOfValidSamples() {
        assertEquals(45f, averageBenchmarkFps(listOf(30f, 60f))!!, 0.001f)
        assertEquals(30f, averageBenchmarkFps(listOf(29f, 30f, 31f))!!, 0.001f)
    }

    @Test
    fun singleSampleIsItsOwnMean() {
        assertEquals(60f, averageBenchmarkFps(listOf(60f))!!, 0.001f)
    }

    @Test
    fun trimmedMeanDropsMinAndMax() {
        // R2-P2-29: un tirón a 1 fps y un pico a 240 no deben decidir
        // la prueba: la media recortada quita ambos extremos.
        assertEquals(30f, averageBenchmarkFps(listOf(1f, 30f, 30f, 30f, 240f))!!, 0.001f)
        // Con 3 muestras solo queda la central.
        assertEquals(30f, averageBenchmarkFps(listOf(10f, 30f, 90f))!!, 0.001f)
    }
}
