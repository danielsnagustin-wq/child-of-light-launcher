package com.danielsnagustin.childoflight.launcher.model

/**
 * Los 27 trofeos de Child of Light en PS Vita.
 *
 * Fuente: lista de GameFAQs para la versión Vita (27 trofeos).
 * OJO: la versión PS4 tiene 20; NO confundir. No hay platino en
 * ninguna de las dos listas: no se promete platino en ningún sitio.
 *
 * Solo el trofeo 4 (Coronation) tiene grado documentado en la fuente
 * del usuario (Silver). Para el resto no se inventa grado (Bronze/Silver):
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
    val tier: String? = null, // solo Coronation tiene "Silver" documentado
    /** Requiere función online de Vita (enviar Oculi a un amigo). */
    val onlineRequired: Boolean = false,
)

val TROPHIES_27: List<Trophy> = listOf(
    Trophy(1, "Starcatcher", "Recover the stars.", isSecret = true),
    Trophy(2, "Over the Moon", "Recover the moon.", isSecret = true),
    Trophy(3, "Sunrise", "Recover the sun.", isSecret = true),
    Trophy(4, "Coronation", "Dethrone the Queen of the Night.", isSecret = true, tier = "Silver"),
    Trophy(5, "Comedy", "Rubella joins Aurora's Party."),
    Trophy(6, "The Girl and the Firefly", "Meet Igniculus."),
    Trophy(7, "Capilli Catch", "Finn joins Aurora's Party."),
    Trophy(8, "Sweet Sister", "Norah joins Aurora's Party."),
    Trophy(9, "Bolmus Bond", "Robert joins Aurora's Party."),
    Trophy(10, "Kategida Kindred", "Óengus joins Aurora's Party."),
    Trophy(11, "Piscean Promise", "Gen joins Aurora's Party."),
    Trophy(12, "Tragedy", "Tristis joins Aurora's Party."),
    Trophy(13, "Tenth Birthday", "Achieve level 10 with Aurora."),
    Trophy(14, "Perfectionist", "Upgrade a skill to its highest level."),
    Trophy(15, "Master Oculist", "Craft Oculi."),
    Trophy(16, "Scene Stealer", "Execute 3 Surprise Strikes."),
    Trophy(17, "Showstopper", "Interrupt 3 foes at the same time."),
    Trophy(18, "Through the Looking Glass", "Send Oculi to a friend.", onlineRequired = true),
    Trophy(19, "Winged Fury", "Devastate 5 foes."),
    Trophy(20, "Explorer", "Find and open 30 coffers."),
    Trophy(21, "Perfect Sonnet", "Collect all Confessions."),
    Trophy(22, "Grassroots", "Finish 3 Lemurian Requests."),
    Trophy(23, "General of Light", "Win a battle against 3 dark creatures without losing any HP."),
    Trophy(24, "Guardians of Lemuria", "Win a battle without Aurora."),
    Trophy(25, "Aurora Borealis", "Collect 5 deposits of Stardust."),
    Trophy(26, "When Pigs Fly", "Finish the Lemurian Request: Achilles's Odyssey."),
    Trophy(27, "Golden Apple", "Finish the Lemurian Request: Free Trade."),
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
