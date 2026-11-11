package com.danielsnagustin.childoflight.launcher.model

/**
 * Los 20 trofeos de Child of Light en PS Vita, EN ESPAÑOL.
 *
 * La lista correcta es de 20, no de 27 (corrección 0.2.57): la lista
 * anterior de 27 mezclaba los trofeos de Vita con logros extra de
 * Xbox One / PC. Verificado en 3 fuentes (2026-11-10):
 * - TrueTrophies, "Full list of all 20 Child of Light trophies -
 *   19 bronze and 1 silver" (versión Vita): 19 Bronce y 1 Plata
 *   (Coronación, nº 4). No hay platino.
 * - EliteGuías, "Logros, Trofeos y Trucos Child of Light"
 *   (eliteguias.com/trucos/c/child-of-light.php): los 20 nombres
 *   en español con sus descripciones.
 * - AllGamersIn, "Guía de Trofeos/Logros Child of Light": "Trofeos
 *   / Logros Offline: 20" y "En su versión Xbox One hay tres logros
 *   más, uno de ellos con funciones online".
 *
 * Nombres y descripciones oficiales en español. Notas: "Furia
 * alada" (17) frente a "Furia aladal" (errata en AllGamersIn);
 * "Robando protagonismo" (15) frente a "Ladrón de Escena" (lista
 * auto-traducida). El nombre en inglés de cada trofeo va en el
 * campo englishName (lo usa el puente JNI para mapear por nombre
 * contra el TROPHY.TRP del núcleo); nunca se muestra como nombre
 * visible en ninguna superficie (lista, diálogo, popup, log).
 *
 * Grados: 19 Bronce y 1 Plata (Coronación), según TrueTrophies.
 * Ningún trofeo Vita requiere online: onlineRequired es false en
 * los 20. El trofeo online "A través del cristal" (enviar Oculi a
 * un amigo) solo existe en Xbox One / PC, con servidores cerrados.
 *
 * Eliminados de la lista vieja de 27 (ninguno está en la lista
 * oficial Vita de 20 de TrueTrophies):
 * - 6 La niña y la luciérnaga (The Girl and the Firefly): no está
 *   en la lista Vita; "Conoce a Igniculus" no es un trofeo Vita.
 * - 18 A través del cristal (Through the Looking Glass): online
 *   solo de Xbox One / PC, servidores cerrados.
 * - 23 General de la luz (General of Light): no está en Vita.
 * - 24 Guardianes de Lemuria (Guardians of Lemuria): no está
 *   en Vita.
 * - 25 Aurora borealis (Aurora Borealis): no está en Vita.
 * - 26 Cuando los cerdos vuelen (When Pigs Fly): petición
 *   lemuriana extra de Xbox One.
 * - 27 La manzana dorada (Golden Apple): petición lemuriana
 *   extra de Xbox One.
 *
 * Son logros LOCALES del launcher. NO se sincronizan con PSN /
 * PlayStation Network ni con Steam. El progreso vive en DataStore
 * y se puede exportar/importar junto con los saves.
 */
data class Trophy(
    val number: Int, // 1..20
    val name: String,
    val description: String,
    /** Nombre en inglés: solo para mapear por nombre contra el TROPHY.TRP. Nunca visible. */
    val englishName: String = "",
    val isSecret: Boolean = false,
    val tier: String? = null, // "Bronce" en 19, "Plata" solo en Coronación (nº 4)
    /** Requiere función online de Vita. En la lista Vita de 20: siempre false. */
    val onlineRequired: Boolean = false,
)

val TROPHIES_20: List<Trophy> = listOf(
    Trophy(1, "Atrapaestrellas", "Recupera las estrellas.", "Starcatcher", isSecret = true, tier = "Bronce"),
    Trophy(2, "En la luna", "Recupera la luna.", "Over the Moon", isSecret = true, tier = "Bronce"),
    Trophy(3, "Amanecer", "Recupera el sol.", "Sunrise", isSecret = true, tier = "Bronce"),
    Trophy(4, "Coronación", "Destrona a la Reina de la Oscuridad.", "Coronation", isSecret = true, tier = "Plata"),
    Trophy(5, "Comedia", "Rubella se une al grupo de Aurora.", "Comedy", tier = "Bronce"),
    Trophy(6, "Captura de un Capilli", "Finn se une al grupo de Aurora.", "Capilli Catch", tier = "Bronce"),
    Trophy(7, "Hermana querida", "Norah se une al grupo de Aurora.", "Sweet Sister", tier = "Bronce"),
    Trophy(8, "Unión de un Bolmus", "Robert se une al grupo de Aurora.", "Bolmus Bond", tier = "Bronce"),
    Trophy(9, "La familia Kategida", "Óengus se une al grupo de Aurora.", "Kategida Kindred", tier = "Bronce"),
    Trophy(10, "Promesa de los piscis", "Gen se une al grupo de Aurora.", "Piscean Promise", tier = "Bronce"),
    Trophy(11, "Tragedia", "Tristis se une al grupo de Aurora.", "Tragedy", tier = "Bronce"),
    Trophy(12, "Décimo cumpleaños", "Llega al nivel 10 con Aurora.", "Tenth Birthday", tier = "Bronce"),
    Trophy(13, "Perfeccionista", "Mejora una habilidad hasta el máximo nivel.", "Perfectionist", tier = "Bronce"),
    Trophy(14, "Experto oculista", "Crea Oculi.", "Master Oculist", tier = "Bronce"),
    Trophy(15, "Robando protagonismo", "Realiza 3 golpes sorpresa.", "Scene Stealer", tier = "Bronce"),
    Trophy(16, "Aguafiestas", "Interrumpe a 3 enemigos al mismo tiempo.", "Showstopper", tier = "Bronce"),
    Trophy(17, "Furia alada", "Arrasa a 5 enemigos.", "Winged Fury", tier = "Bronce"),
    Trophy(18, "Explorador", "Encuentra y abre 30 cofres.", "Explorer", tier = "Bronce"),
    Trophy(19, "Soneto perfecto", "Consigue todas las confesiones.", "Perfect Sonnet", tier = "Bronce"),
    Trophy(20, "Raíces", "Completa 3 peticiones de lemurianos.", "Grassroots", tier = "Bronce"),
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
