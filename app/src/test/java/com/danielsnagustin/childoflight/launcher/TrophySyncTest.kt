package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.TrophySyncPending
import com.danielsnagustin.childoflight.launcher.data.mapNativeTrophies
import org.vita3k.emulator.data.NativeTrophy
import org.vita3k.emulator.data.NativeTrophyCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests del mapeo del puente JNI de trofeos (Ronda 0.2.57):
// de la lista nativa (nombre en inglés exacto) a números locales
// 1..20. Puro JVM, sin Android ni núcleo.

private fun nativeTrophy(name: String, earned: Boolean, timestampSec: Long = 1_700_000_000L): NativeTrophy {
    return NativeTrophy().apply {
        this.name = name
        this.earned = earned
        this.timestampSec = timestampSec
    }
}

private fun collection(vararg trophies: NativeTrophy): NativeTrophyCollection {
    return NativeTrophyCollection().apply {
        this.trophies = arrayOf(*trophies)
    }
}

class TrophySyncTest {

    @Test
    fun exactNameMapsToLocalNumber() {
        // "Capilli Catch" es el 6 en la lista de 20 (en la antigua de
        // 27 era el 7): el mapeo es por nombre contra la lista de 20.
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Capilli Catch", earned = true, timestampSec = 1_700_000_000L))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1, result.unlocked.size)
        assertEquals(6, result.unlocked[0].number)
        assertEquals(1_700_000_000_000L, result.unlocked[0].unlockedAtMillis)
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun allTwentyNamesMap() {
        val names = listOf(
            "Starcatcher", "Over the Moon", "Sunrise", "Coronation", "Comedy",
            "Capilli Catch", "Sweet Sister", "Bolmus Bond", "Kategida Kindred",
            "Piscean Promise", "Tragedy", "Tenth Birthday", "Perfectionist",
            "Master Oculist", "Scene Stealer", "Showstopper", "Winged Fury",
            "Explorer", "Perfect Sonnet", "Grassroots",
        )
        val result = mapNativeTrophies(
            arrayOf(collection(*names.map { nativeTrophy(it, earned = true) }.toTypedArray())),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals((1..20).toList(), result.unlocked.map { it.number })
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun unknownNameIsSkippedAndLogged() {
        val result = mapNativeTrophies(
            arrayOf(
                collection(
                    nativeTrophy("Through the Looking Glass", earned = true),
                    nativeTrophy("Starcatcher", earned = true),
                ),
            ),
            nowMillis = 1_800_000_000_000L,
        )
        // El trofeo online de la lista de 27 no existe en Vita: se
        // salta sin inventar mapeo; el conocido sí se desbloquea.
        assertEquals(listOf(1), result.unlocked.map { it.number })
        assertEquals(listOf("Through the Looking Glass"), result.skippedUnknownNames)
    }

    @Test
    fun notEarnedDoesNotUnlock() {
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Sunrise", earned = false))),
            nowMillis = 1_800_000_000_000L,
        )
        assertTrue(result.unlocked.isEmpty())
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun zeroTimestampUsesNow() {
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Sunrise", earned = true, timestampSec = 0L))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1, result.unlocked.size)
        assertEquals(3, result.unlocked[0].number)
        assertEquals(1_800_000_000_000L, result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun negativeTimestampUsesNow() {
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Sunrise", earned = true, timestampSec = -5L))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1_800_000_000_000L, result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun duplicateAcrossCollectionsKeepsEarliest() {
        val result = mapNativeTrophies(
            arrayOf(
                collection(nativeTrophy("Sunrise", earned = true, timestampSec = 1_700_000_100L)),
                collection(nativeTrophy("Sunrise", earned = true, timestampSec = 1_700_000_000L)),
            ),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1, result.unlocked.size)
        assertEquals(1_700_000_000_000L, result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun emptyInputMapsToEmpty() {
        val result = mapNativeTrophies(emptyArray(), nowMillis = 1_800_000_000_000L)
        assertTrue(result.unlocked.isEmpty())
        assertTrue(result.skippedUnknownNames.isEmpty())
    }

    @Test
    fun pendingTakeClears() {
        TrophySyncPending.store(arrayOf(collection(nativeTrophy("Sunrise", earned = true))))
        val taken = TrophySyncPending.take()
        assertEquals(1, taken?.size)
        assertNull(TrophySyncPending.take())
    }
}
