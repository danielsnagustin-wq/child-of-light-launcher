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
    fun genericQualityNoteSays2xForGpuWithoutOwnProfile() {
        // Ronda 0.2.65 (RG-P2-16): la nota del 2x genérico dice para
        // quién es y que difiere en la resolución 2x.
        // Ronda 0.2.70 (4-P2-1): desde la 0.2.68 también difiere en la
        // carga rápida, que queda apagada (valor upstream); la nota ya
        // no dice "única diferencia".
        val note = presetCompatibilityNote(GpuProfileId.GENERIC_QUALITY)
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("sin perfil propio"))
        assertTrue(note.contains("carga rápida"))
        assertTrue(!note.contains("única diferencia"))
        assertTrue(note.contains("28 fps"))
    }

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
    // (calculada campo a campo desde presetSettings). Ronda 0.2.65:
    // MALI_PERFORMANCE y MALI_BALANCED tienen CERO diferencias con
    // Recomendado (R-P1-5/R-P1-9) y sus notas lo dicen; G99_QUALITY
    // solo difiere en la resolución 2x (R-P1-8/R-P1-17).

    @Test
    fun maliPerformanceIsIdenticalToRecommended() {
        val note = presetCompatibilityNote(GpuProfileId.MALI_PERFORMANCE)
        assertTrue(note.contains("igual que Recomendado"))
        assertTrue(note.contains("Sin Turnip"))
        assertTrue(!note.contains("caché de texturas OFF"))
    }

    @Test
    fun maliBalancedIsIdenticalToRecommended() {
        val note = presetCompatibilityNote(GpuProfileId.MALI_BALANCED)
        assertTrue(note.contains("igual que Recomendado"))
        assertTrue(note.contains("Sin Turnip"))
        assertTrue(!note.contains("FSR"))
    }

    // Ronda 0.2.66 (G11-01): el mapeo por defecto pasó a Page Table
    // en Mali/G99, así que ADRENO_PERFORMANCE ya NO difiere de
    // Recomendado en el mapeo: es idéntico en efecto (la única
    // diferencia es la etiqueta, como Mali Rendimiento).
    @Test
    fun adrenoPerformanceIsIdenticalToRecommended() {
        val note = presetCompatibilityNote(GpuProfileId.ADRENO_PERFORMANCE)
        assertTrue(note.contains("igual que Recomendado"))
        assertTrue(note.contains("Sin Turnip"))
    }

    @Test
    fun qualityDiffersOnlyInResolution() {
        val note = presetCompatibilityNote(GpuProfileId.G99_QUALITY)
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("única diferencia"))
        assertTrue(!note.contains("FSR"))
        assertTrue(!note.contains("HUD de FPS"))
    }

    // Ronda 0.2.64: VSync ON ya no cuenta (en Vulkan es inerte).
    // Ronda 0.2.65 (RG-P2-6): el HUD de FPS activado es la 6ª
    // diferencia del preset Turnip (antes eran 5).
    // Ronda 0.2.66 (G11-01): Page Table ya NO es una diferencia
    // (Recomendado también es Page Table): vuelven a ser 5.
    // Ronda 0.2.70 (4-P2-1): la carga rápida apagada es la 6ª
    // diferencia (solo los presets Mali/G99 la encienden, 0.2.68).
    @Test
    fun turnipQualityListsAllSixDifferences() {
        val note = presetCompatibilityNote(GpuProfileId.ADRENO_QUALITY_TURNIP)
        assertTrue(note.contains("6 ajustes"))
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("FXAA"))
        assertTrue(note.contains("4x"))
        assertTrue(note.contains("Turnip"))
        assertTrue(note.contains("HUD"))
        assertTrue(note.contains("carga rápida"))
        assertTrue(!note.contains("VSync ON"))
    }
}
