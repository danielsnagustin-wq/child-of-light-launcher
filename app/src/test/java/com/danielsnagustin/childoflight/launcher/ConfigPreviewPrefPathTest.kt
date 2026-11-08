package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-14: la vista previa de config (refreshConfigPreview) y el reporte
 * de bug (buildBugReportText) deben mostrar el MISMO pref-path que
 * escribe writeConfig: <vitaRoot>/vita, no la raíz. Ambas llaman a
 * generate() con Vita3kConfigGenerator.vitaFsPathFor(vitaRoot); este
 * test fija que esa ruta es la que emite pref-path, y que generar con
 * la raíz daría el valor viejo (el del bug -3).
 */
class ConfigPreviewPrefPathTest {

    @Test
    fun generateWithVitaFsPathEmitsVitaFsPrefPath() {
        val root = File("/data/data/child-of-light-launcher/files/vita3k")
        val vitaFs = Vita3kConfigGenerator.vitaFsPathFor(root)
        val yaml = Vita3kConfigGenerator.generate(
            presetSettings(GpuProfileId.G99_RECOMMENDED),
            vitaFs.absolutePath,
        )
        assertTrue(yaml.contains("pref-path: \"${vitaFs.absolutePath}\""))
        assertFalse(yaml.contains("pref-path: \"${root.absolutePath}\"\n"))
    }

    @Test
    fun generateWithStorageRootEmitsOldPrefPath() {
        // Contrapunto: si alguien vuelve a pasar la raíz a generate(),
        // el pref-path sale con el valor viejo. Por eso los dos sitios
        // de la UI deben pasar vitaFsPathFor(), no vitaRoot.
        val root = File("/data/data/child-of-light-launcher/files/vita3k")
        val yaml = Vita3kConfigGenerator.generate(
            presetSettings(GpuProfileId.G99_RECOMMENDED),
            root.absolutePath,
        )
        assertTrue(yaml.contains("pref-path: \"${root.absolutePath}\""))
        assertFalse(yaml.contains("pref-path: \"${root.absolutePath}/vita\""))
    }
}
