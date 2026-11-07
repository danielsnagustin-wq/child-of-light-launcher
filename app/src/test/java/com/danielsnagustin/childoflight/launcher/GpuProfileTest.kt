package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.shouldAutoRevertTo1x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuProfileTest {

    private fun g99Gpu() = GpuInfo(
        vendor = GpuVendor.MALI,
        renderer = "Mali-G57 MC2",
        socModel = "MT6789",
        deviceModel = "SM-A175F",
        vulkanSupported = true,
        vulkanVersionLabel = "Vulkan 1.1+",
    )

    @Test
    fun g99IsDetectedAsUserVerifiedDevice() {
        val gpu = g99Gpu()
        assertTrue(gpu.isHelioG99)
        assertTrue(gpu.isMaliG57)
        assertTrue(gpu.isUserVerifiedDevice)
    }

    @Test
    fun defaultProfileForG99IsRecommended() {
        assertEquals(GpuProfileId.G99_RECOMMENDED, defaultProfileFor(g99Gpu()))
    }

    @Test
    fun g99RecommendedPresetValues() {
        val s = presetSettings(GpuProfileId.G99_RECOMMENDED)
        assertEquals(1f, s.resolutionMultiplier, 0.001f)
        assertEquals(false, s.vSync)
        assertEquals(true, s.asyncPipelineCompilation)
        assertEquals(false, s.surfaceSyncEnabled)
        assertEquals("DOUBLE_BUFFER", s.memoryMapping.name)
        assertEquals(true, s.accurateThreadScheduling)
        assertEquals(true, s.cpuOpt)
        assertEquals(0, s.frameSkipping)
        assertEquals("FPS_30", s.frameLimiter.name)
        assertEquals("SDL", s.audioBackend.name)
        assertEquals(true, s.ngsEnable)
    }

    @Test
    fun g99QualityPresetIs2xWithFsr() {
        val s = presetSettings(GpuProfileId.G99_QUALITY)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("FSR", s.screenFilter.name)
        assertEquals(0, s.frameSkipping)
    }

    @Test
    fun maliGenericNeverRecommends2x() {
        val perf = presetSettings(GpuProfileId.MALI_PERFORMANCE)
        val balanced = presetSettings(GpuProfileId.MALI_BALANCED)
        assertEquals(1f, perf.resolutionMultiplier, 0.001f)
        assertEquals(1f, balanced.resolutionMultiplier, 0.001f)
        assertEquals("DOUBLE_BUFFER", perf.memoryMapping.name)
    }

    @Test
    fun adrenoQualityUsesTurnipAndPageTable() {
        val s = presetSettings(GpuProfileId.ADRENO_QUALITY_TURNIP)
        assertEquals(2f, s.resolutionMultiplier, 0.001f)
        assertEquals("PAGE_TABLE", s.memoryMapping.name)
        assertEquals("TURNIP", s.gpuDriver.name)
    }

    @Test
    fun autoRevertOnlyForG99QualityBelow28() {
        assertTrue(shouldAutoRevertTo1x(27f, GpuProfileId.G99_QUALITY, true))
        assertFalse(shouldAutoRevertTo1x(28f, GpuProfileId.G99_QUALITY, true))
        assertFalse(shouldAutoRevertTo1x(30f, GpuProfileId.G99_QUALITY, true))
        assertFalse(shouldAutoRevertTo1x(20f, GpuProfileId.G99_RECOMMENDED, true))
        assertFalse(shouldAutoRevertTo1x(20f, GpuProfileId.G99_QUALITY, false))
        assertFalse(shouldAutoRevertTo1x(null, GpuProfileId.G99_QUALITY, true))
    }

    @Test
    fun safeModeForcesSafeValues() {
        val s = applySafeMode(LauncherSettings(resolutionMultiplier = 4f, highAccuracy = true))
        assertEquals(1f, s.resolutionMultiplier, 0.001f)
        assertEquals(false, s.highAccuracy)
        assertEquals("SDL", s.audioBackend.name)
        assertEquals(2048, s.audioBufferSize)
        assertEquals(true, s.accurateThreadScheduling)
        assertEquals(true, s.safeMode)
    }
}
