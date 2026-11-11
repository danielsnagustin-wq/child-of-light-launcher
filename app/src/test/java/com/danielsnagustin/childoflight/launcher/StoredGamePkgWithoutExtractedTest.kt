package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-P1-02(b): cuándo se muestra el botón "Borrar .pkg guardado sin
 * instalar" de "Limpiar no usados" (GameStatus.storedGamePkgWithoutExtracted).
 * Lógica pura sobre el estado escaneado, sin Android.
 */
class StoredGamePkgWithoutExtractedTest {

    @Test
    fun pkgSinExtraer_muestraBoton() {
        // El caso P1-02b: update importado como juego, guardado como
        // .pkg de juego, juego sin extraer (JUGAR entra en bucle).
        val status = GameStatus(
            titleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = false,
            pkgTitleId = TitleId.EU,
            pkgSizeBytes = 200_000_000L,
        )
        assertTrue(status.storedGamePkgWithoutExtracted)
    }

    @Test
    fun pkgConJuegoExtraido_noMuestraBoton() {
        // .pkg redundante con el juego ya extraído: lo cubre el botón
        // de arriba (".pkg guardado ya instalado"), no este.
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = true,
            pkgTitleId = TitleId.EU,
            pkgSizeBytes = 1_140_000_000L,
        )
        assertFalse(status.storedGamePkgWithoutExtracted)
    }

    @Test
    fun sinPkg_noMuestraBoton() {
        assertFalse(GameStatus().storedGamePkgWithoutExtracted)
    }

    @Test
    fun juegoExtraidoSinPkg_noMuestraBoton() {
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            gameInstalled = true,
            gameExtracted = true,
        )
        assertFalse(status.storedGamePkgWithoutExtracted)
    }
}
