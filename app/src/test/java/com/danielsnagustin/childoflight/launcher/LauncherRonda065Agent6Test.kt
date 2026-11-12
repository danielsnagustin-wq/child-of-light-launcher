package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.ui.autoRevertTargetProfile
import com.danielsnagustin.childoflight.launcher.ui.extractLoadTimingLines
import com.danielsnagustin.childoflight.launcher.ui.sanitizeSessionNamesInReport
import com.danielsnagustin.childoflight.launcher.ui.settings.guardTurnipDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronda 0.2.65, agente 6/6: funciones puras de los arreglos de esta
 * área. Nada de Android: se prueban en JVM.
 */
class LauncherRonda065Agent6Test {

    // RG-P2-14: el destino del auto-revert depende de la GPU real.
    @Test
    fun autoRevertTargetIsAdrenoPerformanceOnAdreno() {
        val gpu = GpuInfo(vendor = GpuVendor.ADRENO, renderer = "Adreno 750")
        assertEquals(GpuProfileId.ADRENO_PERFORMANCE, autoRevertTargetProfile(gpu))
    }

    @Test
    fun autoRevertTargetIsG99RecommendedOnG99() {
        val gpu = GpuInfo(vendor = GpuVendor.MALI, renderer = "Mali-G57 MC2", socModel = "MT6789")
        assertEquals(GpuProfileId.G99_RECOMMENDED, autoRevertTargetProfile(gpu))
    }

    @Test
    fun autoRevertTargetIsMaliPerformanceOnXclipseAndPowerVr() {
        val xclipse = GpuInfo(vendor = GpuVendor.XCLIPSE, renderer = "Xclipse 940")
        assertEquals(GpuProfileId.MALI_PERFORMANCE, autoRevertTargetProfile(xclipse))
        val powervr = GpuInfo(vendor = GpuVendor.POWERVR, renderer = "PowerVR DXT-48-1536")
        assertEquals(GpuProfileId.MALI_PERFORMANCE, autoRevertTargetProfile(powervr))
    }

    @Test
    fun autoRevertTargetIsMaliPerformanceWhenGpuUnknown() {
        assertEquals(GpuProfileId.MALI_PERFORMANCE, autoRevertTargetProfile(null))
        val unknown = GpuInfo(vendor = GpuVendor.UNKNOWN)
        assertEquals(GpuProfileId.MALI_PERFORMANCE, autoRevertTargetProfile(unknown))
    }

    // T-P2-1: T1/T2 se extraen del log para su sección propia.
    @Test
    fun extractLoadTimingLinesKeepsOnlyT1AndT2() {
        val log = "[2026-11-11 20:00:00] [launch] JUGAR t=0ms (titleId=PCSB00598)\n" +
            "[2026-11-11 20:00:05] [launch] T1 (JUGAR → título): 5123ms\n" +
            "[2026-11-11 20:00:06] [launch] Primer frame presentado tras on_launch (T2 ≈ 6230ms desde JUGAR)\n" +
            "[2026-11-11 20:00:07] [gpu] Máscara de mapeo soportada: 26 (driver=SYSTEM)\n"
        val lines = extractLoadTimingLines(log)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("T1 (JUGAR"))
        assertTrue(lines[1].contains("T2 ≈"))
    }

    @Test
    fun extractLoadTimingLinesIsEmptyWithoutTimings() {
        val log = "[2026-11-11 20:00:00] [launch] JUGAR t=0ms (titleId=PCSB00598)\n"
        assertTrue(extractLoadTimingLines(log).isEmpty())
    }

    // T-P2-3: los nombres de sesión no salen en el informe exportado.
    @Test
    fun sanitizeSessionNamesReplacesQuotedNames() {
        val report = "Partida terminada: +12 min en \"Mi partida secreta\" (snapshot guardado)\n" +
            "Perfil: G99 / Mali-G57 — Recomendado\n"
        val out = sanitizeSessionNamesInReport(report, listOf("Mi partida secreta"))
        assertTrue(out.contains("\"sesión\""))
        assertFalse(out.contains("Mi partida secreta"))
        assertTrue(out.contains("G99 / Mali-G57 — Recomendado"))
    }

    @Test
    fun sanitizeSessionNamesLeavesOtherTextAlone() {
        val report = "Sin sesiones todavía.\n"
        assertEquals(report, sanitizeSessionNamesInReport(report, listOf("Cualquiera")))
        assertEquals(report, sanitizeSessionNamesInReport(report, listOf("")))
    }

    // G-P2-7: al volver al driver del sistema se limpian los 3 flags.
    @Test
    fun guardTurnipDriverOnMaliClearsAllThreeFlags() {
        val turnip = LauncherSettings(
            gpuDriver = GpuDriver.TURNIP,
            turnipInstalled = true,
            turnipVersion = "Turnip_v26.0.0_R8_Gmem.zip",
            customDriverName = "Turnip_v26.0.0_R8_Gmem",
        )
        val guarded = guardTurnipDriver(turnip, GpuVendor.MALI)
        assertEquals(GpuDriver.SYSTEM, guarded.gpuDriver)
        assertFalse(guarded.turnipInstalled)
        assertEquals("", guarded.turnipVersion)
        assertEquals("", guarded.customDriverName)
    }

    @Test
    fun guardTurnipDriverOnAdrenoWithTurnipKeepsFlags() {
        val turnip = LauncherSettings(
            gpuDriver = GpuDriver.TURNIP,
            turnipInstalled = true,
            turnipVersion = "Turnip_v26.0.0_R8_Gmem.zip",
            customDriverName = "Turnip_v26.0.0_R8_Gmem",
        )
        val guarded = guardTurnipDriver(turnip, GpuVendor.ADRENO)
        assertEquals(GpuDriver.TURNIP, guarded.gpuDriver)
        assertTrue(guarded.turnipInstalled)
        assertEquals("Turnip_v26.0.0_R8_Gmem.zip", guarded.turnipVersion)
        assertEquals("Turnip_v26.0.0_R8_Gmem", guarded.customDriverName)
    }
}
