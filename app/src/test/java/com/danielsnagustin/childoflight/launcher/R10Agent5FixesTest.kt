package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.BundledTurnipDriver
import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.data.mapNativeTrophies
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import org.vita3k.emulator.data.NativeTrophy
import org.vita3k.emulator.data.NativeTrophyCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests de los arreglos del agente 5, ronda 0.2.65 (R10):
// G-P2-2 (SoC Xclipse por código s5e*), G-P2-3 (Adreno 732/702),
// RG-P2-9 (variante 8xx registrada sin binario) y W-P1-2 (mapeo de
// trofeos por nombre normalizado en español y por trophyId).
// Kotlin puro (sin Android ni núcleo): corre en la JVM.

class R10Agent5FixesTest {

    // --- G-P2-2: códigos de chip Samsung s5e* -> Xclipse ---

    @Test
    fun exynosChipCodesClassifyAsXclipse() {
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Vulkan Device", "Samsung", "s5e9945"))
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Vulkan Device", "Samsung", "s5e9935"))
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Vulkan Device", "Samsung", "s5e8825"))
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Vulkan Device", "Samsung", "s5e9965"))
        assertEquals(GpuVendor.XCLIPSE, GpuDetector.classify("Vulkan Device", "Samsung", "Exynos 2400"))
    }

    @Test
    fun maliRendererStillWinsOverExynosSoc() {
        // Exynos 2100 con Mali: el renderer manda, sigue siendo Mali.
        assertEquals(GpuVendor.MALI, GpuDetector.classify("Mali-G78", "ARM", "s5e9840"))
    }

    // --- G-P2-3: Adreno 732/702 usan el build estándar Gmem ---

    @Test
    fun adreno732And702UseStandardGmem() {
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.STANDARD_GMEM,
            BundledTurnipDriver.selectVariant("Adreno (TM) 732"),
        )
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.STANDARD_GMEM,
            BundledTurnipDriver.selectVariant("Adreno (TM) 702"),
        )
    }

    // --- RG-P2-9: variante 8xx registrada, sin binario, sin auto-instalar ---

    @Test
    fun adreno8xxKnownVariantButNotAutoInstallable() {
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.A8XX_SEPARATE_BUILD_NO_BINARY,
            BundledTurnipDriver.selectKnownVariant("Adreno (TM) 830"),
        )
        assertEquals(
            BundledTurnipDriver.BundledTurnipVariant.A8XX_SEPARATE_BUILD_NO_BINARY,
            BundledTurnipDriver.selectKnownVariant("Adreno (TM) 840"),
        )
        // selectVariant sigue devolviendo null en 8xx: el llamador
        // auto-instala ASSET_PATH (build 6xx/7xx) si no es null, y ese
        // zip NO es el build de 8xx.
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM) 830"))
        assertNull(BundledTurnipDriver.selectVariant("Adreno (TM) 840"))
    }

    @Test
    fun knownVariantStillNullFor710AndA32() {
        assertNull(BundledTurnipDriver.selectKnownVariant("Adreno (TM) 710"))
        assertNull(BundledTurnipDriver.selectKnownVariant("Adreno A32"))
        assertNull(BundledTurnipDriver.selectKnownVariant("Mali-G57 MC2"))
    }

    // --- W-P1-2: trofeos por nombre en español y por trophyId ---

    private fun nativeTrophy(
        name: String,
        earned: Boolean = true,
        trophyId: Int = 0,
        timestampSec: Long = 1_700_000_000L,
    ): NativeTrophy {
        return NativeTrophy().apply {
            this.name = name
            this.earned = earned
            this.trophyId = trophyId
            this.timestampSec = timestampSec
        }
    }

    private fun collection(totalCount: Int, vararg trophies: NativeTrophy): NativeTrophyCollection {
        return NativeTrophyCollection().apply {
            this.totalCount = totalCount
            this.trophies = arrayOf(*trophies)
        }
    }

    @Test
    fun spanishNameMapsToLocalNumber() {
        val result = mapNativeTrophies(
            arrayOf(collection(20, nativeTrophy("Atrapaestrellas"))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(listOf(1), result.unlocked.map { it.number })
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun spanishNameNormalizedMatchesDespiteCaseAndAccents() {
        // "DECIMO CUMPLEANOS" (sin tilde, en mayúsculas) casa con el
        // nº 12 "Décimo cumpleaños" por la comparación normalizada.
        val result = mapNativeTrophies(
            arrayOf(collection(20, nativeTrophy("DECIMO CUMPLEANOS"))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(listOf(12), result.unlocked.map { it.number })
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun englishNameNormalizedMatchesDespiteCase() {
        val result = mapNativeTrophies(
            arrayOf(collection(20, nativeTrophy("capilli catch"))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(listOf(6), result.unlocked.map { it.number })
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun unknownNameFallsBackToTrophyIdWhenCollectionIsComplete() {
        // Nombre que no casa por ninguna vía, pero la colección trae
        // los 20: el id 5 (0..19) es el nº 6.
        val result = mapNativeTrophies(
            arrayOf(collection(20, nativeTrophy("Nombre desconocido", trophyId = 5))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(listOf(6), result.unlocked.map { it.number })
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun trophyIdFallbackNeedsCompleteCollection() {
        // Misma entrada pero con totalCount 0 (colección incompleta o
        // construida a mano): el id no se usa y el nombre se salta.
        val result = mapNativeTrophies(
            arrayOf(collection(0, nativeTrophy("Nombre desconocido", trophyId = 5))),
            nowMillis = 1_800_000_000_000L,
        )
        assertTrue(result.unlocked.isEmpty())
        assertEquals(listOf("Nombre desconocido"), result.skippedUnknownNames)
    }

    @Test
    fun allTwentySpanishNamesMap() {
        val names = listOf(
            "Atrapaestrellas", "En la luna", "Amanecer", "Coronación", "Comedia",
            "Captura de un Capilli", "Hermana querida", "Unión de un Bolmus",
            "La familia Kategida", "Promesa de los piscis", "Tragedia",
            "Décimo cumpleaños", "Perfeccionista", "Experto oculista",
            "Robando protagonismo", "Aguafiestas", "Furia alada", "Explorador",
            "Soneto perfecto", "Raíces",
        )
        val result = mapNativeTrophies(
            arrayOf(collection(20, *names.map { nativeTrophy(it) }.toTypedArray())),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals((1..20).toList(), result.unlocked.map { it.number })
        assertTrue(result.skippedUnknownNames.isEmpty())
    }
}
