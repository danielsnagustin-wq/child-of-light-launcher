package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.audioFixChanges
import com.danielsnagustin.childoflight.launcher.ui.settings.audioFixTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vista previa de "Corregir audio" (Ola 2, Etapa 4). Funciones puras:
 * el diálogo muestra lo mismo que el ViewModel aplica.
 */
class AudioFixPreviewTest {

    private val brokenAudio = LauncherSettings(
        audioBackend = AudioBackend.CUBEB,
        ngsEnable = false,
        shaderCache = false,
    )

    @Test
    fun fixTargetIsSdlNgsOnShaderCacheOn() {
        val target = audioFixTarget(brokenAudio)
        assertEquals(AudioBackend.SDL, target.audioBackend)
        assertTrue(target.ngsEnable)
        assertTrue(target.shaderCache)
    }

    @Test
    fun fixTargetKeepsEverythingElse() {
        val current = LauncherSettings(
            audioBackend = AudioBackend.CUBEB,
            resolutionMultiplier = 2f,
            fpsHack = true,
            audioVolume = 42,
        )
        val target = audioFixTarget(current)
        assertEquals(2f, target.resolutionMultiplier, 0.001f)
        assertTrue(target.fpsHack)
        assertEquals(42, target.audioVolume)
    }

    @Test
    fun previewListsThreeChangesWithBeforeAndAfter() {
        val changes = audioFixChanges(brokenAudio)
        assertEquals(3, changes.size)
        val byLabel = changes.associateBy { it.label }
        assertEquals("Cubeb (alternativa)", byLabel.getValue("Backend de audio").before)
        assertEquals("SDL (recomendado)", byLabel.getValue("Backend de audio").after)
        assertEquals("OFF", byLabel.getValue("NGS (audio del sistema Vita)").before)
        assertEquals("ON", byLabel.getValue("NGS (audio del sistema Vita)").after)
        assertEquals("OFF", byLabel.getValue("Caché de shaders").before)
        assertEquals("ON", byLabel.getValue("Caché de shaders").after)
    }

    @Test
    fun previewShowsNoChangeWhenAlreadyFixed() {
        val alreadyFixed = LauncherSettings(
            audioBackend = AudioBackend.SDL,
            ngsEnable = true,
            shaderCache = true,
        )
        val changes = audioFixChanges(alreadyFixed)
        assertTrue(changes.all { it.before == it.after })
    }
}
