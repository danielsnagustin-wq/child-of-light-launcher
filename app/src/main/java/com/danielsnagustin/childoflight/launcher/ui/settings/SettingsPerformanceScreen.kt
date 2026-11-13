package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Rendimiento" (Ola 2, Etapa 5).
 *
 * La caché de texturas (texture-cache,
 * clave real) NO tenía interruptor en la antigua pantalla única: se
 * añade aquí. La caché de shaders se muda aquí desde la antigua
 * sección "Corrección de errores" (es una caché, no una mitigación),
 * junto con su botón de limpieza (antes en GPU y gráficos).
 * "Restaurar valores recomendados" NO está aquí: es lo mismo que
 * "Restablecer ajustes (preset de tu GPU)" en General, que es su
 * sitio canónico. La prueba de 30 s vive en Diagnóstico
 * ("GPU y diagnóstico (prueba de 30 s)").
 */
@Composable
fun SettingsPerformanceScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val s = settings
    SettingsCategoryScaffold(
        title = "Rendimiento",
        subtitle = "FPS, cachés y overlay.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.PERFORMANCE) },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Fotogramas")
                SettingSwitch("Volver a 1x si baja de 28 fps", "Con cualquier perfil 2x (G99 Calidad, Adreno Calidad/Turnip o un 2x puesto a mano). Automático al registrar el FPS de la prueba.", s.autoRevertTo1xIfBelow28Fps) { vm.saveSettings(s.copy(autoRevertTo1xIfBelow28Fps = it)) }
                SettingSwitch("Modo rendimiento del launcher", "Mantiene la pantalla encendida mientras juegas.", s.performanceMode) { vm.saveSettings(s.copy(performanceMode = it)) }
            }
            item {
                SectionTitle("Cachés")
                // Ronda 0.2.65 (R-P1-5): la descripción anterior decía
                // que en teléfonos con 4 GB o menos el primer arranque
                // lo deja OFF (perfil conservador). Eso ya no es verdad:
                // todos los presets llevan texture-cache ON desde 0.2.65
                // (en Vulkan el núcleo lo ignora y siempre cachea, así
                // que el interruptor solo tiene efecto con OpenGL).
                SettingSwitch("Caché de texturas", "texture-cache en config.yml. ON recomendado: sin ella las texturas se vuelven a subir desde la memoria del juego en cada draw. En Vulkan sí tiene efecto desde la 0.2.66 (parche c-01): OFF re-sube cada textura y es más lento. En OpenGL, el de siempre.", s.textureCache) { vm.saveSettings(s.copy(textureCache = it)) }
                // R2-P2-08: sin "ON siempre": el interruptor lo puede
                // apagar el usuario (los presets lo ponen ON por
                // defecto, pero nada lo fuerza).
                // Ronda 0.2.65 (R-P1-6): en Vulkan, shader-cache solo
                // controla el precompilado de shaders conocidos al
                // arrancar: OFF no ahorra disco ni RAM y quita el
                // precompilado que evita tirones en escenas nuevas. El
                // interruptor se etiqueta por lo que hace de verdad.
                SettingSwitch("Precompilar shaders conocidos al arrancar", "shader-cache en config.yml. En Vulkan solo controla el precompilado al arrancar: el stutter de la primera partida se va cuando la caché se llena. OFF no ahorra disco ni RAM. ON recomendado.", s.shaderCache) { vm.saveSettings(s.copy(shaderCache = it)) }
                // Ronda 0.2.68 (load-p1): carga rápida. El precompilado
                // de shaders al arrancar es serial (un programa detrás
                // de otro, sin imagen mientras tanto) y en este teléfono
                // costaba 92-147 s hasta el primer frame, y con la caché
                // ya grande el juego "ni siquiera entra". Con la carga
                // rápida ON, se espera que el juego entre en segundos
                // (pendiente medir T2 en el teléfono) y los shaders se
                // compilan bajo demanda con la compilación asíncrona
                // (3 hilos): sin bloqueo, solo objetos que aparecen unos
                // frames más tarde. ON por defecto en los presets Mali /
                // G99. Se aplica en el siguiente lanzamiento.
                SettingSwitch("Carga rápida (sin precompilar shaders)", "skip-shader-precompile en config.yml. Salta el precompilado de shaders al arrancar: se espera que el juego entre en segundos en vez de esperar minutos en negro (pendiente medirlo en el teléfono). Los shaders se compilan bajo demanda durante el juego (compilación asíncrona). Si ves objetos que tardan en aparecer al entrar en una escena nueva, apágalo. Se aplica en el siguiente lanzamiento.", s.skipShaderPrecompile) { vm.saveSettings(s.copy(skipShaderPrecompile = it)) }
                // Tercera pasada (A-2): el texto de arriba promete
                // compilación asíncrona, pero con la compilación
                // asíncrona OFF la compilación bajo demanda es
                // síncrona en el hilo de render (pipeline_cache.cpp
                // del pin): tirones al entrar en escenas nuevas.
                if (!s.asyncPipelineCompilation) {
                    NoteText("Con la compilación asíncrona OFF, los shaders se compilan bajo demanda en el hilo de render: tirones al entrar en escenas nuevas. Enciende la compilación asíncrona (GPU y gráficos) para que la carga rápida no bloquee.")
                }
                OutlinedButton(onClick = { vm.clearShaderCache() }, modifier = Modifier.fillMaxWidth()) { Text("Limpiar caché de shaders") }
                NoteText("Limpia la caché solo si hay glitches. El stutter de la primera partida es normal hasta que la caché se vuelva a llenar.")
                // Ronda 0.2.70 (11-P2-1): en Vulkan el interruptor es
                // inerte (el núcleo lo tiene siempre activado,
                // renderer.cpp:1046) y su texto prometía FPS y lo negaba
                // a la vez. Mientras el backend efectivo sea Vulkan el
                // interruptor se OCULTA y queda solo esta nota; con
                // OpenGL vuelve a aparecer (el campo del modelo, el
                // generador y el validador se quedan intactos).
                if (resolveBackend(s, gpu) == GpuBackend.VULKAN) {
                    NoteText("Caché de texturas sin hash: en Vulkan el núcleo lo tiene siempre activado, así que no hay interruptor con este backend. Solo aparece con OpenGL.")
                } else {
                    SettingSwitch("Caché de texturas sin hash (experimental)", "Solo OpenGL: el núcleo vigila la memoria de las texturas grandes en vez de recalcular el hash en cada frame. Puede dar más FPS en escenas con muchas texturas. Si alguna textura se ve incorrecta, apágalo. OFF por defecto.", s.hashlessTextureCache) { vm.saveSettings(s.copy(hashlessTextureCache = it)) }
                }
            }
            item {
                SectionTitle("Overlay de FPS")
                SettingSwitch("Mostrar overlay de FPS", "performance-overlay en config.yml. Actívalo para la prueba de 30 s.", s.showFpsOverlay) { vm.saveSettings(s.copy(showFpsOverlay = it)) }
                EnumChips("Detalle del overlay de FPS", PerformanceOverlayDetail.entries, s.performanceOverlayDetail, { it.label }) { vm.saveSettings(s.copy(performanceOverlayDetail = it)) }
                EnumChips("Posición del overlay de FPS", PerformanceOverlayPosition.entries, s.performanceOverlayPosition, { it.label }) { vm.saveSettings(s.copy(performanceOverlayPosition = it)) }
                NoteText("El detalle y la posición solo se notan con el overlay de FPS activado.")
                SettingSwitch("Mostrar mensajes de compilación de shaders", "OFF por defecto: pantalla limpia, sin avisos de shaders al arrancar o cambiar de escena. Actívalo si quieres verlos.", s.showCompileShaders) { vm.saveSettings(s.copy(showCompileShaders = it)) }
            }
            // Ronda 0.2.66 — Ajustes experimentales. No hay una pantalla
            // "Ajustes avanzados" en 0.2.65 (las 9 categorías son las de
            // SettingsCategories): la sección vive aquí, en Rendimiento,
            // con el título "Ajustes Experimentales" (decisión del usuario
            // 2026-11-12: se queda en Rendimiento, marcado como experimental).
            // Regla del proyecto: un interruptor sin efecto real no se
            // crea. Por eso aquí hay UN solo interruptor (Audio SDL, con
            // su parche adaptado en app/src/main/cpp/patches/): las demás
            // familias no tienen interruptor porque su parche no se pudo
            // adaptar limpio al núcleo actual (ver patches/experimental/).
            item {
                SectionTitle("Ajustes Experimentales")
                NoteText("Solo para probar: pueden romper el sonido o el juego. Todos OFF por defecto. Cambiar uno exige salir del juego y volver a entrar. El modo seguro los apaga todos.")
                SettingSwitch("Audio experimental (SDL y Cubeb)", "exp-audio-sdl en config.yml. Nuevo manejo del audio (SDL y Cubeb): sin la espera que frena el hilo del juego, con la cola calibrada contra lo que falta por sonar. Si el sonido cruje, se retrasa o se entrecorta, apágalo y vuelve al audio actual.", s.expAudioSdl) { vm.saveSettings(s.copy(expAudioSdl = it)) }
                NoteText("Sin interruptor (aún no se pueden probar en esta compilación): caché de texturas / memoria baja y Double Buffer — sus parches del fork necesitan piezas que el núcleo actual no tiene y van en patches/experimental/ con su plan de portado. El polling del mando con 60 FPS tampoco lleva interruptor: solo actúa con el modo 60 FPS activado (Ajustes → CPU), y sus parches también están pendientes de reescribirse para el núcleo actual.")
            }
        }
    }
}
