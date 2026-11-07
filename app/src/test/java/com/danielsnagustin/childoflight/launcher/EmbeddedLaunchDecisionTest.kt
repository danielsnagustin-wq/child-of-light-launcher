package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameLauncher
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la decisión de lanzamiento de Etapa 2 (función pura).
 * La regla clave: nunca se bloquea pudiendo jugar por fuera.
 */
class EmbeddedLaunchDecisionTest {

    private fun extracted(titleId: TitleId = TitleId.EU) = GameStatus(
        titleId = titleId,
        extractedTitleId = titleId,
        gameInstalled = true,
        gameExtracted = true,
    )

    private fun pkgOnly(titleId: TitleId = TitleId.EU) = GameStatus(
        titleId = titleId,
        gameInstalled = true,
        gameExtracted = false,
        pkgTitleId = titleId,
        pkgContentId = "EP0001-PCSB00598_00-CHILD0FLIGHT0001",
        pkgSizeBytes = 114171057L,
        pkgLicensePresent = true,
    )

    @Test
    fun `sin juego instalado bloquea`() {
        val decision = EmbeddedGameLauncher.decide(GameStatus(), coreAvailable = true, firmwareReady = true)
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
    }

    @Test
    fun `extraido con nucleo y firmware usa embebido`() {
        val decision = EmbeddedGameLauncher.decide(extracted(), coreAvailable = true, firmwareReady = true)
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbedded("PCSB00598"),
            decision,
        )
    }

    @Test
    fun `extraido sin nucleo usa externo`() {
        val decision = EmbeddedGameLauncher.decide(extracted(), coreAvailable = false, firmwareReady = true)
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `extraido con nucleo pero sin firmware usa externo no bloquea`() {
        // Sin firmware el núcleo no arranca, pero Vita3K externo
        // gestiona su propio firmware: nunca bloquear pudiendo jugar.
        val decision = EmbeddedGameLauncher.decide(extracted(), coreAvailable = true, firmwareReady = false)
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `solo pkg con nucleo y firmware instala y arranca embebido`() {
        val decision = EmbeddedGameLauncher.decide(pkgOnly(), coreAvailable = true, firmwareReady = true)
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbeddedPkg("PCSB00598"),
            decision,
        )
    }

    @Test
    fun `solo pkg sin nucleo usa externo`() {
        val decision = EmbeddedGameLauncher.decide(pkgOnly(), coreAvailable = false, firmwareReady = true)
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `solo pkg con nucleo pero sin firmware usa externo`() {
        val decision = EmbeddedGameLauncher.decide(pkgOnly(), coreAvailable = true, firmwareReady = false)
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `version US usa su Title ID`() {
        val decision = EmbeddedGameLauncher.decide(extracted(TitleId.US), coreAvailable = true, firmwareReady = true)
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbedded("PCSE00480"),
            decision,
        )
    }
}
