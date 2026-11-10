package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.resolveLaunchArguments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * argv de SDL_main (R4-P2-14): el extra AppStartParameters manda si
 * existe; si no, {"-r", titleId}; sin Title ID y sin extra, vacío.
 * Puro JVM: la Activity solo lee el Intent y delega aquí.
 */
class EmbeddedGameArgumentsTest {

    @Test
    fun appStartParametersWinWhenPresent() {
        val args = resolveLaunchArguments(arrayOf("-r", "PCSB00598", "--debug"), "PCSB00598")
        assertArrayEquals(arrayOf("-r", "PCSB00598", "--debug"), args)
    }

    @Test
    fun appStartParametersWinEvenWithoutTitleId() {
        // El extra manda tal cual, aunque no haya Title ID: es lo que
        // hace el oficial (devuelve el extra si existe).
        val args = resolveLaunchArguments(arrayOf("--help"), "")
        assertArrayEquals(arrayOf("--help"), args)
    }

    @Test
    fun fallsBackToRunTitleIdWithoutExtra() {
        assertArrayEquals(arrayOf("-r", "PCSB00598"), resolveLaunchArguments(null, "PCSB00598"))
    }

    @Test
    fun emptyExtraFallsBackToRunTitleId() {
        // Un extra presente pero vacío se trata como ausente: arrancar
        // SDL_main sin argumentos es peor que {"-r", titleId}.
        assertArrayEquals(arrayOf("-r", "PCSB00598"), resolveLaunchArguments(emptyArray(), "PCSB00598"))
    }

    @Test
    fun noExtraAndNoTitleIdIsEmpty() {
        assertEquals(0, resolveLaunchArguments(null, "").size)
        assertEquals(0, resolveLaunchArguments(emptyArray(), "").size)
    }

    @Test
    fun returnedArrayIsACopy() {
        // El llamador no debe poder mutar el extra original a través
        // del argv devuelto.
        val extra = arrayOf("-r", "PCSB00598")
        val args = resolveLaunchArguments(extra, "PCSB00598")
        args[1] = "OTRO"
        assertEquals("PCSB00598", extra[1])
    }
}
