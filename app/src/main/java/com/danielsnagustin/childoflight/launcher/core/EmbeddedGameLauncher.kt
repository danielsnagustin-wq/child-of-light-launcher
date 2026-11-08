package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.GameStatus

/**
 * Decisión de por dónde lanzar el juego (Etapa 2: motor embebido
 * como camino principal).
 *
 * Función PURA (sin Android ni I/O) para poder probarla en unit tests.
 * La usa Vita3KLauncher antes de tocar nada; el lanzamiento real
 * (Activity SDL embebida o intent externo) vive en Vita3KLauncher.
 *
 * Reglas (el motor integrado es el camino normal; la app externa
 * nunca es un respaldo automático):
 * - Sin juego instalado -> Blocked (falta el juego).
 * - Sin Title ID -> Blocked (no se sabe qué arrancar).
 * - Núcleo embebido disponible + juego EXTRAÍDO + firmware local
 *   -> UseEmbedded: el juego arranca dentro del launcher.
 * - Núcleo disponible + juego solo en .pkg + firmware local
 *   -> UseEmbeddedPkg: primero se instala el .pkg con el propio
 *   núcleo (EmbeddedPkgInstaller) y luego se arranca embebido.
 * - Núcleo disponible pero SIN firmware local -> Blocked: el núcleo
 *   no puede arrancar sin firmware y ya no se salta a la app externa
 *   por detrás. El motivo dice dónde importarlo (Ajustes ->
 *   Avanzado -> Firmware).
 * - Sin núcleo embebido -> Blocked, SALVO que el usuario haya
 *   activado la opción avanzada en Ajustes (allowExternal): solo
 *   entonces UseExternal. La opción avanzada no cambia nada cuando
 *   el núcleo SÍ está presente: con núcleo, JUGAR siempre usa el
 *   motor integrado (o bloquea con el motivo exacto si algo falta).
 */
object EmbeddedGameLauncher {

    sealed class Decision {
        /** Arrancar el juego extraído con el núcleo embebido. */
        data class UseEmbedded(val titleId: String) : Decision()
        /** Instalar el .pkg con el núcleo y luego arrancar embebido. */
        data class UseEmbeddedPkg(val titleId: String) : Decision()
        /**
         * Lanzar la app externa. Solo llega aquí si no hay núcleo
         * embebido Y el usuario activó la opción avanzada en Ajustes.
         * reason es solo para el log.
         */
        data class UseExternal(val reason: String) : Decision()
        /** No lanzar. reason es el mensaje para el usuario. */
        data class Blocked(val reason: String) : Decision()
    }

    fun decide(
        status: GameStatus,
        coreAvailable: Boolean,
        firmwareReady: Boolean,
        allowExternal: Boolean = false,
    ): Decision {
        val titleId = status.titleId?.id
            ?: return Decision.Blocked("No se pudo determinar el Title ID instalado.")
        if (!status.gameInstalled) {
            return Decision.Blocked(
                "El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).",
            )
        }
        if (coreAvailable) {
            if (!firmwareReady) {
                return Decision.Blocked(Vita3KLauncher.FIRMWARE_BLOCKED_EMBEDDED)
            }
            return when {
                status.gameExtracted -> Decision.UseEmbedded(titleId)
                status.pkgInstalled -> Decision.UseEmbeddedPkg(titleId)
                else -> Decision.Blocked("El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).")
            }
        }
        // Sin núcleo embebido: la app externa solo se usa si el usuario
        // lo permitió explícitamente en Ajustes → Avanzado. Sin ese
        // permiso, se bloquea con el motivo exacto (reintentar /
        // reportar en el diálogo) en vez de saltar por fuera.
        if (allowExternal) {
            return Decision.UseExternal("Núcleo embebido no presente; opción avanzada activada.")
        }
        return Decision.Blocked(
            "El motor integrado no está disponible en esta versión: no se encontró el núcleo embebido. " +
                "Reinstala el launcher y reintenta; si sigue igual, repórtalo desde aquí.",
        )
    }
}
