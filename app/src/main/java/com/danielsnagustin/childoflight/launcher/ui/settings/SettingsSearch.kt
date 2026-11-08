package com.danielsnagustin.childoflight.launcher.ui.settings

/**
 * Buscador de ajustes (Fase 2, Ola 2, Etapa 1).
 *
 * Archivo PURO: sin Compose ni Android, testeable en JVM. Cada entrada
 * es un ajuste real que existe en alguna categoría; el buscador no
 * inventa ajustes ni lleva a sitios que no existen.
 *
 * Coincidencia: la consulta se parte en palabras y TODAS deben aparecer
 * (tras normalizar) en el título, las palabras clave o el título de la
 * categoría. La normalización quita mayúsculas y tildes, así que
 * "diagnostico" encuentra "Diagnóstico" y "60 fps" encuentra el modo
 * 60 FPS. Una consulta vacía no devuelve nada: el hub muestra entonces
 * las 9 categorías.
 */
data class SearchableSetting(
    val title: String,
    val keywords: String,
    val category: SettingsCategory,
)

fun normalizeSearchText(text: String): String {
    val folded = text.lowercase()
    val out = StringBuilder(folded.length)
    for (c in folded) {
        out.append(
            when (c) {
                'á' -> 'a'
                'é' -> 'e'
                'í' -> 'i'
                'ó' -> 'o'
                'ú' -> 'u'
                'ü' -> 'u'
                'ñ' -> 'n'
                else -> c
            },
        )
    }
    return out.toString()
}

fun searchSettings(
    query: String,
    index: List<SearchableSetting> = SETTINGS_SEARCH_INDEX,
): List<SearchableSetting> {
    val words = normalizeSearchText(query)
        .split(' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return index.filter { entry ->
        val haystack = normalizeSearchText(
            entry.title + " " + entry.keywords + " " + entry.category.title,
        )
        words.all { haystack.contains(it) }
    }
}

private fun entry(title: String, keywords: String, category: SettingsCategory) =
    SearchableSetting(title, keywords, category)

/**
 * Registro de entradas buscables. Cubre TODOS los controles que
 * existían en la pantalla única de Ajustes antes de la Ola 2, más los
 * añadidos por la Ola 2 (caché de texturas, deshacer Corregir audio,
 * restaurar valores recomendados). Si un ajuste no está aquí, el
 * buscador no lo encuentra: al añadir un ajuste nuevo hay que
 * registrarlo aquí también.
 */
