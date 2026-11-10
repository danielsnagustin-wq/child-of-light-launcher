package com.danielsnagustin.childoflight.launcher.ui.settings

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
 *
 * R2-P2-23: la función ya no recibe la GPU. Su rama Mali/Turnip era
 * muerta: el preset ADRENO_QUALITY_TURNIP nunca se lista cuando la
 * GPU es Mali (la lista de General lo filtra por vendor), así que la
 * advertencia "no se recomienda aquí" nunca se mostraba; y si se
 * aplicara, el guard de applyPreset ya corrige el driver a Sistema y
 * avisa. La nota estática ("Turnip no funciona en Mali") sigue
 * diciendo lo mismo para quien la lea.
 *
 * P2-16: cada nota dice la diferencia REAL del preset con G99
 * Recomendado, calculada campo a campo desde [presetSettings]
 * (ningún preset tiene cero diferencias, así que ninguno se fusiona:
 * MALI_PERFORMANCE solo apaga la caché de texturas, MALI_BALANCED
 * solo cambia el filtro a FSR, ADRENO_PERFORMANCE solo cambia el
 * mapeo a Page Table, G99_QUALITY cambia 3 ajustes y
 * ADRENO_QUALITY_TURNIP cambia 6).
 *
 * R5-A2-6: las notas describen el preset CANÓNICO, no el resultado
 * real cuando la preservación conserva un cambio manual (ver
 * preserveManualCacheChoices): por eso MALI_PERFORMANCE y
 * G99_QUALITY se matizan con "salvo tus cambios manuales
 * conservados".
 */
fun presetCompatibilityNote(profile: GpuProfileId): String = when (profile) {
    GpuProfileId.G99_RECOMMENDED ->
        "Recomendado en Helio G99 / Mali-G57: 1x estable, Double Buffer, sin Turnip."
    GpuProfileId.G99_QUALITY ->
        "2x con FSR en Mali y HUD de FPS activado: las únicas diferencias con Recomendado son la resolución 2x, el filtro FSR y el HUD, salvo tus cambios manuales conservados (si tenías el HUD apagado, se queda apagado). Prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado."
    GpuProfileId.MALI_PERFORMANCE ->
        "Mali genérico (no G99): la única diferencia con Recomendado es la caché de texturas OFF (libera RAM), salvo tus cambios manuales conservados (si la tenías ON, se queda ON). 1x siempre, Double Buffer forzado. Sin Turnip: el driver Turnip no funciona en Mali."
    GpuProfileId.MALI_BALANCED ->
        "Mali genérico: la única diferencia con Recomendado es el filtro FSR a 1x, si el FPS es estable. Sin Turnip: el driver Turnip no funciona en Mali."
    GpuProfileId.ADRENO_PERFORMANCE ->
        "Solo Snapdragon / Adreno: la única diferencia con Recomendado es el mapeo de memoria Page Table (recomendado en Android para Adreno). En Mali se usa el perfil G99 / Mali. Sin Turnip."
    GpuProfileId.ADRENO_QUALITY_TURNIP ->
        "Solo Snapdragon / Adreno: Vulkan + Turnip a 2x. Difiere de Recomendado en 6 ajustes: resolución 2x, filtro FXAA, anisotrópico 4x, VSync ON, Page Table y driver Turnip. Turnip no funciona en Mali."
}
