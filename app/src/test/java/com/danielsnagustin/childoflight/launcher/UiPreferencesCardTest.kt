package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.IconSize
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nuevos campos de personalización (Ola 3, Etapa 6): tamaño y
 * transparencia de tarjetas y tamaño de iconos. Los defaults son el
 * comportamiento actual (tarjeta normal 16 dp / radio 20 dp, opaca,
 * icono 24 dp).
 */
class UiPreferencesCardTest {

    @Test
    fun defaultsAreCurrentBehavior() {
        val prefs = UiPreferences()
        assertEquals(CardSize.NORMAL, prefs.cardSize)
        assertEquals(16, prefs.cardSize.paddingDp)
        assertEquals(20, prefs.cardSize.cornerDp)
        assertEquals(CardTransparency.OPAQUE, prefs.cardTransparency)
        assertEquals(1f, prefs.cardTransparency.alpha, 0.001f)
        assertEquals(IconSize.MEDIUM, prefs.iconSize)
        assertEquals(24, prefs.iconSize.sizeDp)
    }

    @Test
    fun cardSizeScalesPaddingAndCorner() {
        assertEquals(10, CardSize.COMPACT.paddingDp)
        assertEquals(14, CardSize.COMPACT.cornerDp)
        assertEquals(22, CardSize.LARGE.paddingDp)
        assertEquals(26, CardSize.LARGE.cornerDp)
    }

    @Test
    fun transparencyAlphasAreReal() {
        assertEquals(0.92f, CardTransparency.SOFT.alpha, 0.001f)
        assertEquals(0.8f, CardTransparency.TRANSLUCENT.alpha, 0.001f)
    }

    @Test
    fun iconSizesAreReal() {
        assertEquals(18, IconSize.SMALL.sizeDp)
        assertEquals(32, IconSize.LARGE.sizeDp)
    }
}
