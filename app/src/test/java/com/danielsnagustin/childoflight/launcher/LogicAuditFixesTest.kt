package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.data.SessionRepository
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.model.GameSession
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de los fixes de la auditoría de lógica (H5, M4, M24, M22, M28).
 * Todo puro, sin Android: parseLine, classify, clamps y decodeControl.
 */
class LogicAuditFixesTest {

    private fun validSession(): GameSession = GameSession(
        id = "s1",
        name = "Partida 1",
        titleId = "PCSE00480",
        createdAtMillis = 1000L,
        lastPlayedAtMillis = 2000L,
        playTimeMillis = 3_600_000L,
        chapter = "Capítulo 2",
        savedataFiles = 2,
        savedataBytes = 1024L,
        unlockedTrophies = 5,
    )

    // --- parseLine (H5/M4) ---

    @Test
    fun parseLine_aceptaLineaValida() {
        val parsed = SessionRepository.parseLine(SessionRepository.formatLine(validSession()))
        assertNotNull(parsed)
        assertEquals("s1", parsed!!.id)
        assertEquals("PCSE00480", parsed.titleId)
    }

    @Test
    fun parseLine_rechazaTitleIdTraversal() {
        val line = SessionRepository.formatLine(validSession().copy(titleId = "../../x"))
        assertNull(SessionRepository.parseLine(line))
    }

    @Test
    fun parseLine_rechazaTitleIdDesconocido() {
        val line = SessionRepository.formatLine(validSession().copy(titleId = "PCSE00000"))
        assertNull(SessionRepository.parseLine(line))
    }

    @Test
    fun parseLine_rechazaIdConBarra() {
        val line = SessionRepository.formatLine(validSession().copy(id = "a/b"))
        assertNull(SessionRepository.parseLine(line))
    }

    @Test
    fun parseLine_rechazaValoresNegativos() {
        val line = SessionRepository.formatLine(validSession().copy(playTimeMillis = -5L))
        assertNull(SessionRepository.parseLine(line))
    }

    @Test
    fun parseLine_rechazaTrofeosFueraDeRango() {
        val line = SessionRepository.formatLine(validSession().copy(unlockedTrophies = 999))
        assertNull(SessionRepository.parseLine(line))
    }

    // --- GpuDetector.classify (M24) ---

    @Test
    fun classify_maliPorRenderer() {
        assertEquals(GpuVendor.MALI, GpuDetector.classify("Mali-G57 MC2", "ARM"))
    }

    @Test
    fun classify_armSinMaliEsOther() {
        // Antes: vendor.contains("ARM") -> MALI aunque no fuera Mali.
        assertEquals(GpuVendor.OTHER, GpuDetector.classify("Some ARM GPU", "ARM"))
    }

    @Test
    fun classify_adreno() {
        assertEquals(GpuVendor.ADRENO, GpuDetector.classify("Adreno 730", "Qualcomm"))
    }

    @Test
    fun classify_vacioEsUnknown() {
        assertEquals(GpuVendor.UNKNOWN, GpuDetector.classify("", ""))
    }

    // --- Clamps de ajustes (M22) ---

    @Test
    fun clampResolution() {
        assertEquals(1f, SettingsRepository.clampResolution(0f))
        assertEquals(1f, SettingsRepository.clampResolution(-1f))
        assertEquals(4f, SettingsRepository.clampResolution(99f))
        assertEquals(1f, SettingsRepository.clampResolution(Float.NaN))
        assertEquals(2f, SettingsRepository.clampResolution(2f))
    }

    @Test
    fun clampAniso() {
        assertEquals(1, SettingsRepository.clampAniso(1))
        assertEquals(8, SettingsRepository.clampAniso(8))
        assertEquals(1, SettingsRepository.clampAniso(3))
        assertEquals(1, SettingsRepository.clampAniso(999))
    }

    // --- decodeControl intermedio 8-13 campos (M28) ---

    @Test
    fun decodeControl_10campos_noSePierde() {
        // Formato nuevo parcial: id|label|kind|vitaButton|x|y|size|opacity|color|shape
        val raw = "btn_a|A|button|CROSS|0.8|0.7|72|0.9|#FF0000|CIRCLE"
        val control = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(control)
        assertEquals("btn_a", control!!.id)
        assertEquals("CROSS", control.vitaButton.name)
        assertEquals(0.8f, control.xFraction)
        assertEquals("#FF0000", control.colorHex)
        // Los que faltan toman el default (visible true, sensibilidad 1.0).
        assertEquals(true, control.visible)
        assertEquals(1.0f, control.sensitivity)
    }

    @Test
    fun decodeControl_7campos_sigueSiendoLegacy() {
        val raw = "btn_a|A|button|0.8|0.7|72|0.9"
        val control = TouchLayoutRepository.decodeControl(raw)
        assertNotNull(control)
        assertEquals("CROSS", control!!.vitaButton.name)
    }
}
