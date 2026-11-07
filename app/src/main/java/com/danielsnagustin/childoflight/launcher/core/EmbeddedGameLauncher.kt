package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.model.GameStatus

/**
 * Decisión de por dónde lanzar el juego (Etapa 2).
 *
 * Función PURA (sin Android ni I/O) para poder probarla en unit tests.
 * La usa Vita3KLauncher antes de tocar nada; el lanzamiento real
 * (Activity SDL embebida o intent externo) vive en Vita3KLauncher.
 *
 * Reglas:
 * - Sin juego instalado -> Blocked (falta el juego).
 * - Núcleo embebido disponible + juego EXTRAÍDO + firmware local
 *   -> UseEmbedded: el juego arranca dentro del launcher.
 * - Núcleo disponible + juego solo en .pkg + firmware local
 *   -> UseEmbeddedPkg: primero se instala el .pkg con el propio
 *   núcleo (EmbeddedPkgInstaller) y luego se arranca embebido.
 * - Sin núcleo embebido, o sin firmware local -> UseExternal
 *   (Vita3K externo, Etapa 1). Sin firmware, el núcleo no puede
 *   arrancar, pero Vita3K externo gestiona su propio firmware: se
 *   intenta la vía externa (con su aviso) en vez de bloquear al
 *   usuario. Solo si TAMPOCO está Vita3K externo, Vita3KLauncher
 *   bloquea al final con la frase de firmware.
 *   (El plan original bloqueaba con núcleo presente y sin firmware;
 *   se cambió en la fundación de Etapa 2 porque bloquear pudiendo
 *   jugar por fuera es peor para el usuario. Ver
 *   docs/etapa2/ETAPA2-ESTADO.md.)
 */
object EmbeddedGameLauncher {

    sealed class Decision {
        /** Arrancar el juego extraído con el núcleo embebido. */
        data class UseEmbedded(val titleId: String) : Decision()
        /** Instalar el .pkg con el núcleo y luego arrancar embebido. */
        data class UseEmbeddedPkg(val titleId: String) : Decision()
        /** Lanzar Vita3K externo (Etapa 1). reason es solo para el log. */
        data class UseExternal(val reason: String) : Decision()
        /** No lanzar. reason es el mensaje para el usuario. */
        data class Blocked(val reason: String) : Decision()
    }

    fun decide(status: GameStatus, coreAvailable: Boolean, firmwareReady: Boolean): Decision {
        val titleId = status.titleId?.id
            ?: return Decision.Blocked("No se pudo determinar el Title ID instalado.")
        if (!status.gameInstalled) {
            return Decision.Blocked(
                "El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).",
            )
        }
        if (!coreAvailable) {
            return Decision.UseExternal("Núcleo embebido no presente (Etapa 2).")
        }
        if (!firmwareReady) {
            return Decision.UseExternal("Sin firmware local: se usa la app externa.")
        }
        return when {
            status.gameExtracted -> Decision.UseEmbedded(titleId)
            status.pkgInstalled -> Decision.UseEmbeddedPkg(titleId)
            else -> Decision.Blocked("El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).")
        }
    }
}
