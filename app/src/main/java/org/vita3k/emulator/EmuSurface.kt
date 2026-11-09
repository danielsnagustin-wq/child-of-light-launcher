package org.vita3k.emulator

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import org.libsdl.app.SDLSurface

/**
 * Superficie SDL del juego, espejo mínimo de la clase oficial
 * `org.vita3k.emulator.EmuSurface` (Vita3K 0.2.1).
 *
 * CAUSA RAÍZ DE LA PANTALLA NEGRA (REA 2026-11-09, verificado en el
 * pin 3e43bc80): el renderizador Vulkan del núcleo tiene un
 * interruptor `has_surface` (renderer/src/vulkan/screen_renderer.cpp:47)
 * que solo se enciende con la JNI
 * `Java_org_vita3k_emulator_EmuSurface_setSurfaceStatus` (:50). Sin
 * ella, `FrameHost::drawable_size()` devuelve {0, 0} y
 * `acquire_swapchain_image()` devuelve false en silencio en cada
 * frame: el juego corre pero no se presenta nada. La app oficial
 * enciende el interruptor desde `EmuSurface.surfaceCreated`; nuestro
 * launcher usaba la `SDLSurface` plana, que no lo toca.
 *
 * El nombre JNI exige este paquete y este nombre de clase EXACTOS;
 * la clase no es visible para el usuario (ningún string de UI), así
 * que no viola la regla de no mencionar Vita3K en la app. El símbolo
 * ya está en nuestro libVita3K.so (screen_renderer.cpp se compila
 * en nuestro CI desde el mismo pin); solo faltaba llamarlo. Si el
 * símbolo no estuviera, el fallo se registra y la Activity no se
 * tumba (el juego quedaría en negro, como antes).
 */
class EmuSurface(context: Context) : SDLSurface(context) {

    private external fun setSurfaceStatus(present: Boolean)

    override fun surfaceCreated(holder: SurfaceHolder) {
        runCatching { setSurfaceStatus(true) }
            .onFailure { Log.w("EmuSurface", "setSurfaceStatus(true) falló: $it") }
        super.surfaceCreated(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { setSurfaceStatus(false) }
            .onFailure { Log.w("EmuSurface", "setSurfaceStatus(false) falló: $it") }
        super.surfaceDestroyed(holder)
    }
}
