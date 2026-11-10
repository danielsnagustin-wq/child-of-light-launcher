package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.TitleId
import com.danielsnagustin.childoflight.launcher.ui.storageInvalidationTitleIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5-A1-13: al entrar a Almacenamiento, refreshStorageState() olvida
 * los tamaños instalados cacheados y fuerza un escaneo nuevo para que
 * la pantalla muestre tamaños actualizados sin reiniciar la app.
 * La selección de títulos a invalidar es pura (JVM) y se prueba aquí.
 * El cableado (invalidar + marcar pendiente + refreshStatus() +
 * re-leer Descargas) vive en el ViewModel y necesita Context, así que
 * solo se verifica por lectura de código, como el resto del cableado
 * de tamaños (ver DirSizeInvalidationTest): no hay Robolectric ni
 * mocks en los tests unitarios. El re-escaneo tras invalidar ya está
 * probado en DirSizeCacheTest (invalidateForcesRescan y
 * invalidateAfterNestedChangeForcesRescan), y que el pendiente salta
 * la guarda de recencia en RefreshRecencyTest
 * (noSkipWhenRefreshPendingEvenIfRecent).
 */
class R5StorageRefreshTest {

    @Test
    fun singleTitleReturnsJustIt() {
        val status = GameStatus(titleId = TitleId.EU)
        assertEquals(listOf("PCSB00598"), storageInvalidationTitleIds(status))
    }

    @Test
    fun extractedAndStoredPkgOfDifferentRegionsBothCovered() {
        // M15: juego extraído EU y .pkg guardado US: el estado muestra
        // el extraído, pero el .pkg de la otra región también puede
        // tener tamaños cacheados obsoletos.
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            pkgTitleId = TitleId.US,
        )
        assertEquals(listOf("PCSB00598", "PCSE00480"), storageInvalidationTitleIds(status))
    }

    @Test
    fun duplicatedTitleIdsAppearOnce() {
        val status = GameStatus(
            titleId = TitleId.EU,
            extractedTitleId = TitleId.EU,
            pkgTitleId = TitleId.EU,
        )
        assertEquals(listOf("PCSB00598"), storageInvalidationTitleIds(status))
    }

    @Test
    fun emptyWithoutAnyTitle() {
        assertTrue(storageInvalidationTitleIds(GameStatus()).isEmpty())
    }
}
