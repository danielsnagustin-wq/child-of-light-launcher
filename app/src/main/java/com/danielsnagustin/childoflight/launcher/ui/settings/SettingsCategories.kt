package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.ui.Screen

/**
 * Categorías de Ajustes (Fase 2, Ola 2). Cada categoría es una pantalla
 * propia (Screen.SETTINGS_*) y el hub (Screen.SETTINGS) las lista todas.
 * El título y el subtítulo son los que se ven en el hub; el buscador
 * usa además el registro de entradas de SettingsSearch.
 */
enum class SettingsCategory(val screen: Screen, val title: String, val subtitle: String) {
    GENERAL(
        Screen.SETTINGS_GENERAL,
        "General",
        "Perfil activo, presets, secciones, motor y aviso legal.",
    ),
    PERSONALIZATION(
        Screen.SETTINGS_PERSONALIZATION,
        "Personalización",
        "Tema, acento, fondo, texto, idioma y portada.",
    ),
    GPU(
        Screen.SETTINGS_GPU,
        "GPU y gráficos",
        "Backend, resolución, filtros, driver y caché de shaders.",
    ),
    CPU(
        Screen.SETTINGS_CPU,
        "CPU",
        "Optimización Dynarmic, modo 60 FPS y retraso de carga.",
    ),
    AUDIO(
        Screen.SETTINGS_AUDIO,
        "Audio",
        "Backend, buffer, NGS, volumen y Corregir audio.",
    ),
    PERFORMANCE(
        Screen.SETTINGS_PERFORMANCE,
        "Rendimiento",
        "Límite de FPS, cachés, overlay y valores recomendados.",
    ),
    CONTROLS(
        Screen.SETTINGS_CONTROLS,
        "Controles y HUD",
        "Overlay táctil en el juego: mostrar, opacidad y perfiles.",
    ),
    STORAGE(
        Screen.SETTINGS_STORAGE,
        "Almacenamiento",
        "Copias en Descargas y limpieza de lo no usado.",
    ),
    DIAGNOSTICS(
        Screen.SETTINGS_DIAGNOSTICS,
        "Diagnóstico",
        "Prueba de 30 s y corrección de errores.",
    ),
}
