package com.danielsnagustin.childoflight.launcher

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vita3k.emulator.shouldOfferDownToOverlayFirst

/**
 * R4-P1-17: EmuSurface prueba el overlay primero y solo si no consume
 * cae a SDL. La decisión es pura (sin Views): solo un ACTION_DOWN
 * fresco se ofrece al overlay, solo si está visible y solo si no hay
 * un toque sintético en vuelo hacia SDL. Puro JVM: las constantes de
 * MotionEvent son constantes en tiempo de compilación, no se construye
 * ningún MotionEvent.
 */
class EmuSurfaceTouchOrderTest {

    @Test
    fun freshDownWithVisibleOverlayIsOfferedFirst() {
        assertTrue(
            shouldOfferDownToOverlayFirst(
                MotionEvent.ACTION_DOWN,
                overlayVisible = true,
                forwardingSyntheticTouch = false,
            ),
        )
    }

    @Test
    fun downWithHiddenOverlayGoesStraightToSdl() {
        assertFalse(
            shouldOfferDownToOverlayFirst(
                MotionEvent.ACTION_DOWN,
                overlayVisible = false,
                forwardingSyntheticTouch = false,
            ),
        )
    }

    @Test
    fun downDuringASyntheticForwardIsNotReoffered() {
        // Un toque sintético (modo Joystick de Igniculus) ya va de
        // camino a SDL: el overlay devolvió false por el flag y
        // re-ofrecerlo aquí solo podría comérselo un botón.
        assertFalse(
            shouldOfferDownToOverlayFirst(
                MotionEvent.ACTION_DOWN,
                overlayVisible = true,
                forwardingSyntheticTouch = true,
            ),
        )
    }

    @Test
    fun pointerDownAndMoveAreNeverReoffered() {
        // Un POINTER_DOWN re-ofrecido dejaría un botón atascado: el
        // framework sigue encaminando el gesto a la superficie y el
        // control no recibiría jamás sus MOVE/UP. Ese caso lo cubre
        // el overlay (R4-P1-16), no EmuSurface.
        assertFalse(
            shouldOfferDownToOverlayFirst(
                MotionEvent.ACTION_POINTER_DOWN,
                overlayVisible = true,
                forwardingSyntheticTouch = false,
            ),
        )
        assertFalse(
            shouldOfferDownToOverlayFirst(
                MotionEvent.ACTION_MOVE,
                overlayVisible = true,
                forwardingSyntheticTouch = false,
            ),
        )
        assertFalse(
            shouldOfferDownToOverlayFirst(
                MotionEvent.ACTION_UP,
                overlayVisible = true,
                forwardingSyntheticTouch = false,
            ),
        )
    }
}
