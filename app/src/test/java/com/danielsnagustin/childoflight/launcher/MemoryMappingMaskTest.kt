package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.effectiveMappingMethod
import com.danielsnagustin.childoflight.launcher.model.isSupportedByMask
import com.danielsnagustin.childoflight.launcher.model.maskBit
import com.danielsnagustin.childoflight.launcher.model.supportedMappingMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Máscara de métodos de mapeo de memoria (ronda 0.2.64, P1-3 Mali).
 * Bits VERIFICADOS en el pin (renderer/state.h:51): Disabled=1<<0,
 * DoubleBuffer=1<<1, ExernalHost=1<<2, PageTable=1<<3,
 * NativeBuffer=1<<4. Puro y testeable en JVM.
 */
class MemoryMappingMaskTest {

    @Test
    fun maskBitsMatchPinEnumOrder() {
        assertEquals(1, MemoryMapping.OFF.maskBit())
        assertEquals(2, MemoryMapping.DOUBLE_BUFFER.maskBit())
        assertEquals(4, MemoryMapping.EXTERNAL_HOST.maskBit())
        assertEquals(8, MemoryMapping.PAGE_TABLE.maskBit())
        assertEquals(16, MemoryMapping.NATIVE_BUFFER.maskBit())
    }

    @Test
    fun unknownMaskSupportsEverything() {
        // Máscara 0 = desconocida (sin núcleo): la UI no filtra nada.
        for (method in MemoryMapping.entries) {
            assertTrue(method.isSupportedByMask(0))
            assertEquals(method, effectiveMappingMethod(method, 0))
        }
        assertTrue(supportedMappingMethods(0).isEmpty())
    }

    @Test
    fun unsupportedMethodFallsBackToDoubleBuffer() {
        // Máscara sin Native Buffer (Mali típico): pedirlo cae a
        // Double Buffer, que es el valor inicial del núcleo.
        val mask = MemoryMapping.DOUBLE_BUFFER.maskBit() or
            MemoryMapping.PAGE_TABLE.maskBit() or
            MemoryMapping.EXTERNAL_HOST.maskBit()
        assertFalse(MemoryMapping.NATIVE_BUFFER.isSupportedByMask(mask))
        assertEquals(MemoryMapping.DOUBLE_BUFFER, effectiveMappingMethod(MemoryMapping.NATIVE_BUFFER, mask))
        assertEquals(MemoryMapping.PAGE_TABLE, effectiveMappingMethod(MemoryMapping.PAGE_TABLE, mask))
        assertEquals(
            listOf(MemoryMapping.DOUBLE_BUFFER, MemoryMapping.EXTERNAL_HOST, MemoryMapping.PAGE_TABLE),
            supportedMappingMethods(mask),
        )
    }

    @Test
    fun fullMaskKeepsRequestedMethod() {
        val full = (1 shl 5) - 1 // bits 0..4
        for (method in MemoryMapping.entries) {
            assertTrue(method.isSupportedByMask(full))
            assertEquals(method, effectiveMappingMethod(method, full))
        }
        assertEquals(MemoryMapping.entries.toList(), supportedMappingMethods(full))
    }

    @Test
    fun disabledOnlyMaskFallsBackToDoubleBufferForAnythingElse() {
        // El núcleo sin dispositivos Vulkan devuelve 1<<0 (solo
        // Disabled): cualquier método pedido cae a Double Buffer.
        val mask = 1
        assertTrue(MemoryMapping.OFF.isSupportedByMask(mask))
        assertEquals(MemoryMapping.DOUBLE_BUFFER, effectiveMappingMethod(MemoryMapping.PAGE_TABLE, mask))
        assertEquals(listOf(MemoryMapping.OFF), supportedMappingMethods(mask))
    }
}
