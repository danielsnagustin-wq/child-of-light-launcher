package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameLauncher
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la decisión de lanzamiento de Etapa 2 (función pura).
 * Regla clave: el motor integrado es el camino normal; la app externa
 * solo se usa si no hay núcleo Y el usuario activó la opción avanzada
 * en Ajustes (allowExternal). Sin núcleo y sin permiso, se bloquea con
 * el motivo exacto en vez de saltar por fuera. Sin firmware con núcleo
 * presente, también se bloquea (el motivo dice dónde importarlo).
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
    fun `extraido sin nucleo con permiso externo usa externo`() {
        val decision = EmbeddedGameLauncher.decide(
            extracted(),
            coreAvailable = false,
            firmwareReady = true,
            allowExternal = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `extraido sin nucleo y sin permiso externo bloquea`() {
        val decision = EmbeddedGameLauncher.decide(
            extracted(),
            coreAvailable = false,
            firmwareReady = true,
            allowExternal = false,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
    }

    @Test
    fun `extraido con nucleo pero sin firmware bloquea`() {
        // Sin firmware el núcleo no arranca y ya no se salta a la app
        // externa por detrás: el motivo dice dónde importarlo. La
        // opción avanzada no cambia nada con núcleo presente.
        val sinPermiso = EmbeddedGameLauncher.decide(
            extracted(),
            coreAvailable = true,
            firmwareReady = false,
            allowExternal = false,
        )
        assertTrue(sinPermiso is EmbeddedGameLauncher.Decision.Blocked)
        val conPermiso = EmbeddedGameLauncher.decide(
            extracted(),
            coreAvailable = true,
            firmwareReady = false,
            allowExternal = true,
        )
        assertTrue(conPermiso is EmbeddedGameLauncher.Decision.Blocked)
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
    fun `solo pkg sin nucleo con permiso externo usa externo`() {
        val decision = EmbeddedGameLauncher.decide(
            pkgOnly(),
            coreAvailable = false,
            firmwareReady = true,
            allowExternal = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.UseExternal)
    }

    @Test
    fun `solo pkg sin nucleo y sin permiso externo bloquea`() {
        val decision = EmbeddedGameLauncher.decide(
            pkgOnly(),
            coreAvailable = false,
            firmwareReady = true,
            allowExternal = false,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
    }

    @Test
    fun `solo pkg con nucleo pero sin firmware bloquea`() {
        val decision = EmbeddedGameLauncher.decide(pkgOnly(), coreAvailable = true, firmwareReady = false)
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
    }

    @Test
    fun `version US usa su Title ID`() {
        val decision = EmbeddedGameLauncher.decide(extracted(TitleId.US), coreAvailable = true, firmwareReady = true)
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbedded("PCSE00480"),
            decision,
        )
    }

    @Test
    fun `titleId nulo bloquea aunque el juego este instalado`() {
        val decision = EmbeddedGameLauncher.decide(
            GameStatus(titleId = null, gameInstalled = true, gameExtracted = true),
            coreAvailable = true,
            firmwareReady = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
    }

    @Test
    fun `instalado pero ni extraido ni pkg bloquea`() {
        val decision = EmbeddedGameLauncher.decide(
            GameStatus(titleId = TitleId.EU, gameInstalled = true, gameExtracted = false, pkgTitleId = null),
            coreAvailable = true,
            firmwareReady = true,
        )
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
    }

    @Test
    fun `eboot roto con pkg guardado reinstala el pkg`() {
        // Rebote de JUGAR (2026-11-13): el eboot no es un SELF válido
        // y hay .pkg guardado: JUGAR reinstala en vez de rebotar (el
        // instalador verifica el eboot que deja y falla con el motivo
        // si sigue mal).
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = true,
            pkgTitleId = TitleId.EU,
            pkgContentId = "EP0001-PCSB00598_00-CHILD0FLIGHT0001",
            pkgSizeBytes = 114171057L,
            pkgLicensePresent = true,
            ebootBroken = true,
        )
        val decision = EmbeddedGameLauncher.decide(status, coreAvailable = true, firmwareReady = true)
        assertEquals(
            EmbeddedGameLauncher.Decision.UseEmbeddedPkg("PCSB00598"),
            decision,
        )
    }

    @Test
    fun `eboot roto sin pkg bloquea con reimportar`() {
        // Rebote de JUGAR (2026-11-13): el eboot no es un SELF válido
        // y no hay .pkg que reinstalar: JUGAR se bloquea con la
        // explicación (reimportar el juego completo), no rebota en
        // silencio ni ofrece un Reintentar que repite lo mismo.
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = true,
            ebootBroken = true,
        )
        val decision = EmbeddedGameLauncher.decide(status, coreAvailable = true, firmwareReady = true)
        assertTrue(decision is EmbeddedGameLauncher.Decision.Blocked)
        val reason = (decision as EmbeddedGameLauncher.Decision.Blocked).reason
        assertTrue(reason.contains("Vuelve a importar el juego completo en Importar"))
    }
}
