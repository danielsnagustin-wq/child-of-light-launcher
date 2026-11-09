package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.PkgHeader
import com.danielsnagustin.childoflight.launcher.data.PkgHeaderParser
import com.danielsnagustin.childoflight.launcher.model.PkgKind
import com.danielsnagustin.childoflight.launcher.model.TitleId
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-4: DLC y update ya se distinguen al importar un .pkg por el
 * content_type de su tabla INFO (0x15 juego, 0x16 DLC, 0x1F tema).
 * Pura: el companion GameLibraryRepository.pkgKindMismatch no necesita
 * Context. El límite documentado: juego y update comparten 0x15 (el
 * núcleo los separa por el SFO al instalar), así que un .pkg de juego
 * importado como update se acepta.
 */
class PkgKindMismatchTest {

    private fun header(contentId: String, contentType: Int?): PkgHeader =
        PkgHeader(contentId, TitleId.EU, "EU", 2, 0L, 0L, 0L, contentType)

    private val gameApp = header(
        "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
        PkgHeaderParser.CONTENT_TYPE_APP,
    )
    private val dlc = header(
        "EP0001-PCSB00598_00-CHILDOFLIGHTDLC1",
        PkgHeaderParser.CONTENT_TYPE_DLC,
    )
    private val theme = header(
        "EP0001-PCSB00598_00-CHILDOFLIGHT0000",
        PkgHeaderParser.CONTENT_TYPE_THEME,
    )
    private val unknownType = header("EP0001-PCSB00598_00-CHILDOFLIGHT0000", 0x99)
    private val noInfoGame = header("EP0001-PCSB00598_00-CHILDOFLIGHT0000", null)
    private val noInfoDlc = header("EP0001-PCSB00598_00-CHILDOFLIGHTDLC1", null)
    private val appOtherName = header(
        "EP0001-PCSB00598_00-CHILDOFLIGHTDLC1",
        PkgHeaderParser.CONTENT_TYPE_APP,
    )

    @Test
    fun dlcPkgAsGameIsRejected() {
        val msg = GameLibraryRepository.pkgKindMismatch(dlc, PkgKind.GAME)
        assertTrue(msg!!.contains("DLC"))
    }

    @Test
    fun dlcPkgAsUpdateIsRejected() {
        val msg = GameLibraryRepository.pkgKindMismatch(dlc, PkgKind.UPDATE)
        assertTrue(msg!!.contains("DLC"))
    }

    @Test
    fun dlcPkgAsDlcPasses() {
        assertNull(GameLibraryRepository.pkgKindMismatch(dlc, PkgKind.DLC))
    }

    @Test
    fun appPkgAsDlcIsRejected() {
        val msg = GameLibraryRepository.pkgKindMismatch(gameApp, PkgKind.DLC)
        assertTrue(msg!!.contains("no es un DLC"))
    }

    @Test
    fun appPkgAsGamePasses() {
        assertNull(GameLibraryRepository.pkgKindMismatch(gameApp, PkgKind.GAME))
    }

    @Test
    fun appPkgAsUpdateIsAccepted() {
        // Límite documentado: juego y update comparten 0x15; el núcleo
        // decide por el SFO al instalar.
        assertNull(GameLibraryRepository.pkgKindMismatch(gameApp, PkgKind.UPDATE))
    }

    @Test
    fun appPkgWithOtherNameAsGameIsRejected() {
        val msg = GameLibraryRepository.pkgKindMismatch(appOtherName, PkgKind.GAME)
        assertTrue(msg!!.contains("no es el juego"))
    }

    @Test
    fun themePkgIsRejectedEverywhere() {
        for (kind in PkgKind.entries) {
            val msg = GameLibraryRepository.pkgKindMismatch(theme, kind)
            assertTrue(msg!!.contains("tema"))
        }
    }

    @Test
    fun unknownContentTypeIsRejected() {
        val msg = GameLibraryRepository.pkgKindMismatch(unknownType, PkgKind.GAME)
        assertTrue(msg!!.contains("no compatible"))
    }

    @Test
    fun noInfoFallsBackToNameRule() {
        val asDlc = GameLibraryRepository.pkgKindMismatch(noInfoGame, PkgKind.DLC)
        assertTrue(asDlc!!.contains("es del juego"))
        val asGame = GameLibraryRepository.pkgKindMismatch(noInfoDlc, PkgKind.GAME)
        assertTrue(asGame!!.contains("no es el juego"))
        val updateAsGame = GameLibraryRepository.pkgKindMismatch(noInfoGame, PkgKind.UPDATE)
        assertTrue(updateAsGame!!.contains("es del juego"))
    }
}
