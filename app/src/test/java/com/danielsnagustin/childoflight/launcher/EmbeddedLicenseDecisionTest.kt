package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameLauncher
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la decisión de lanzamiento con LICENCIA (P0-1, P2-A6).
 * Regla: un .pkg sin su work.bin no se instala con el núcleo
 * embebido: el juego quedaría instalado pero no arrancaría, sin
 * avisar a nadie. decide() bloquea con "Falta la licencia
 * (work.bin)…" para que la UI ofrezca el selector de work.bin.
 * Solo el camino .pkg se bloquea: un juego extraído ya tiene su
 * licencia registrada en el núcleo, y la vía externa gestiona su
 * propia licencia al instalar en la app externa.
 */
class EmbeddedLicenseDecisionTest {

    private fun pkgOnly(licensePresent: Boolean, titleId: TitleId = TitleId.EU) = GameStatus(
        titleId = titleId,
        gameInstalled = true,
        gameExtracted = false,
        pkgTitleId = titleId,
        pkgContentId = "EP0001-PCSB00598_00-CHILD0FLIGHT0001",
        pkgSizeBytes = 114171057L,
        pkgLicensePresent = licensePresent,
    )

    @Test
    fun `solo pkg sin licencia con nucleo y firmware bloquea con falta de licencia`() {
        val decision = EmbeddedGameLauncher.decide(
            pkgOnly(licensePresent = false),
            coreAvailable = true,
            firmwareReady = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
        val reason = (decision as EmbeddedGameLauncher.Decision.Blocked).reason
        assertTrue(reason.contains("Falta la licencia (work.bin)"))
        assertEquals(EmbeddedGameLauncher.LICENSE_BLOCKED_EMBEDDED, reason)
    }

    @Test
    fun `solo pkg con licencia instala y arranca embebido`() {
        val decision = EmbeddedGameLauncher.decide(
            pkgOnly(licensePresent = true),
            coreAvailable = true,
            firmwareReady = true,
        )
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbeddedPkg("PCSB00598"),
            decision,
        )
    }

    @Test
    fun `extraido sin licencia usa embebido`() {
        // El juego extraído ya tiene su licencia en el núcleo: la
        // falta del work.bin guardado no bloquea su arranque.
        val decision = EmbeddedGameLauncher.decide(
            GameStatus(
                titleId = TitleId.EU,
                extractedTitleId = TitleId.EU,
                gameInstalled = true,
                gameExtracted = true,
                pkgLicensePresent = false,
            ),
            coreAvailable = true,
            firmwareReady = true,
        )
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbedded("PCSB00598"),
            decision,
        )
    }

    @Test
    fun `solo pkg sin licencia sin nucleo con permiso externo usa externo`() {
        // La vía externa instala el .pkg en la app externa, que pide
        // su propio work.bin allí: decide() no bloquea por la licencia
        // del launcher en este camino.
        val decision = EmbeddedGameLauncher.decide(
            pkgOnly(licensePresent = false),
            coreAvailable = false,
            firmwareReady = true,
            allowExternal = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `instalado pero ni extraido ni pkg describe el estado real`() {
        // P2-A6: estado inconsistente (solo construible a mano, el
        // invariante de scanStatus lo impide en disco). El mensaje ya
        // no dice "no está instalado": describe el estado real.
        val decision = EmbeddedGameLauncher.decide(
            GameStatus(titleId = TitleId.EU, gameInstalled = true, gameExtracted = false, pkgTitleId = null),
            coreAvailable = true,
            firmwareReady = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
        val reason = (decision as EmbeddedGameLauncher.Decision.Blocked).reason
        assertTrue(reason.contains("no hay copia extraída ni .pkg guardado"))
    }
}
