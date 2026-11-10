package com.danielsnagustin.childoflight.launcher.core

/**
 * Contador de referencias por botón SDL del mando virtual (R6-P1-14).
 *
 * Problema: cada onButton(sdl, false) ponía el botón virtual a falso
 * sin contar cuántos controles lo mantenían. El botón virtual SDL es
 * un booleano (input_overlay.cpp: SDL_SetJoystickVirtualButton), así
 * que soltar un control apagaba el botón que otro seguía manteniendo:
 * brillo de Igniculus (SDL 9) + botón del editor asignado a L1
 * (también 9), brillo (eje -4) + botón asignado a L2 (también -4), o
 * dos botones del editor mapeados al mismo VitaButton (el editor lo
 * permite). El estado pulsado vivía por vista (pressed, pointerId),
 * nunca por botón SDL.
 *
 * Uso: EmbeddedGameActivity.forwardOverlayButton es el punto único de
 * paso (todos los onButton del overlay llegan ahí). Al pulsar, solo se
 * envía al núcleo si [press] devuelve true (primer mantenedor); al
 * soltar, solo si [release] devuelve true (último mantenedor). Los
 * ejes negativos -4/-5 (L2/R2) pasan por el mismo camino onButton, así
 * que el mismo mapa los cubre: la clave es el valor SDL tal cual.
 *
 * El contador cuenta INTENCIÓN (quién mantiene), no entregas con
 * éxito: si un envío falla y se pierde, el contador sigue reflejando
 * los controles que creen mantener el botón, así que la suelta
 * posterior sigue enviando su false. [reset] se llama al soltar el
 * mando virtual (detach): el joystick destruido pierde todo el estado
 * y un contador viejo dejaría botones sin enviar en el mando nuevo.
 *
 * Kotlin puro, sin Android: testeable en JVM (SdlButtonRefCounterTest).
 */
class SdlButtonRefCounter {

    private val holders = mutableMapOf<Int, Int>()

    /**
     * Un control empieza a mantener [sdlButton]. Devuelve true solo si
     * es el primer mantenedor (hay que enviar el true al núcleo); los
     * siguientes mantenedores no reenvían nada.
     */
    fun press(sdlButton: Int): Boolean {
        val count = (holders[sdlButton] ?: 0) + 1
        holders[sdlButton] = count
        return count == 1
    }

    /**
     * Un control deja de mantener [sdlButton]. Devuelve true solo si
     * era el último mantenedor (hay que enviar el false al núcleo).
     * Una suelta sin mantenedor (p. ej. un release duplicado tras un
     * reset) devuelve false y no envía nada.
     */
    fun release(sdlButton: Int): Boolean {
        val count = holders[sdlButton] ?: return false
        if (count <= 1) {
            holders.remove(sdlButton)
            return true
        }
        holders[sdlButton] = count - 1
        return false
    }

    /** Cuántos controles mantienen [sdlButton] ahora mismo. Solo para tests y diagnóstico. */
    fun heldCount(sdlButton: Int): Int = holders[sdlButton] ?: 0

    /** Olvida todos los mantenedores (mando virtual destruido). */
    fun reset() {
        holders.clear()
    }
}
