package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Contrato del puente de sesión sin núcleo (P1-1 / P2-B6 / P2-B12).
 * En los tests unitarios no hay .so, así que available es false y
 * destroySession() / isInitialized() devuelven el valor "sin núcleo"
 * sin llamar a nativo. Si esto falla, el puente está llamando a
 * nativo sin comprobar available. El comportamiento con núcleo real
 * (destruir la sesión y re-leer config.yml en el próximo init) solo
 * se puede probar en el teléfono.
 */
class CoreBridgeSessionTest {

    @Test
    fun destroySessionWithoutNativeReturnsNotImplemented() {
        assertEquals(Vita3kCoreBridge.NOT_IMPLEMENTED, Vita3kCoreBridge.destroySession())
    }

    @Test
    fun isInitializedWithoutNativeReturnsFalse() {
        assertFalse(Vita3kCoreBridge.isInitialized())
    }

    @Test
    fun overlayGamepadCountWithoutNativeReturnsMinusOne() {
        // R4-P0-3: sin .so no hay dato de gamepads SDL (-1, no 0:
        // 0 significaría "SDL no ve ningún mando").
        assertEquals(-1, Vita3kCoreBridge.overlayGamepadCount())
    }

    @Test
    fun overlayIsAttachedAndIsAppPausedWithoutNativeReturnFalse() {
        // R4-P0-4: sin núcleo la verificación de pausa lee false; el
        // clear con verificación no hace nada sin available.
        assertFalse(Vita3kCoreBridge.overlayIsAttached())
        assertFalse(Vita3kCoreBridge.isAppPaused())
    }

    @Test
    fun sessionActiveCodeIsDistinctFromOtherCodes() {
        assertEquals(-4, Vita3kCoreBridge.SESSION_ACTIVE)
        assertEquals(0, Vita3kCoreBridge.OK)
        assertEquals(-1, Vita3kCoreBridge.NOT_IMPLEMENTED)
    }
}
