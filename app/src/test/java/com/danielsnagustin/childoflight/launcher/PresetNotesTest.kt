package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.ui.settings.presetCompatibilityNote
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notas de compatibilidad por preset (Ola 3, Etapa 8). Funciones
 * puras: cada preset conserva sus valores reales (presetSettings) y
 * solo se añade una nota en la UI.
 */
class PresetNotesTest {

    @Test
    fun qualityWarnsAbout2xOnMali() {
        val note = presetCompatibilityNote(GpuProfileId.G99_QUALITY)
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("28 fps"))
    }

    @Test
    fun turnipNoteSaysItDoesNotWorkOnMali() {
        // R2-P2-23: la rama Mali ("no se recomienda aquí") era muerta
        // y se borró; la nota estática sigue diciendo que Turnip no
        // funciona en Mali.
        val note = presetCompatibilityNote(GpuProfileId.ADRENO_QUALITY_TURNIP)
        assertTrue(note.contains("Turnip no funciona en Mali"))
        assertTrue(note.contains("Vulkan + Turnip a 2x"))
    }

    @Test
    fun maliPresetsSayNoTurnip() {
        assertTrue(presetCompatibilityNote(GpuProfileId.MALI_BALANCED).contains("Sin Turnip"))
        assertTrue(presetCompatibilityNote(GpuProfileId.MALI_PERFORMANCE).contains("Sin Turnip"))
    }

    @Test
    fun recommendedMentionsG99And1x() {
        val note = presetCompatibilityNote(GpuProfileId.G99_RECOMMENDED)
        assertTrue(note.contains("G99"))
        assertTrue(note.contains("1x"))
    }

    // P2-16: cada nota dice su diferencia real con G99 Recomendado
    // (calculada campo a campo desde presetSettings; ningún preset
    // tiene cero diferencias, así que ninguno se fusiona).

    @Test
    fun maliPerformanceDiffersOnlyInTextureCache() {
        val note = presetCompatibilityNote(GpuProfileId.MALI_PERFORMANCE)
        assertTrue(note.contains("única diferencia"))
        assertTrue(note.contains("caché de texturas OFF"))
    }

    @Test
    fun maliBalancedDiffersOnlyInFilter() {
        val note = presetCompatibilityNote(GpuProfileId.MALI_BALANCED)
        assertTrue(note.contains("única diferencia"))
        assertTrue(note.contains("FSR"))
    }

    @Test
    fun adrenoPerformanceDiffersOnlyInPageTable() {
        val note = presetCompatibilityNote(GpuProfileId.ADRENO_PERFORMANCE)
        assertTrue(note.contains("única diferencia"))
        assertTrue(note.contains("Page Table"))
    }

    @Test
    fun qualityListsItsThreeDifferences() {
        val note = presetCompatibilityNote(GpuProfileId.G99_QUALITY)
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("FSR"))
        assertTrue(note.contains("HUD de FPS"))
    }

    @Test
    fun turnipQualityListsAllSixDifferences() {
        val note = presetCompatibilityNote(GpuProfileId.ADRENO_QUALITY_TURNIP)
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("FXAA"))
        assertTrue(note.contains("4x"))
        assertTrue(note.contains("VSync ON"))
        assertTrue(note.contains("Page Table"))
        assertTrue(note.contains("Turnip"))
    }
}
