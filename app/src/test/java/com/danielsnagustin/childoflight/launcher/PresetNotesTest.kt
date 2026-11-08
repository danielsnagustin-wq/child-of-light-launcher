package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.ui.settings.presetCompatibilityNote
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notas de compatibilidad por preset (Ola 3, Etapa 8). Funciones
 * puras: cada preset conserva sus valores reales (presetSettings) y
 * solo se añade una nota en la UI.
 */
class PresetNotesTest {

    private val maliGpu = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", socModel = "MT6789")
    private val adrenoGpu = GpuInfo(vendor = GpuVendor.ADRENO, renderer = "Adreno 750", socModel = "SM8650")

    @Test
    fun qualityWarnsAbout2xOnMali() {
        val note = presetCompatibilityNote(GpuProfileId.G99_QUALITY, maliGpu)
        assertTrue(note.contains("2x"))
        assertTrue(note.contains("28 fps"))
    }

    @Test
    fun turnipNoteWarnsOnMaliButNotOnAdreno() {
        val onMali = presetCompatibilityNote(GpuProfileId.ADRENO_QUALITY_TURNIP, maliGpu)
        assertTrue(onMali.contains("Turnip no funciona en Mali"))
        assertTrue(onMali.contains("no se recomienda"))
        val onAdreno = presetCompatibilityNote(GpuProfileId.ADRENO_QUALITY_TURNIP, adrenoGpu)
        assertTrue(onAdreno.contains("Turnip"))
        assertTrue(!onAdreno.contains("no se recomienda"))
    }

    @Test
    fun maliPresetsSayNoTurnip() {
        assertTrue(presetCompatibilityNote(GpuProfileId.MALI_BALANCED, maliGpu).contains("Sin Turnip"))
        assertTrue(presetCompatibilityNote(GpuProfileId.MALI_PERFORMANCE, maliGpu).contains("Sin Turnip"))
    }

    @Test
    fun recommendedMentionsG99And1x() {
        val note = presetCompatibilityNote(GpuProfileId.G99_RECOMMENDED, maliGpu)
        assertTrue(note.contains("G99"))
        assertTrue(note.contains("1x"))
    }
}
