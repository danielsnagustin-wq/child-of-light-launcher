package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.MultiTouchRouter
import com.danielsnagustin.childoflight.launcher.core.MultiTouchRouter.PointerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-P1-16: el segundo dedo en zona vacía llega al juego aunque otro
 * dedo mantenga el stick o un botón. La decisión vive en
 * MultiTouchRouter (lógica pura, sin MotionEvent ni Views): la vista
 * solo ejecuta lo que el router decide. Puro JVM.
 *
 * Escenario que cubre: dedo 0 en el stick (lo consume un hijo),
 * dedo 1 en zona vacía (ningún hijo lo consume) -> FORWARD_DOWN y
 * se sigue hasta su UP; dedo 2 sobre un botón -> lo consume el hijo
 * (DROP, nunca se reenvía); dedo sobre un control ya activo (el
 * stick mantenido) -> DROP, sin dedo fantasma en el juego.
 */
class MultiTouchRouterTest {

    private fun router() = MultiTouchRouter()

    @Test
    fun secondFingerInEmptyAreaIsForwarded() {
        val router = router()
        val decision = router.onPointerDown(
            pointerId = 1,
            downTime = 1000L,
            x = 500f,
            y = 800f,
            handledByChild = false,
            insideActiveControl = false,
        )
        assertEquals(PointerAction.FORWARD_DOWN, decision)
        assertEquals(setOf(1), router.forwardedPointerIds)
    }

    @Test
    fun fingerConsumedByAChildIsNeverForwarded() {
        val router = router()
        val decision = router.onPointerDown(
            pointerId = 1,
            downTime = 1000L,
            x = 100f,
            y = 100f,
            handledByChild = true,
            insideActiveControl = false,
        )
        assertEquals(PointerAction.DROP, decision)
        assertTrue(router.forwardedPointerIds.isEmpty())
        assertEquals(PointerAction.DROP, router.onMove(1, 100f, 100f))
        assertEquals(PointerAction.DROP, router.onPointerUp(1))
    }

    @Test
    fun fingerInsideAnActiveControlIsNotForwarded() {
        val router = router()
        // Segundo dedo sobre el stick que ya mantiene el primer dedo:
        // el framework ya se lo dio al stick; reenviarlo crearía un
        // dedo fantasma en el juego justo sobre el stick.
        val decision = router.onPointerDown(
            pointerId = 1,
            downTime = 1000L,
            x = 200f,
            y = 700f,
            handledByChild = false,
            insideActiveControl = true,
        )
        assertEquals(PointerAction.DROP, decision)
        assertTrue(router.forwardedPointerIds.isEmpty())
    }

    @Test
    fun forwardedFingerMovesAndLiftsAsItsOwnTouch() {
        val router = router()
        router.onPointerDown(1, 1000L, 500f, 800f, false, false)
        assertEquals(PointerAction.FORWARD_MOVE, router.onMove(1, 520f, 780f))
        assertEquals(1000L, router.downTimeOf(1))
        assertEquals(PointerAction.FORWARD_UP, router.onPointerUp(1))
        assertTrue(router.forwardedPointerIds.isEmpty())
        // Un segundo UP del mismo dedo ya no hace nada.
        assertEquals(PointerAction.DROP, router.onPointerUp(1))
    }

    @Test
    fun moveOfAnUnknownPointerIsDropped() {
        val router = router()
        router.onPointerDown(1, 1000L, 500f, 800f, false, false)
        // El dedo 0 es del stick: su MOVE no es táctil del juego.
        assertEquals(PointerAction.DROP, router.onMove(0, 200f, 700f))
        assertEquals(PointerAction.FORWARD_MOVE, router.onMove(1, 510f, 790f))
    }

    @Test
    fun twoForwardedFingersAreTrackedIndependently() {
        val router = router()
        router.onPointerDown(1, 1000L, 500f, 800f, false, false)
        router.onPointerDown(2, 1100L, 900f, 300f, false, false)
        assertEquals(setOf(1, 2), router.forwardedPointerIds)
        assertEquals(PointerAction.FORWARD_MOVE, router.onMove(2, 910f, 310f))
        assertEquals(PointerAction.FORWARD_UP, router.onPointerUp(1))
        assertEquals(setOf(2), router.forwardedPointerIds)
        assertEquals(1100L, router.downTimeOf(2))
    }

    @Test
    fun endAllReturnsRemainingTouchesAndClears() {
        val router = router()
        router.onPointerDown(1, 1000L, 500f, 800f, false, false)
        router.onPointerDown(2, 1100L, 900f, 300f, false, false)
        router.onMove(1, 530f, 770f)
        val ended = router.endAll()
        assertEquals(2, ended.size)
        val first = ended.first { it.pointerId == 1 }
        assertEquals(1000L, first.downTime)
        assertEquals(530f, first.x, 0.001f)
        assertEquals(770f, first.y, 0.001f)
        assertTrue(router.forwardedPointerIds.isEmpty())
        assertTrue(router.endAll().isEmpty())
    }
}
