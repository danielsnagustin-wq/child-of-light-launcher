package com.danielsnagustin.childoflight.launcher.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import org.libsdl.app.SDLActivity

/**
 * Activity del juego con el núcleo Vita3K EMBEBIDO (Etapa 2).
 *
 * Es la SDLActivity que Vita3K espera, adaptada a este paquete
 * (modelo EmuCoreV; contrato verificado contra Vita3K upstream
 * master 3e43bc80, 2026-11-07):
 *
 * - Extiende org.libsdl.app.SDLActivity (pegamento Java de SDL3
 *   vendorizado en app/src/main/java/org/libsdl/app/, commit
 *   fa2c02b = SDL 3.4.16, el fijado por Vita3K).
 * - getLibraries() -> {"vita3k"}: SDL carga libvita3k.so (el .so
 *   del núcleo, compilado desde el árbol Vita3K en CI). SDL3 llega
 *   por DT_NEEDED de libvita3k.so. TODO-VERIFY en la primera build:
 *   si la carga falla con UnsatisfiedLinkError, añadir "SDL3".
 * - getArguments() -> {"-r", titleId}: es EXACTAMENTE lo que
 *   SDL_main (vita3k/android/jni/main_android.cpp) espera para
 *   arrancar el juego con AppLaunchRequest{.app_path = titleId}.
 * - onCreate: prepareFrontend() ANTES de super.onCreate (igual que
 *   upstream, que lo llama desde MainActivity antes de Emulator) y
 *   luego init(storagePath) DESPUÉS de super.onCreate y ANTES de
 *   que se cree la superficie (el hilo SDL no arranca hasta que la
 *   superficie está lista + resumed, así que init llega a tiempo;
 *   es el mismo orden que Emulator.ensureNativeSessionInitialized()).
 *
 * MÉTODOS QUE EL NATIVO LLAMA POR NOMBRE (main_android.cpp):
 * el hilo SDL busca estos métodos en la Activity con GetMethodID.
 * Si falta getNativeDisplayRotation, SDL_main SE CIERRA (no hay
 * comprobación de null en get_display_rotation()). Por eso existen
 * aquí con ESTAS firmas exactas, aunque el launcher aún no tenga
 * menú de pausa:
 * - setCurrentGameId(String): el núcleo avisa qué juego arrancó.
 * - openPauseMenuFromController(): botón guía del mando. Hoy solo
 *   pausa/reanuda el juego (el menú de pausa con UI es trabajo
 *   futuro); nunca cierra el juego.
 * - getNativeDisplayRotation(): Int: rotación de la pantalla
 *   (0/90/180/270) para el motion del núcleo.
 *
 * SIN núcleo embebido esta Activity no se lanza nunca (el launcher
 * decide con EmbeddedGameLauncher y Vita3KLauncher hace fallback a
 * Vita3K externo). Si init falla aquí, se cierra con un mensaje y
 * el usuario vuelve al launcher (no se finge que el juego arrancó).
 *
 * TODO (Etapa 2, tras la primera build que arranque el juego):
 * - Overlay táctil: el layout táctil del launcher (Compose) y la
 *   vista SDL son dos mundos. El overlay nativo de Vita3K
 *   (input_overlay.cpp) se configura por config.yml; integrar el
 *   layout del launcher con él es el mayor trabajo restante de
 *   integración. Mientras tanto, el juego se juega con mando
 *   físico (SDL lo detecta solo) o con el overlay nativo.
 * - Menú de pausa con UI (reanudar / salir / ajustes).
 * - Relanzamiento en proceso (requestAppRelaunch) para cambiar de
 *   Title ID sin recrear la Activity.
 */
class EmbeddedGameActivity : SDLActivity() {

    companion object {
        const val EXTRA_TITLE_ID = "title_id"
        const val EXTRA_GAME_TITLE = "game_title"

        fun createLaunchIntent(context: Context, titleId: String, gameTitle: String): Intent {
            return Intent(context, EmbeddedGameActivity::class.java).apply {
                putExtra(EXTRA_TITLE_ID, titleId)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    private var currentTitleId: String = ""
    private var pausedByGuideButton: Boolean = false

    /** SDL carga solo libvita3k.so; SDL3 llega por DT_NEEDED. */
    override fun getLibraries(): Array<String> {
        return arrayOf("vita3k")
    }

    /** argv de SDL_main: {"-r", titleId}. Sin Title ID, SDL_main devuelve -1. */
    override fun getArguments(): Array<String> {
        val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        if (titleId.isEmpty()) return emptyArray()
        return arrayOf("-r", titleId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // prepareFrontend (SDL_SetMainReady + metadatos + cámara) ANTES
        // de que SDLActivity toque nada nativo. Best-effort: si falla,
        // init() lo detectará y se cerrará con mensaje.
        runCatching { Vita3kCoreBridge.prepareFrontend() }
        super.onCreate(savedInstanceState)

        val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        if (titleId.isEmpty()) {
            Toast.makeText(this, "Sin Title ID: no se puede arrancar el juego.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentTitleId = titleId
        val storageRoot = EmbeddedCoreLayout.storageRoot(this)
        val rc = Vita3kCoreBridge.init(storageRoot.absolutePath)
        if (rc != Vita3kCoreBridge.OK) {
            Toast.makeText(
                this,
                "El núcleo embebido no se pudo iniciar (código $rc). Vuelve al launcher e inténtalo con Vita3K externo.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        val newTitleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        if (newTitleId.isEmpty() || newTitleId == currentTitleId) {
            // Mismo juego (o sin Title ID) y partida en curso: ignorar.
            return
        }
        // Otro Title ID: la forma segura sin relanzamiento en proceso
        // es cerrar y volver a abrir con el nuevo intent.
        finish()
        startActivity(createLaunchIntent(this, newTitleId, intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()))
    }

    /**
     * Lo llama el núcleo al arrancar el juego (hook on_launch).
     * Firma exacta exigida por main_android.cpp: (Ljava/lang/String;)V.
     */
    fun setCurrentGameId(gameId: String?) {
        runOnUiThread {
            currentTitleId = gameId.orEmpty()
        }
    }

    /**
     * Botón guía del mando (hook on_guide_button). Firma exacta:
     * ()V. Hoy pausa/reanuda; el menú de pausa con UI es futuro.
     */
    fun openPauseMenuFromController() {
        runOnUiThread {
            val paused = Vita3kCoreBridge.isAppPaused()
            val ok = Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_USER,
                !paused,
            )
            if (ok) {
                pausedByGuideButton = !paused
                Toast.makeText(
                    this,
                    if (!paused) "Juego en pausa (pulsa el botón guía para seguir)" else "Juego reanudado",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Rotación de la pantalla para el motion del núcleo (hook on_init).
     * Firma exacta exigida por main_android.cpp: ()I. Sin este método
     * SDL_main se cierra: get_display_rotation() no comprueba null.
     */
    @Suppress("DEPRECATION")
    fun getNativeDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    override fun onDestroy() {
        // Si la partida sigue en curso al salir, pedir cierre limpio.
        runCatching {
            if (Vita3kCoreBridge.isAppRunning()) {
                Vita3kCoreBridge.requestAppQuit()
            }
        }
        super.onDestroy()
    }
}
