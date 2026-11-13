package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.RoutedChildHit
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.pauseWingsLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.OverlayChildLayout
import com.danielsnagustin.childoflight.launcher.core.findRoutableChildIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.2.64 (D-P1-1 y A-2): el segundo dedo sobre un botón se encamina
 * al botón (no a SDL como toque fantasma) y las alas de la pausa
 * viven en una vista aparte más ancha que el botón. Lógica pura,
 * sin MotionEvent ni Views: la vista solo ejecuta lo que decide
 * [findRoutableChildIndex] y lo que calcula [pauseWingsLayout].
 * Puro JVM.
 *
 * Escenario D-P1-1: dedo 0 en el stick (lo consume un hijo), dedo 1
 * sobre el salto (ningún hijo lo consume: el framework solo entrega
 * el POINTER_DOWN a los destinos del gesto). Sin encaminado, el
 * router lo reenviaba a SDL como toque libre: fantasma en el juego
 * sobre el botón + pulsación perdida. Con encaminado, el dedo 1 va
 * al salto (índice del botón) y el juego recibe el salto, no un
 * toque. La zona Igniculus nunca se encamina (su toque es táctil
 * del juego) y un control con un dedo activo tampoco (el framework
 * ya se lo dio).
 */
class RoutedPointerTest {

    private fun button(left: Float, top: Float, size: Float, active: Boolean = false) =
        RoutedChildHit(left, top, left + size, top + size, isZone = false, hasActivePointer = active)

    private fun zone(left: Float, top: Float, size: Float) =
        RoutedChildHit(left, top, left + size, top + size, isZone = true, hasActivePointer = false)

    @Test
    fun secondFingerOnAButtonRoutesToThatButton() {
        // Hijos en orden de montaje: zona detrás, stick, salto encima.
        val children = listOf(
            zone(1500f, 700f, 300f),
            button(100f, 600f, 400f, active = true), // stick mantenido
            button(1900f, 700f, 250f), // salto
        )
        assertEquals(2, findRoutableChildIndex(children, 2000f, 800f))
    }

    @Test
    fun secondFingerInEmptyAreaRoutesToNothing() {
        val children = listOf(
            zone(1500f, 700f, 300f),
            button(100f, 600f, 400f, active = true),
            button(1900f, 700f, 250f),
        )
        assertEquals(-1, findRoutableChildIndex(children, 1000f, 300f))
    }

    @Test
    fun secondFingerOnZoneIsNeverRouted() {
        // El dedo cae sobre la zona Igniculus (y sobre el salto, que
        // pisa su esquina): la zona no se encamina, pero el botón
        // que se solapa SÍ (está encima): gana el botón.
        val overlapping = listOf(
            zone(1500f, 700f, 300f),
            button(1700f, 700f, 250f),
        )
        assertEquals(1, findRoutableChildIndex(overlapping, 1800f, 800f))
        // Solo la zona, sin botón encima: nadie lo quiere (lo sigue
        // el router a SDL como táctil del juego).
        assertEquals(-1, findRoutableChildIndex(listOf(zone(1500f, 700f, 300f)), 1600f, 800f))
    }

    @Test
    fun secondFingerOnAnActiveControlIsNeverRouted() {
        val children = listOf(
            button(100f, 600f, 400f, active = true),
        )
        assertEquals(-1, findRoutableChildIndex(children, 200f, 700f))
    }

    @Test
    fun topmostOverlappingChildWins() {
        // Dos botones solapados: gana el de más arriba (último).
        val children = listOf(
            button(100f, 100f, 200f),
            button(150f, 150f, 200f),
        )
        assertEquals(1, findRoutableChildIndex(children, 200f, 200f))
    }

    @Test
    fun pauseWingsAreWiderThanTheButtonAndCenteredOnIt() {
        // Pausa de 52 dp a 2.80 en 2340x1080: botón 145 px en
        // (1097, 3); las alas son 4.7 x de ancho y 1.07 x de alto,
        // centradas en el botón.
        val pause = OverlayChildLayout(sizePx = 145, leftMargin = 1097, topMargin = 3)
        val wings = pauseWingsLayout(pause)
        assertEquals(681, wings.widthPx)
        assertEquals(155, wings.heightPx)
        assertEquals(1097 + (145 - 681) / 2, wings.leftMargin)
        assertEquals(3 + (145 - 155) / 2, wings.topMargin)
    }
}
