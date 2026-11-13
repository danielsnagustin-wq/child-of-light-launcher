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
 * Recomendado, calculada campo a campo desde [presetSettings].
 * Ronda 0.2.65: DOS presets tienen CERO diferencias con Recomendado
 * (MALI_PERFORMANCE y MALI_BALANCED: R-P1-5/R-P1-9 los dejaron
 * idénticos en efecto — 1x, Bilinear, Page Table, cachés ON) y
 * sus notas lo dicen así, sin inventar una diferencia. Siguen
 * existiendo como entradas propias (etiqueta y defaultProfileFor).
 * G99_QUALITY solo difiere en la resolución 2x (R-P1-8 quitó el FSR
 * y R-P1-17 el HUD), GENERIC_QUALITY igual en resolución (2x, misma
 * idea con etiqueta neutra para GPU sin perfil propio, RG-P2-16)
 * pero deja la carga rápida apagada (ver abajo).
 * Ronda 0.2.66 (G11-01): el mapeo por defecto pasó a Page Table en
 * Mali/G99, así que ADRENO_PERFORMANCE TAMBIÉN es idéntico en
 * efecto a Recomendado (antes solo difería en el mapeo) y
 * ADRENO_QUALITY_TURNIP difiere en 5 ajustes, no 6 (Page Table ya
 * no cuenta como diferencia).
 * Ronda 0.2.68 (load-p1): la carga rápida (skipShaderPrecompile)
 * solo se enciende en los 4 presets Mali/G99; Adreno y el genérico
 * la dejan apagada (valor upstream, deliberado y testeado en
 * GpuProfileTest). Desde la ronda 0.2.70 (4-P2-1) las notas lo
 * dicen: GENERIC_QUALITY difiere además en la carga rápida,
 * ADRENO_PERFORMANCE solo en la carga rápida y TURNIP en 6 ajustes
 * (los 5 de antes + carga rápida apagada).
 *
 * R5-A2-6: las notas SÍ matizan la preservación: la diferencia
 * descrita es la del preset canónico y el resultado real conserva
 * los cambios manuales (ver preserveManualCacheChoices).
 */
fun presetCompatibilityNote(profile: GpuProfileId): String = when (profile) {
    GpuProfileId.G99_RECOMMENDED ->
        "Recomendado en Helio G99 / Mali-G57: 1x estable, Page Table, sin Turnip. Page Table es el nuevo valor por defecto (ronda 0.2.66): la hipótesis es que Double Buffer es el método más caro en Mali, pero aún no está medido en este teléfono. Mide con la prueba de 30 s; si va lento o hay glitches, prueba Double Buffer en Ajustes → GPU."
    GpuProfileId.G99_QUALITY ->
        "2x con Bilinear: la única diferencia con Recomendado es la resolución 2x (salvo tus cambios manuales conservados). Prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado."
    GpuProfileId.MALI_PERFORMANCE ->
        "Mali genérico (no G99): en efecto es igual que Recomendado (1x, Bilinear, Page Table, cachés ON, sin Turnip): la única diferencia es la etiqueta (salvo tus cambios manuales conservados). 1x siempre, Page Table en el preset (se puede cambiar a mano). Si va lento, prueba Double Buffer en Ajustes → GPU y mide con la prueba de 30 s. Sin Turnip: el driver Turnip no funciona en Mali."
    GpuProfileId.MALI_BALANCED ->
        "Mali genérico: en efecto es igual que Recomendado (1x, Bilinear, Page Table, sin Turnip): la única diferencia es la etiqueta. Sin Turnip: el driver Turnip no funciona en Mali."
    GpuProfileId.GENERIC_QUALITY ->
        "Genérico (Xclipse, PowerVR u otra GPU sin perfil propio): 2x con Bilinear. Difiere de Recomendado en la resolución 2x y en la carga rápida, que queda apagada (valor upstream; actívala en Rendimiento si la carga es larga). Prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado."
    GpuProfileId.ADRENO_PERFORMANCE ->
        "Solo Snapdragon / Adreno: en efecto es igual que Recomendado (1x, Bilinear, Page Table, cachés ON, sin Turnip), salvo la carga rápida, que queda apagada (valor upstream; actívala en Rendimiento si la carga es larga): la única diferencia es la etiqueta y la carga rápida. En Mali se usa el perfil G99 / Mali. Sin Turnip."
    GpuProfileId.ADRENO_QUALITY_TURNIP ->
        "Solo Snapdragon / Adreno: Vulkan + Turnip a 2x. Difiere de Recomendado en 6 ajustes: resolución 2x, filtro FXAA, anisotrópico 4x, driver Turnip, HUD de FPS activado y carga rápida apagada (el HUD es la prueba visible de que Turnip cargó; el HUD se enciende salvo que lo apagaras a mano). Turnip no funciona en Mali."
}
