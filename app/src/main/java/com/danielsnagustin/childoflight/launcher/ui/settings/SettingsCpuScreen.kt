package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSlider
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "CPU" (Ola 2, Etapa 3).
 *
 * Honestidad: NO existe ninguna clave JIT / cpu-backend en config.h
 * (verificado en el pin 3e43bc80). Dynarmic es el backend por defecto
 * del núcleo y siempre está activo: no hay interruptor "JIT Dynamic"
 * porque no haría nada. Lo que sí existe es cpu-opt, que controla
 * las optimizaciones de Dynarmic, y se presenta tal cual.
 * El modo 60 FPS (fps-hack) es experimental y va separado, con sus
 * riesgos explicados en texto corto.
 */
@Composable
fun SettingsCpuScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val s = settings
    SettingsCategoryScaffold(
        title = "CPU",
        subtitle = "Optimización, modo 60 FPS y carga de archivos.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.CPU) },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Procesador")
                SettingSwitch("Optimización CPU (Dynarmic)", "cpu-opt en config.yml. Dynarmic es el backend por defecto del núcleo y siempre está activo: no se puede apagar desde la configuración; este ajuste controla sus optimizaciones.", s.cpuOpt) { vm.saveSettings(s.copy(cpuOpt = it)) }
                // R2-P2-25: la etiqueta muestra la unidad (ms): antes
                // no se sabía si eran ms, % o qué.
                // P2-20 + R2-S02: el valor ES un retraso (el núcleo
                // duerme esos ms en sceIoOpen antes de abrir cada
                // archivo: pin SceLibKernel.cpp:626-627): subirlo hace
                // la carga MÁS LENTA, no más rápida. Tope 30 ms: más
                // que eso solo ralentiza el juego sin uso conocido.
                SettingSlider("Retraso de carga de archivos", s.fileLoadingDelay, 0..30, { vm.saveSettings(s.copy(fileLoadingDelay = it)) }) { "$it ms" }
                NoteText("Retraso de carga (file-loading-delay, en config.yml): 0 es lo normal (sin retraso). El núcleo espera esos ms cada vez que abre un archivo del juego: súbelo solo si el juego tiene problemas de carga por sincronización. Un valor alto hace el juego MÁS LENTO, no más rápido.")
            }
            item {
                SectionTitle("Experimental")
                SettingSwitch("Modo 60 FPS (opcional)", "Intenta 30 → 60 fps con fps-hack y los ajustes rápidos del modo. Puede no hacer nada o hacer que el juego corra al doble de velocidad: compruébalo con el contador de FPS y la música como referencia. Se aplica al reiniciar el juego; apágalo para volver a 30. OFF por defecto.", s.fpsHack) { vm.saveSettings(s.copy(fpsHack = it)) }
                NoteText("Experimental: el juego en Vita es 30 fps por diseño. Si corre al doble de velocidad o no notas cambio, apaga el modo y vuelve a 30 bloqueado y fluido.")
            }
        }
    }
}
