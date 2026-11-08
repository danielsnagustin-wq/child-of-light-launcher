package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.presetSettings

/**
 * Nota de compatibilidad por preset (Ola 3, Etapa 8). Puro, testeable
 * en JVM (PresetNotesTest).
 *
 * Los 3 presets que pidió el usuario (G99 Recomendado, G99 Calidad,
 * Mali Equilibrado) se conservan tal cual, y el resto como hoy: la
 * lista filtrada por vendor ya existe en General. Cada preset modifica
 * parámetros REALES del núcleo (ver [presetSettings]: backend,
 * resolución, filtro, mapping, driver…); aquí solo se AÑADE una nota
 * de compatibilidad en la UI. No se inventa ningún preset nuevo ni se
 * cambia ningún valor.
 */
fun presetCompatibilityNote(profile: GpuProfileId, gpu: GpuInfo?): String = when (profile) {
    GpuProfileId.G99_RECOMMENDED ->
        "Recomendado en Helio G99 / Mali-G57: 1x estable, Double Buffer, sin Turnip."
    GpuProfileId.G99_QUALITY ->
        "2x con FSR en Mali: prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado."
    GpuProfileId.MALI_PERFORMANCE ->
        "Mali genérico (no G99): 1x siempre, Double Buffer forzado. Sin Turnip: el driver Turnip no funciona en Mali."
    GpuProfileId.MALI_BALANCED ->
        "Mali genérico: 1x con FSR y Frame Skip 0 si el FPS es estable. Sin Turnip: el driver Turnip no funciona en Mali."
    GpuProfileId.ADRENO_PERFORMANCE ->
        "Solo Snapdragon / Adreno: en Mali se usa el perfil G99 / Mali. Sin Turnip."
    GpuProfileId.ADRENO_QUALITY_TURNIP ->
        "Solo Snapdragon / Adreno: Turnip no funciona en Mali. " +
            if (gpu != null && gpu.vendor.name == "MALI") {
                "Tu GPU es Mali: este preset no se recomienda aquí; el driver quedará en Sistema."
            } else {
                "Vulkan + Turnip a 2x."
            }
    GpuProfileId.GENERIC_PERFORMANCE ->
        "Perfil seguro para GPU sin detectar: 1x, Double Buffer, sin Turnip."
}