val SETTINGS_SEARCH_INDEX: List<SearchableSetting> = listOf(
    // --- General ---
    entry("Perfil activo", "perfil rendimiento gpu preset activo", SettingsCategory.GENERAL),
    entry("Presets por GPU", "preset g99 mali adreno calidad rendimiento recomendado", SettingsCategory.GENERAL),
    entry("Sesiones y guardados", "sesiones partidas guardados saves", SettingsCategory.GENERAL),
    entry("Logros", "logros trofeos 27 achievements", SettingsCategory.GENERAL),
    entry("Controles táctiles", "controles tactiles editor botones mando", SettingsCategory.GENERAL),
    entry("Permitir app externa", "motor app externa casos especiales", SettingsCategory.GENERAL),
    entry("Aviso legal", "legal ubisoft sony licencia", SettingsCategory.GENERAL),
    entry("Ver config.yml generado", "config yml ver generado yaml", SettingsCategory.GENERAL),
    entry("Restablecer ajustes", "restablecer reset preset gpu defecto", SettingsCategory.GENERAL),
    entry("Copia de seguridad de ajustes", "copia seguridad backup exportar importar json ajustes", SettingsCategory.GENERAL),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.GENERAL),
    // --- Personalización ---
    entry("Tema", "tema claro oscuro sistema apariencia", SettingsCategory.PERSONALIZATION),
    entry("Color de acento", "acento color azul hielo lavanda turquesa plata", SettingsCategory.PERSONALIZATION),
    entry("Fondo de la pantalla principal", "fondo portada home amoled negro", SettingsCategory.PERSONALIZATION),
    entry("Tamaño de texto", "texto tamaño letra grande pequeño", SettingsCategory.PERSONALIZATION),
    entry("Idioma", "idioma español english language", SettingsCategory.PERSONALIZATION),
    entry("Tamaño de tarjetas", "tarjetas tamaño compacta normal amplia padding", SettingsCategory.PERSONALIZATION),
    entry("Transparencia de tarjetas", "tarjetas transparencia opaco suave translucido", SettingsCategory.PERSONALIZATION),
    entry("Tamaño de iconos", "iconos tamaño pequeño mediano grande", SettingsCategory.PERSONALIZATION),
    entry("Aplicar personalización", "aplicar descartar cambios personalizacion guardar borrador", SettingsCategory.PERSONALIZATION),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.PERSONALIZATION),
    entry("Mostrar FPS en la pantalla principal", "fps portada home mostrar", SettingsCategory.PERSONALIZATION),
    entry("Mostrar logros en la pantalla principal", "logros portada home mostrar", SettingsCategory.PERSONALIZATION),
    entry("Orden de secciones en la portada", "orden secciones portada home", SettingsCategory.PERSONALIZATION),
    entry("Restablecer personalización y editor táctil", "restablecer personalizacion editor tactil reset tema", SettingsCategory.PERSONALIZATION),
    // --- GPU y gráficos ---
    entry("Backend", "backend renderer vulkan opengl", SettingsCategory.GPU),
    entry("Resolución interna", "resolucion 1x 2x 3x 4x interna", SettingsCategory.GPU),
    entry("Filtro de pantalla", "filtro pantalla bilinear nearest bicubic fxaa fsr", SettingsCategory.GPU),
    entry("Anisotrópico", "anisotropico texturas 2x 4x 8x 16x", SettingsCategory.GPU),
    entry("V-Sync", "vsync sincronizacion vertical", SettingsCategory.GPU),
    entry("Async Pipeline Compilation", "async pipeline compilacion shaders segundo plano tirones", SettingsCategory.GPU),
    entry("High Accuracy Rendering", "high accuracy precision renderizado lento", SettingsCategory.GPU),
    entry("Surface Sync activado", "surface sync sincronizacion superficie rapido", SettingsCategory.GPU),
    entry("Memory Mapping", "memory mapping double buffer page table native external off", SettingsCategory.GPU),
    entry("Driver GPU", "driver gpu turnip sistema adreno mali", SettingsCategory.GPU),
    entry("Instalar Turnip", "turnip instalar cambiar zip driver mesa", SettingsCategory.GPU),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.GPU),
    // --- CPU ---
    entry("Optimización CPU (Dynarmic)", "cpu optimizacion dynarmic jit", SettingsCategory.CPU),
    entry("Modo 60 FPS", "60 fps hack experimental velocidad doble", SettingsCategory.CPU),
    entry("Retraso de carga de archivos", "retraso carga archivos file loading delay", SettingsCategory.CPU),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.CPU),
    // --- Audio ---
    entry("Backend de audio", "audio backend sdl cubeb", SettingsCategory.AUDIO),
    entry("NGS", "ngs audio sistema vita", SettingsCategory.AUDIO),
    entry("Volumen", "volumen audio", SettingsCategory.AUDIO),
    entry("Corregir audio", "corregir audio arreglar sdl ngs", SettingsCategory.AUDIO),
    entry("Deshacer Corregir audio", "deshacer corregir audio restaurar valores anteriores", SettingsCategory.AUDIO),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.AUDIO),
    // --- Rendimiento ---
    entry("Caché de texturas", "cache texturas texture cache ram", SettingsCategory.PERFORMANCE),
    entry("Caché de shaders", "cache shaders shader cache", SettingsCategory.PERFORMANCE),
    entry("Limpiar caché de shaders", "limpiar cache shaders glitches stutter", SettingsCategory.PERFORMANCE),
    entry("Caché de texturas sin hash", "hashless texturas experimental hash", SettingsCategory.PERFORMANCE),
    entry("Mostrar overlay de FPS", "overlay fps hud mostrar rendimiento", SettingsCategory.PERFORMANCE),
    entry("Detalle del overlay de FPS", "overlay fps detalle minimo bajo medio maximo", SettingsCategory.PERFORMANCE),
    entry("Posición del overlay de FPS", "overlay fps posicion arriba abajo izquierda derecha", SettingsCategory.PERFORMANCE),
    entry("Mostrar mensajes de compilación de shaders", "mensajes compilacion shaders avisos", SettingsCategory.PERFORMANCE),
    entry("Volver a 1x si baja de 28 fps", "volver 1x 28 fps automatico calidad 2x", SettingsCategory.PERFORMANCE),
    entry("Modo rendimiento del launcher", "modo rendimiento pantalla encendida", SettingsCategory.PERFORMANCE),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.PERFORMANCE),
    // --- Controles y HUD ---
    entry("Mostrar controles táctiles en el juego", "controles tactiles overlay juego mostrar", SettingsCategory.CONTROLS),
    entry("Opacidad general del overlay", "opacidad overlay tactil", SettingsCategory.CONTROLS),
    entry("Brillo neón", "neon brillo halo azul botones", SettingsCategory.CONTROLS),
    entry("Ocultar controles táctiles si hay mando conectado", "ocultar overlay mando fisico gamepad conectado", SettingsCategory.CONTROLS),
    entry("Sin overlay en un perfil", "overlay perfil ocultar mando externo", SettingsCategory.CONTROLS),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.CONTROLS),
    // --- Almacenamiento ---
    entry("Copias en Descargas", "descargas copias childoflight borrar", SettingsCategory.STORAGE),
    entry("Limpiar no usados", "limpiar no usados botones perfiles presets dlc saves", SettingsCategory.STORAGE),
    entry("Estado de archivos", "estado archivos firmware guardado instalado juego dlc update app externa", SettingsCategory.STORAGE),
    // --- Diagnóstico ---
    entry("GPU y diagnóstico", "gpu diagnostico prueba 30 segundos benchmark", SettingsCategory.DIAGNOSTICS),
    entry("Cerrar apps en segundo plano antes de jugar", "cerrar apps segundo plano ram liberar", SettingsCategory.DIAGNOSTICS),
    entry("Modelo del dispositivo", "modelo dispositivo telefono resumen diagnostico", SettingsCategory.DIAGNOSTICS),
    entry("Errores recientes del log", "errores recientes log fallos diagnostico", SettingsCategory.DIAGNOSTICS),
    entry("Resumen del dispositivo", "resumen dispositivo soc cpu gpu ram fps nucleo", SettingsCategory.DIAGNOSTICS),
    entry("Resumen de la configuración activa", "resumen configuracion activa perfil backend resolucion filtro fps", SettingsCategory.DIAGNOSTICS),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.DIAGNOSTICS),
    entry("Modo seguro", "modo seguro 1x sdl fallos", SettingsCategory.DIAGNOSTICS),
)
