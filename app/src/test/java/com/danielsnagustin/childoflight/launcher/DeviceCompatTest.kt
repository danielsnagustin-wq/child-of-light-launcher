package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.DeviceCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compatibilidad de arquitectura: el núcleo embebido solo existe en
 * arm64-v8a. La variante pura (listas como parámetro) es testeable en
 * JVM; la lectura real de Build vive en DeviceCompat.is64BitDevice().
 */
class DeviceCompatTest {

    @Test
    fun arm64Is64Bit() {
        assertTrue(DeviceCompat.is64BitDevice(listOf("arm64-v8a"), listOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun x86_64Is64BitEvenWithout64BitAbisList() {
        assertTrue(DeviceCompat.is64BitDevice(emptyList(), listOf("x86_64", "x86")))
    }

    @Test
    fun armv7OnlyIs32Bit() {
        assertFalse(DeviceCompat.is64BitDevice(emptyList(), listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun emptyAbisIsNot64Bit() {
        assertFalse(DeviceCompat.is64BitDevice(emptyList(), emptyList()))
    }
}
