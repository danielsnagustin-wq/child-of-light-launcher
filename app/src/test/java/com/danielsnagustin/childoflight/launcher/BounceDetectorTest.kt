package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.isBounceCandidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rebote de JUGAR (2026-11-13): decisión pura del candidato a
 * rebote (la usa EmbeddedGameActivity.onPause; onDestroy lo
 * confirma). Es rebote solo si se cumplen TODAS: el juego confirmó
 * on_launch, la sesión ya no está en curso, nunca se presentó ningún
 * frame, la salida no fue pedida por el usuario y la Activity no se
 * está cerrando ya. Cada condición que falta descarta el rebote:
 * ir a segundo plano durante la carga, salir por el menú de pausa o
 * cerrar con el juego en curso no son rebotes.
 */
class BounceDetectorTest {

    private fun candidate(
        gameRunningSinceMillis: Long = 1_000L,
        appRunning: Boolean = false,
        presentedFrames: Long = 0L,
        userExitRequested: Boolean = false,
        finishing: Boolean = false,
    ) = isBounceCandidate(
        gameRunningSinceMillis = gameRunningSinceMillis,
        appRunning = appRunning,
        presentedFrames = presentedFrames,
        userExitRequested = userExitRequested,
        finishing = finishing,
    )

    @Test
    fun `rebote con on_launch sin sesion y sin frames`() {
        assertTrue(candidate())
    }

    @Test
    fun `sin on_launch no es rebote`() {
        assertFalse(candidate(gameRunningSinceMillis = 0L))
    }

    @Test
    fun `sesion en curso no es rebote`() {
        assertFalse(candidate(appRunning = true))
    }

    @Test
    fun `frames presentados no es rebote`() {
        assertFalse(candidate(presentedFrames = 1L))
        assertFalse(candidate(presentedFrames = 120L))
    }

    @Test
    fun `salida voluntaria por menu de pausa no es rebote`() {
        assertFalse(candidate(userExitRequested = true))
    }

    @Test
    fun `cerrandose ya con finish no es rebote`() {
        assertFalse(candidate(finishing = true))
    }

    @Test
    fun `contador de presents desconocido cuenta como sin frames`() {
        // -1 = el puente no sabe cuántos frames se presentaron (sin
        // núcleo): no es un frame presentado, así que no descarta.
        assertTrue(candidate(presentedFrames = -1L))
    }
}
