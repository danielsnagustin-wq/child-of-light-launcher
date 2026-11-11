package com.danielsnagustin.childoflight.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vita3k.emulator.shouldRecordFreeTouch

/**
 * 0.2.63 (D-P2-3): el toque libre no se lo come el overlay.
 *
 * La cadena completa (overlay → superficie SDL → juego) solo se puede
 * probar en el teléfono: dispatchTouchEvent es maquinaria de Views y
 * en los tests JVM las Views de Android son stubs. Lo que SÍ se prueba
 * aquí, en JVM, es la decisión pura de la que depende que el toque
 * libre se cuente una sola vez: un reenvío sintético (2º dedo, zona de
 * Igniculus) NO se vuelve a contar como toque libre en EmuSurface
 * (ya se contó en su origen). La otra decisión pura de la cadena (un
 * DOWN fresco con el overlay visible se ofrece primero al overlay)
 * ya la cubre EmuSurfaceTouchOrderTest.
 *
 * El comportamiento en teléfono lo decide la prueba de 2 minutos del
 * ítem D (título → Igniculus va al punto; diálogo → avanza o no).
 */
class FreeTouchRegressionTest {

    @Test
    fun nonSyntheticTouchIsRecordedAsFreeTouch() {
        assertTrue(shouldRecordFreeTouch(forwardingSyntheticTouch = false))
    }

    @Test
    fun syntheticForwardIsNotRecordedAsFreeTouch() {
        // Un toque sintético en vuelo ya se contó en su origen
        // (forwardPointerDownToSdl / forwardTouch): si EmuSurface lo
        // contara también como libre, cada toque de la zona de
        // Igniculus aparecería dos veces en el reporte.
        assertFalse(shouldRecordFreeTouch(forwardingSyntheticTouch = true))
    }
}
