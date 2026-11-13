package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.ui.Screen

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
 * "diagnostico" encuentra "Diagnóstico", "60 fps" encuentra el modo
 * 60 FPS y "60fps" también (concatenación de palabras enteras seguidas).
 * La coincidencia es por límite de palabra, no por subcadena cruda
 * (R6-P2-11): "pup" no encuentra "Presets por GPU" ni "psn" "Resumen
 * del dispositivo". Una consulta vacía no devuelve nada: el hub muestra
 * entonces las 9 categorías.
 */
/**
 * Una entrada del buscador. La categoría es el grupo de ajustes al que
 * pertenece (participa en la coincidencia y en el "En:" del resultado).
 * `destination` (P2-12) es solo para destinos que NO son una categoría
 * de ajustes — hoy solo la pantalla de Logros, que vive en el Inicio—:
 * al tocar el resultado se navega ahí en vez de a la categoría.
 */
data class SearchableSetting(
    val title: String,
    val keywords: String,
    val category: SettingsCategory,
    val destination: Screen? = null,
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

/**
 * R6-P2-11: true si [word] coincide con el haystack por LÍMITE DE
 * PALABRA: es una palabra entera del haystack, o la concatenación de
 * varias palabras enteras seguidas ("60fps" = "60" + "FPS", R2-P2-21d).
 * La subcadena cruda sobre el texto compactado producía falsos
 * positivos entre palabras ("pup" en "gpu"+"preset" de "Presets por
 * GPU"; "psn" en "fps"+"nucleo" de "Resumen del dispositivo") y
 * dentro de palabra ("app" en "mapping", "externa" en "external" de
 * "Memory Mapping"). Pura, testeable en JVM (SettingsSearchTest).
 */
internal fun matchesWordBoundary(word: String, haystackWords: List<String>): Boolean {
    if (word in haystackWords) return true
    for (start in haystackWords.indices) {
        var joined = ""
        for (end in start until haystackWords.size) {
            joined += haystackWords[end]
            if (joined == word) return true
            if (joined.length > word.length) break
        }
    }
    return false
}

fun searchSettings(
    query: String,
    index: List<SearchableSetting> = SETTINGS_SEARCH_INDEX,
    // R2-P2-21a / R2-P2-28: con GPU conocida y no-Adreno, la entrada
    // "Instalar Turnip" no se ofrece: en Mali no hay dónde instalarlo
    // (la pantalla GPU no muestra el botón). Sin GPU detectada (null)
    // se conserva: aún no se sabe si es Adreno.
    gpuVendor: GpuVendor? = null,
): List<SearchableSetting> {
    val words = normalizeSearchText(query)
        .split(' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return index
        .filter { entry -> gpuVendor == null || gpuVendor == GpuVendor.ADRENO || entry.title != "Instalar Turnip" }
        .filter { entry ->
            val haystackWords = normalizeSearchText(
                entry.title + " " + entry.keywords + " " + entry.category.title,
            ).split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            // R6-P2-11: por límite de palabra (ver matchesWordBoundary),
            // no por subcadena cruda sobre el texto compactado.
            words.all { matchesWordBoundary(it, haystackWords) }
        }
}

private fun entry(title: String, keywords: String, category: SettingsCategory, destination: Screen? = null) =
    SearchableSetting(title, keywords, category, destination)

/**
 * Registro de entradas buscables. Cubre TODOS los controles que
 * existían en la pantalla única de Ajustes antes de la Ola 2, más los
 * añadidos por la Ola 2 (caché de texturas, deshacer Corregir audio).
 * Si un ajuste no está aquí, el buscador no lo encuentra: al añadir un
 * ajuste nuevo hay que registrarlo aquí también.
 *
 * R2-P2-21c: el KDoc prometía además una entrada "restaurar valores
 * recomendados" que no existe (el restablecido vive en General como
 * "Restablecer ajustes" y en cada categoría como "Restablecer esta
 * categoría"): se quitó la promesa, no se inventó la entrada.
 */
val SETTINGS_SEARCH_INDEX: List<SearchableSetting> = listOf(
    // --- General ---
    entry("Perfil activo", "perfil rendimiento gpu preset activo", SettingsCategory.GENERAL),
    entry("Presets por GPU", "preset g99 mali adreno calidad rendimiento recomendado", SettingsCategory.GENERAL),
    // P2-12: el buscador vuelve a encontrar "logros": la pantalla de
    // Logros no es una categoría de ajustes, así que la entrada lleva
    // destino propio (abre Logros, no General).
    entry("Logros", "logros trofeos", SettingsCategory.GENERAL, Screen.ACHIEVEMENTS),
    // P2-13: "Sesiones y guardados" vive en Almacenamiento, no en
    // General (el plan de dedup lo decía así).
    entry("Sesiones y guardados", "sesiones partidas guardados saves", SettingsCategory.STORAGE),
    entry("Controles táctiles", "controles tactiles editor botones mando", SettingsCategory.GENERAL),
    entry("Permitir app externa", "motor app externa casos especiales", SettingsCategory.GENERAL),
    entry("Sesión PSN (simulada)", "psn sesion simulada dlc activacion", SettingsCategory.GENERAL),
    entry("Idioma del juego", "idioma juego espanol ingles frances aleman italiano", SettingsCategory.GENERAL),
    entry("Aviso legal", "legal ubisoft sony licencia", SettingsCategory.GENERAL),
    entry("Ver config.yml generado", "config yml ver generado yaml", SettingsCategory.GENERAL),
    entry("Restablecer ajustes", "restablecer reset preset gpu defecto", SettingsCategory.GENERAL),
    entry("Borrar progreso de logros", "borrar trofeos logros progreso eliminar", SettingsCategory.GENERAL),
    entry("Copia de seguridad de ajustes", "copia seguridad backup exportar importar json ajustes", SettingsCategory.GENERAL),
    // R5-A2-9: la entrada "Restablecer esta categoría" → GENERAL se
    // quitó: General no tiene ajustes de preset que restablecer
    // (categoryHasResettableSettings GENERAL = false) y el botón
    // llevaba a un no-op con diálogo. Las demás categorías conservan
    // la suya: su reset sí hace algo.
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
    // P2-21: el botón "Descartar cambios" existe en la pantalla y
    // buscarlo solo encontraba "Aplicar personalización" (su entrada
    // lleva "descartar" en las palabras clave). Entrada propia.
    entry("Descartar cambios", "descartar cambios personalizacion deshacer borrador", SettingsCategory.PERSONALIZATION),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.PERSONALIZATION),
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
    entry("Driver GPU", "driver gpu turnip sistema adreno mali personalizado", SettingsCategory.GPU),
    entry("Modo turbo (Adreno)", "turbo adreno rendimiento maximo driver", SettingsCategory.GPU),
    // R6-P2-11: "Driver GPU" + "Driver personalizado" eran dos entradas
    // para el mismo control (los chips de la pantalla GPU): fusionadas
    // en una sola (el nombre "personalizado" sigue en las claves, así
    // que buscarlo encuentra esta entrada).
    entry("Instalar Turnip", "turnip instalar cambiar zip driver mesa", SettingsCategory.GPU),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.GPU),
    // --- CPU ---
    entry("Optimización CPU (Dynarmic)", "cpu optimizacion dynarmic", SettingsCategory.CPU),
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
    // Ronda 0.2.68 (load-p1): la carga rápida es el interruptor nuevo
    // de Rendimiento; sin esta entrada el buscador no la encontraba.
    entry("Carga rápida (sin precompilar shaders)", "carga rapida precompilar shaders arranque negro espera", SettingsCategory.PERFORMANCE),
    entry("Limpiar caché de shaders", "limpiar cache shaders glitches stutter", SettingsCategory.PERFORMANCE),
    entry("Caché de texturas sin hash", "hashless texturas experimental hash", SettingsCategory.PERFORMANCE),
    entry("Mostrar overlay de FPS", "overlay fps hud mostrar rendimiento", SettingsCategory.PERFORMANCE),
    entry("Detalle del overlay de FPS", "overlay fps detalle minimo bajo medio maximo", SettingsCategory.PERFORMANCE),
    entry("Posición del overlay de FPS", "overlay fps posicion arriba abajo izquierda derecha", SettingsCategory.PERFORMANCE),
    entry("Mostrar mensajes de compilación de shaders", "mensajes compilacion shaders avisos", SettingsCategory.PERFORMANCE),
    // Ronda 0.2.66: parches experimentales (sección en Rendimiento).
    entry("Audio SDL experimental", "audio sdl experimental parche underrun chasquidos latencia retraso", SettingsCategory.PERFORMANCE),
    entry("Volver a 1x si baja de 28 fps", "volver 1x 28 fps automatico calidad 2x", SettingsCategory.PERFORMANCE),
    entry("Modo rendimiento del launcher", "modo rendimiento pantalla encendida", SettingsCategory.PERFORMANCE),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.PERFORMANCE),
    // --- Controles y HUD ---
    // R6 (evidencia del teléfono, 0.2.55): el editor táctil no se
    // encontraba: solo se abría desde General → Secciones. Ahora
    // tiene botón propio en Controles y HUD ("Personalizar botones")
    // y esta entrada del buscador lleva ahí.
    entry("Personalizar botones", "personalizar editar botones tactiles layout editor mover tamano reasignar borrar", SettingsCategory.CONTROLS),
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
    // R6-P2-11: el firmware se importa desde Importar, no desde
    // Ajustes: buscar "firmware", "importar firmware" o "pup" debe
    // encontrarlo (antes devolvía 0 o solo "Estado de archivos").
    // Destino propio (pantalla Importar), como Logros.
    entry("Importar firmware", "importar firmware pup instalar psvupdat actualizacion version nucleo", SettingsCategory.STORAGE, Screen.IMPORT),
    // --- Diagnóstico ---
    entry("GPU y diagnóstico", "gpu diagnostico prueba 30 segundos benchmark", SettingsCategory.DIAGNOSTICS),
    entry("Cerrar apps en segundo plano antes de jugar", "cerrar apps segundo plano ram liberar", SettingsCategory.DIAGNOSTICS),
    entry("Modelo del dispositivo", "modelo dispositivo telefono resumen diagnostico", SettingsCategory.DIAGNOSTICS),
    entry("Errores recientes del log", "errores recientes log fallos diagnostico", SettingsCategory.DIAGNOSTICS),
    entry("Resumen del dispositivo", "resumen dispositivo soc cpu gpu ram fps nucleo", SettingsCategory.DIAGNOSTICS),
    // R2-P2-21b: el título es el de la pantalla real de Diagnóstico
    // ("Configuración efectiva"), no "Resumen de la configuración
    // activa" (ese es el título de sección de GPU y diagnóstico).
    entry("Configuración efectiva", "resumen configuracion activa perfil backend resolucion filtro fps", SettingsCategory.DIAGNOSTICS),
    entry("Restablecer esta categoría", "restablecer reset categoria valores preset", SettingsCategory.DIAGNOSTICS),
    entry("Modo seguro", "modo seguro 1x sdl fallos", SettingsCategory.DIAGNOSTICS),
)
