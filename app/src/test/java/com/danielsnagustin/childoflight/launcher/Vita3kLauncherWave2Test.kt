package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Oleada 2 (área lanzador/activity): P2-A4a y P2-A4b.
 * Todo es lógica pura del companion de Vita3KLauncher (sin Context
 * ni .so): la bandera de fallo de arranque y el texto del fallo de
 * init. El comportamiento con núcleo real (que MainActivity corrija
 * el mensaje al volver, que el -1 salga del stub) solo se puede
 * probar en el teléfono.
 */
class Vita3kLauncherWave2Test {

    @Test
    fun embeddedLaunchFailureFlagIsConsumableOnce() {
        Vita3KLauncher.takeEmbeddedLaunchFailure()
        Vita3KLauncher.noteEmbeddedLaunchFailed("fallo de prueba")
        assertEquals("fallo de prueba", Vita3KLauncher.takeEmbeddedLaunchFailure())
        assertNull(Vita3KLauncher.takeEmbeddedLaunchFailure())
    }

    @Test
    fun initFailureMinusOneReturnsGenericMessage() {
        val message = Vita3KLauncher.initFailureMessage(-1, "System.loadLibrary(Vita3K): prueba")
        assertTrue(message.contains("No se pudo cargar el motor"))
        assertFalse(message.contains("Vita3K"))
        assertFalse(message.contains("System.loadLibrary"))
    }

    @Test
    fun initFailureMinusOneWithoutLoadErrorReturnsGenericMessage() {
        val message = Vita3KLauncher.initFailureMessage(-1, null)
        assertTrue(message.contains("No se pudo cargar el motor"))
        assertFalse(message.contains("Vita3K"))
        assertFalse(message.contains("System.loadLibrary"))
    }

    @Test
    fun initFailureOtherCodeKeepsCode() {
        val message = Vita3KLauncher.initFailureMessage(-2, null)
        assertTrue(message.contains("código -2"))
    }
}
