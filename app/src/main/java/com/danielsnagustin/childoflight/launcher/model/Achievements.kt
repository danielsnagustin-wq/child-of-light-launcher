package com.danielsnagustin.childoflight.launcher.model

/**
 * Los 27 trofeos de Child of Light en PS Vita, EN ESPAÑOL.
 *
 * Nombres y descripciones oficiales en español, verificados en las
 * listas españolas de PSN/Xbox del juego (2026-11-08):
 * - All Gamers In, "Guía de Trofeos/Logros Child of Light"
 *   (allgamersin.com/guia-trofeos-logros-child-of-light)
 * - EliteGuías, "Logros, Trofeos y Trucos Child of Light"
 *   (eliteguias.com/trucos/c/child-of-light.php)
 * - Zhakey, "Guía de Logros (Child of Light)"
 *   (zhakey.com/2015/04/guia-de-logros-child-of-light.html)
 * - Mundogamers, trofeos PS3/PS4 de Child of Light
 * - Xombit Games, "Logros y trofeos disponibles para Child of Light"
 * Comprobación cruzada en inglés: lista de GameFAQs para la versión
 * Vita (27 trofeos). OJO: la versión PS4 tiene 20; NO confundir.
 * No hay platino en ninguna de las dos listas: no se promete platino
 * en ningún sitio.
 *
 * Los 27 nombres y descripciones son oficiales verificados (2 o más
 * fuentes coinciden en cada uno). Notas: "Furia alada" (19) frente a
 * "Furia aladal" (errata en una sola guía); "Robando protagonismo"
 * (16) frente a "Ladrón de Escena" (lista auto-traducida); "A través
 * del cristal" (18) es el nombre oficial en español del trofeo
 * online, que no existe en la lista de PS4. El nombre en inglés de
 * cada trofeo queda en el comentario de su línea, como referencia.
 *
 * Solo el trofeo 4 (Coronación) tiene grado documentado en la fuente
 * (Plata). Para el resto no se inventa grado (Bronce/Plata):
 * el grado queda como "no especificado en la fuente".
 *
 * Son logros LOCALES del launcher. NO se sincronizan con PSN /
 * PlayStation Network ni con Steam. El progreso vive en DataStore
 * y se puede exportar/importar junto con los saves.
 */
data class Trophy(
    val number: Int, // 1..27
    val name: String,
    val description: String,
    val isSecret: Boolean = false,
    val tier: String? = null, // solo Coronación tiene "Plata" documentado
    /** Requiere función online de Vita (enviar Oculi a un amigo). */
    val onlineRequired: Boolean = false,
)

val TROPHIES_27: List<Trophy> = listOf(
    Trophy(1, "Atrapaestrellas", "Recupera las estrellas.", isSecret = true), // EN: Starcatcher
    Trophy(2, "En la luna", "Recupera la luna.", isSecret = true), // EN: Over the Moon
    Trophy(3, "Amanecer", "Recupera el sol.", isSecret = true), // EN: Sunrise
    Trophy(4, "Coronación", "Destrona a la Reina de la Oscuridad.", isSecret = true, tier = "Plata"), // EN: Coronation
    Trophy(5, "Comedia", "Rubella se une al grupo de Aurora."), // EN: Comedy
    Trophy(6, "La niña y la luciérnaga", "Conoce a Igniculus."), // EN: The Girl and the Firefly
    Trophy(7, "Captura de un Capilli", "Finn se une al grupo de Aurora."), // EN: Capilli Catch
    Trophy(8, "Hermana querida", "Norah se une al grupo de Aurora."), // EN: Sweet Sister
    Trophy(9, "Unión de un Bolmus", "Robert se une al grupo de Aurora."), // EN: Bolmus Bond
    Trophy(10, "La familia Kategida", "Óengus se une al grupo de Aurora."), // EN: Kategida Kindred
    Trophy(11, "Promesa de los piscis", "Gen se une al grupo de Aurora."), // EN: Piscean Promise
    Trophy(12, "Tragedia", "Tristis se une al grupo de Aurora."), // EN: Tragedy
    Trophy(13, "Décimo cumpleaños", "Llega al nivel 10 con Aurora."), // EN: Tenth Birthday
    Trophy(14, "Perfeccionista", "Mejora una habilidad hasta el máximo nivel."), // EN: Perfectionist
    Trophy(15, "Experto oculista", "Crea Oculi."), // EN: Master Oculist
    Trophy(16, "Robando protagonismo", "Realiza 3 golpes sorpresa."), // EN: Scene Stealer
    Trophy(17, "Aguafiestas", "Interrumpe a 3 enemigos al mismo tiempo."), // EN: Showstopper
    Trophy(18, "A través del cristal", "Envía Oculi a un amigo.", onlineRequired = true), // EN: Through the Looking Glass
    Trophy(19, "Furia alada", "Arrasa a 5 enemigos."), // EN: Winged Fury
    Trophy(20, "Explorador", "Encuentra y abre 30 cofres."), // EN: Explorer
    Trophy(21, "Soneto perfecto", "Consigue todas las confesiones."), // EN: Perfect Sonnet
    Trophy(22, "Raíces", "Completa 3 peticiones de lemurianos."), // EN: Grassroots
    Trophy(23, "General de la luz", "Gana un combate contra 3 criaturas oscuras sin perder ningún PV."), // EN: General of Light
    Trophy(24, "Guardianes de Lemuria", "Gana un combate sin Aurora."), // EN: Guardians of Lemuria
    Trophy(25, "Aurora borealis", "Recoge 5 depósitos de brillo."), // EN: Aurora Borealis
    Trophy(26, "Cuando los cerdos vuelen", "Termina la petición de los lemurianos: La odisea de Aquiles."), // EN: When Pigs Fly
    Trophy(27, "La manzana dorada", "Termina la petición de los lemurianos: Librecambismo."), // EN: Golden Apple
)

data class TrophyProgress(
    val trophy: Trophy,
    val unlocked: Boolean = false,
    /** Epoch millis del desbloqueo, null si bloqueado. */
    val unlockedAtMillis: Long? = null,
) {
    /** Nombre visible: los secretos se ocultan hasta desbloquearlos. */
    val displayName: String get() = if (trophy.isSecret && !unlocked) "Trofeo secreto" else trophy.name
    val displayDescription: String
        get() = if (trophy.isSecret && !unlocked) "???" else trophy.description
}

fun trophyProgressPercent(progress: List<TrophyProgress>): Int {
    if (progress.isEmpty()) return 0
    return (progress.count { it.unlocked } * 100) / progress.size
}
