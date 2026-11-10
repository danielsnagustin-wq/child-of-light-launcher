package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.SdlButtonRefCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refcount por botón SDL (R6-P1-14). Kotlin puro, sin Android: corre en JVM.
 */
class SdlButtonRefCounterTest {

    @Test
    fun firstPressSendsSecondPressDoesNot() {
        val counter = SdlButtonRefCounter()
        assertTrue(counter.press(9))
        assertFalse(counter.press(9))
        assertEquals(2, counter.heldCount(9))
    }

    @Test
    fun releaseSendsOnlyWhenLastHolderReleases() {
        val counter = SdlButtonRefCounter()
        counter.press(9)
        counter.press(9)
        assertFalse(counter.release(9))
        assertEquals(1, counter.heldCount(9))
        assertTrue(counter.release(9))
        assertEquals(0, counter.heldCount(9))
    }

    @Test
    fun releaseWithoutHolderSendsNothing() {
        val counter = SdlButtonRefCounter()
        assertFalse(counter.release(9))
        assertEquals(0, counter.heldCount(9))
    }

    @Test
    fun glowAndL1ButtonShareSdl9() {
        // Caso concreto del informe: brillo (SDL 9) + botón del editor
        // asignado a L1 (también 9). Soltar el botón no apaga el brillo.
        val counter = SdlButtonRefCounter()
        assertTrue(counter.press(9)) // brillo
        assertFalse(counter.press(9)) // botón L1
        assertFalse(counter.release(9)) // suelta el botón L1
        assertTrue(counter.release(9)) // suelta el brillo
    }

    @Test
    fun triggerAxisMinusFourSharesCounter() {
        // El brillo (-4) y un botón asignado a L2 (-4) comparten contador.
        val counter = SdlButtonRefCounter()
        assertTrue(counter.press(-4))
        assertFalse(counter.press(-4))
        assertFalse(counter.release(-4))
        assertTrue(counter.release(-4))
    }

    @Test
    fun differentButtonsAreIndependent() {
        val counter = SdlButtonRefCounter()
        assertTrue(counter.press(9))
        assertTrue(counter.press(2))
        assertTrue(counter.release(9))
        assertEquals(1, counter.heldCount(2))
    }

    @Test
    fun resetForgetsHolders() {
        val counter = SdlButtonRefCounter()
        counter.press(9)
        counter.press(9)
        counter.reset()
        assertEquals(0, counter.heldCount(9))
        assertFalse(counter.release(9))
        assertTrue(counter.press(9))
    }

    @Test
    fun pressAfterFullReleaseSendsAgain() {
        val counter = SdlButtonRefCounter()
        assertTrue(counter.press(1))
        assertTrue(counter.release(1))
        assertTrue(counter.press(1))
    }
}
