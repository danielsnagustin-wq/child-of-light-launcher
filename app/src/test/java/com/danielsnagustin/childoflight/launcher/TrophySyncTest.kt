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
    fun zeroTimestampIsUnknownDate() {
        // 7-P2-4: timestampSec == 0 con earned == true NO se sustituye
        // por la fecha de la sync: la fecha queda desconocida (null).
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Sunrise", earned = true, timestampSec = 0L))),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1, result.unlocked.size)
        assertEquals(3, result.unlocked[0].number)
        assertNull(result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun negativeTimestampIsUnknownDate() {
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Sunrise", earned = true, timestampSec = -5L))),
            nowMillis = 1_800_000_000_000L,
        )
        assertNull(result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun unknownDateMergeKeepsKnownInstant() {
        // Dos colecciones con el mismo trofeo: una sin fecha y otra
        // con fecha real -> la entrada conserva la fecha real.
        val result = mapNativeTrophies(
            arrayOf(
                collection(nativeTrophy("Sunrise", earned = true, timestampSec = 0L)),
                collection(nativeTrophy("Sunrise", earned = true, timestampSec = 1_700_000_000L)),
            ),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1, result.unlocked.size)
        assertEquals(1_700_000_000_000L, result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun allUnknownDatesStayUnknown() {
        val result = mapNativeTrophies(
            arrayOf(
                collection(nativeTrophy("Sunrise", earned = true, timestampSec = 0L)),
                collection(nativeTrophy("Sunrise", earned = true, timestampSec = -1L)),
            ),
            nowMillis = 1_800_000_000_000L,
        )
        assertEquals(1, result.unlocked.size)
        assertNull(result.unlocked[0].unlockedAtMillis)
    }

    @Test
    fun wrongNpComIdCollectionIsDiscarded() {
        // 7-P2-2: con el npComId esperado conocido, una colección de
        // otro juego se descarta entera aunque traiga un nombre
        // homónimo ("Explorer" es el nº 18 local).
        val other = NativeTrophyCollection().apply {
            npComId = "NPWR99999_00"
            totalCount = 20
            trophies = arrayOf(nativeTrophy("Explorer", earned = true))
        }
        val result = mapNativeTrophies(
            arrayOf(other),
            nowMillis = 1_800_000_000_000L,
            expectedNpComId = "NPWR06875_00",
        )
        assertTrue(result.unlocked.isEmpty())
        assertEquals(listOf("NPWR99999_00"), result.skippedCollections)
    }

    @Test
    fun matchingNpComIdIsKept() {
        val own = NativeTrophyCollection().apply {
            npComId = "NPWR06875_00"
            totalCount = 20
            trophies = arrayOf(nativeTrophy("Explorer", earned = true))
        }
        val result = mapNativeTrophies(
            arrayOf(own),
            nowMillis = 1_800_000_000_000L,
            expectedNpComId = "NPWR06875_00",
        )
        assertEquals(listOf(18), result.unlocked.map { it.number })
        assertTrue(result.skippedCollections.isEmpty())
    }

    @Test
    fun blankNpComIdIsNotDiscarded() {
        // Un puente que no rellena npComId no descarta la colección:
        // el filtro solo actúa con un npComId distinto y no vacío.
        val result = mapNativeTrophies(
            arrayOf(collection(nativeTrophy("Explorer", earned = true))),
            nowMillis = 1_800_000_000_000L,
            expectedNpComId = "NPWR06875_00",
        )
        assertEquals(listOf(18), result.unlocked.map { it.number })
    }

    @Test
    fun pendingListenerFiresOnStoreAfterEmptyTake() {
        // P1-5: simula el orden real onPause -> onResume -> onDestroy.
        // El onResume consume antes (take() == null) y la captura de
        // onDestroy llega después (store): el oyente debe avisar al
        // consumidor para que la sync se dispare en esa vuelta.
        val collections = arrayOf(collection(nativeTrophy("Sunrise", earned = true)))
        var notified: Array<NativeTrophyCollection>? = null
        val listener: (Array<NativeTrophyCollection>) -> Unit = { notified = it }
        TrophySyncPending.addListener(listener)
        try {
            // onResume temprano: nada pendiente todavía.
            assertNull(TrophySyncPending.take())
            // onDestroy tardío: la captura aterriza y avisa.
            TrophySyncPending.store(collections)
            assertTrue(notified === collections)
            // El consumidor avisado ya puede tomar el pendiente.
            assertTrue(TrophySyncPending.take() === collections)
            assertNull(TrophySyncPending.take())
        } finally {
            TrophySyncPending.removeListener(listener)
            TrophySyncPending.take()
        }
    }

    @Test
    fun failingListenerDoesNotBreakStore() {
        // Un oyente que falla no impide guardar el pendiente.
        val bad: (Array<NativeTrophyCollection>) -> Unit = { throw IllegalStateException("oyente roto") }
        TrophySyncPending.addListener(bad)
        try {
            val collections = arrayOf(collection(nativeTrophy("Sunrise", earned = true)))
            TrophySyncPending.store(collections)
            assertTrue(TrophySyncPending.take() === collections)
        } finally {
            TrophySyncPending.removeListener(bad)
            TrophySyncPending.take()
        }
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
