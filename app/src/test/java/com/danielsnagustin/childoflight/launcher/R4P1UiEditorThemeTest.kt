package com.danielsnagustin.childoflight.launcher

import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.ui.ColCard
import com.danielsnagustin.childoflight.launcher.ui.ColCardElevatedLight
import com.danielsnagustin.childoflight.launcher.ui.ColMutedTextLight
import com.danielsnagustin.childoflight.launcher.ui.ColTextLight
import com.danielsnagustin.childoflight.launcher.ui.cardColor
import com.danielsnagustin.childoflight.launcher.ui.cardElevatedColor
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.lightBackgroundTint
import com.danielsnagustin.childoflight.launcher.ui.mutedTextColor
import com.danielsnagustin.childoflight.launcher.ui.previewSizeForScreen
import com.danielsnagustin.childoflight.launcher.ui.resolveCardSize
import com.danielsnagustin.childoflight.launcher.ui.resolveCardTransparency
import com.danielsnagustin.childoflight.launcher.ui.touchControlTestLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 4, P1, lote UI/EDITOR/TEMA (0.2.51): previa 16:9 del editor,
 * tema Claro en Ajustes y tamaño/transparencia de tarjetas desde
 * UiPreferences. Todo puro, sin Android: tamaños, tintes, resolución
 * de parámetros y textos. La verificación en el teléfono y en CI
 * sigue pendiente.
 */
class R4P1UiEditorThemeTest {

    // --- R4-P1-4: previa 16:9 real (960x544) ---

    @Test
    fun previewSize_vertical_es16a9() {
        val size = previewSizeForScreen(360.dp, 800.dp)
        assertEquals(360.dp, size.width)
        assertEquals(204.dp, size.height)
    }

    @Test
    fun previewSize_horizontal_cabeEnAltura() {
        val size = previewSizeForScreen(720.dp, 360.dp)
        assertEquals(360.dp, size.height)
        assertTrue(size.width.value > 635f && size.width.value < 636f)
        assertTrue(size.width < 720.dp)
    }

    @Test
    fun previewSize_cuadrada_cabeEnAncho() {
        val size = previewSizeForScreen(400.dp, 400.dp)
        assertEquals(400.dp, size.width)
        assertTrue(size.height.value > 226f && size.height.value < 227f)
    }

    @Test
    fun previewSize_aspectoEs960Entre544() {
        val size = previewSizeForScreen(960.dp, 800.dp)
        val ratio = size.width.value / size.height.value
        assertTrue(ratio > 1.764f && ratio < 1.765f)
    }

    @Test
    fun touchControlTestLabel_muestraEtiquetaYAsignacion() {
        val control = TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.8f, 0.8f, 64, 0.8f)
        assertEquals("A → Cruz (X)", touchControlTestLabel(control))
    }

    // --- R4-P1-14: tema Claro ---

    @Test
    fun lightBackgroundTint_aclaraElFondoOscuro() {
        val tint = lightBackgroundTint("#10131D")
        assertTrue(tint.red > 0.85f)
        assertTrue(tint.green > 0.85f)
        assertTrue(tint.blue > 0.85f)
        assertTrue(tint.blue >= tint.red)
    }

    @Test
    fun lightBackgroundTint_sinHexUsaElAmoldPorDefecto() {
        assertEquals(lightBackgroundTint("#10131D"), lightBackgroundTint(null))
        assertEquals(lightBackgroundTint("#10131D"), lightBackgroundTint("no-es-un-color"))
    }

    @Test
    fun lightBackgroundTint_blancoSeQuedaBlanco() {
        val tint = lightBackgroundTint("#FFFFFF")
        assertEquals(1f, tint.red, 0.001f)
        assertEquals(1f, tint.green, 0.001f)
        assertEquals(1f, tint.blue, 0.001f)
    }

    @Test
    fun cardColors_claroSonClaros() {
        assertEquals(ColCardElevatedLight, cardElevatedColor(false))
        assertEquals(ColTextLight, cardTextColor(false))
        assertEquals(ColMutedTextLight, mutedTextColor(false))
    }

    @Test
    fun cardColors_oscuroSonOscuros() {
        assertEquals(ColCard, cardColor(true))
    }

    // --- R4-P1-15: tamaño/transparencia desde UiPreferences ---

    @Test
    fun resolveCardSize_explicitoGana() {
        val prefs = UiPreferences(cardSize = CardSize.LARGE)
        assertEquals(CardSize.COMPACT, resolveCardSize(CardSize.COMPACT, prefs))
    }

    @Test
    fun resolveCardSize_sinExplicitoLeeLaPreferencia() {
        val prefs = UiPreferences(cardSize = CardSize.LARGE)
        assertEquals(CardSize.LARGE, resolveCardSize(null, prefs))
    }

    @Test
    fun resolveCardSize_sinNadaEsNormal() {
        assertEquals(CardSize.NORMAL, resolveCardSize(null, null))
        assertEquals(CardSize.NORMAL, resolveCardSize(null, UiPreferences()))
    }

    @Test
    fun resolveCardTransparency_explicitoGana() {
        val prefs = UiPreferences(cardTransparency = CardTransparency.TRANSLUCENT)
        assertEquals(CardTransparency.SOFT, resolveCardTransparency(CardTransparency.SOFT, prefs))
    }

    @Test
    fun resolveCardTransparency_sinExplicitoLeeLaPreferencia() {
        val prefs = UiPreferences(cardTransparency = CardTransparency.TRANSLUCENT)
        assertEquals(CardTransparency.TRANSLUCENT, resolveCardTransparency(null, prefs))
    }

    @Test
    fun resolveCardTransparency_sinNadaEsOpaco() {
        assertEquals(CardTransparency.OPAQUE, resolveCardTransparency(null, null))
        assertEquals(CardTransparency.OPAQUE, resolveCardTransparency(null, UiPreferences()))
    }
}
