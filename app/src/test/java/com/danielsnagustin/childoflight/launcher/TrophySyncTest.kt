package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.TrophySyncPending
import com.danielsnagustin.childoflight.launcher.data.decodePending
import com.danielsnagustin.childoflight.launcher.data.encodePending
import com.danielsnagustin.childoflight.launcher.data.mapNativeTrophies
import java.io.File
import java.nio.file.Files
import org.vita3k.emulator.data.NativeTrophy
import org.vita3k.emulator.data.NativeTrophyCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // R-3 (tercera pasada): persistencia del pendiente a disco.
    // Cada test usa su propio directorio temporal y limpia el
    // singleton en finally (resetForTest), para no contaminar los
    // tests de solo-memoria de arriba.

    private fun tempPersistDir(): File =
        Files.createTempDirectory("trophy-sync-test").toFile()

    private fun storedCollection(): NativeTrophyCollection =
        collection(
            nativeTrophy("Sunrise", earned = true, timestampSec = 1_700_000_000L),
            nativeTrophy("Starcatcher", earned = false),
        ).apply {
            npComId = "NPWR06875_00"
            totalCount = 20
            unlockedCount = 1
        }

    @Test
    fun encodeDecodeRoundTripKeepsTrickyNames() {
        // Nombre con tabulador, salto de línea, comillas y tildes:
        // sin Base64 rompería el formato por líneas.
        val original = NativeTrophyCollection().apply {
            npComId = "NPWR06875_00"
            totalCount = 20
            unlockedCount = 1
            trophies = arrayOf(
                NativeTrophy().apply {
                    this.npComId = "NPWR06875_00"
                    trophyId = 11
                    name = "Décimo\tcumpleaños\n\"con comillas\""
                    earned = true
                    timestampSec = 1_700_000_000L
                },
                NativeTrophy().apply {
                    trophyId = 0
                    name = "Starcatcher"
                    earned = false
                    timestampSec = 0L
                },
            )
        }
        val decoded = decodePending(encodePending(arrayOf(original)))
        assertNotNull(decoded)
        assertEquals(1, decoded!!.size)
        assertEquals("NPWR06875_00", decoded[0].npComId)
        assertEquals(20, decoded[0].totalCount)
        assertEquals(1, decoded[0].unlockedCount)
        assertEquals(2, decoded[0].trophies.size)
        assertEquals("Décimo\tcumpleaños\n\"con comillas\"", decoded[0].trophies[0].name)
        assertEquals(11, decoded[0].trophies[0].trophyId)
        assertTrue(decoded[0].trophies[0].earned)
        assertEquals(1_700_000_000L, decoded[0].trophies[0].timestampSec)
        assertFalse(decoded[0].trophies[1].earned)
        // Texto corrupto o de otro formato: null, nunca una colección inventada.
        assertNull(decodePending("basura sin magia\n"))
        assertNull(decodePending("trophy-sync-pending v1\nT\tno-numérico\n"))
        assertNull(decodePending("trophy-sync-pending v1\nT\t1\t!!!no-base64!!!\t1\t0\n"))
    }

    @Test
    fun pendingSurvivesProcessDeathAndReplaysOnStartup() {
        // R-3: proceso matado con el juego en primer plano ->
        // arranque -> sync. Simulación: store() (captura en
        // onDestroy) persiste a disco; la sync consume la memoria;
        // el proceso "muere" antes del ack (sin clearPersisted): el
        // siguiente take() (el arranque) debe releer el archivo y el
        // mapeo debe dar el mismo desbloqueo. El ack final lo borra.
        val dir = tempPersistDir()
        try {
            TrophySyncPending.resetForTest()
            TrophySyncPending.initPersistence(dir)
            TrophySyncPending.store(arrayOf(storedCollection()))
            val pendingFile = File(dir, "trophy-sync-pending.txt")
            assertTrue(pendingFile.isFile)
            // La sync normal consume la memoria...
            val taken = TrophySyncPending.take()
            assertEquals(1, taken?.size)
            // ...pero el proceso muere antes del ack: el arranque
            // reintenta desde el disco (misma ruta que si el take
            // inicial nunca hubiera corrido: la memoria está vacía).
            val replayed = TrophySyncPending.take()
            assertNotNull(replayed)
            assertEquals(1, replayed!!.size)
            val result = mapNativeTrophies(replayed, nowMillis = 1_800_000_000_000L)
            assertEquals(listOf(3), result.unlocked.map { it.number })
            assertEquals(1_700_000_000_000L, result.unlocked[0].unlockedAtMillis)
            // La sync con éxito hace ack: ya no hay nada que reintentar.
            TrophySyncPending.clearPersisted()
            assertNull(TrophySyncPending.take())
            assertFalse(pendingFile.exists())
        } finally {
            TrophySyncPending.take()
            TrophySyncPending.resetForTest()
            dir.deleteRecursively()
        }
    }

    @Test
    fun newStoreBetweenTakeAndAckIsNotDeleted() {
        // La carrera que señaló OpenCode: un store() nuevo entre
        // take() y clearPersisted() no debe perder su archivo por el
        // ack de la sync anterior (la huella lo protege).
        val dir = tempPersistDir()
        try {
            TrophySyncPending.resetForTest()
            TrophySyncPending.initPersistence(dir)
            val pendingFile = File(dir, "trophy-sync-pending.txt")
            TrophySyncPending.store(arrayOf(storedCollection()))
            assertTrue(TrophySyncPending.take()?.size == 1)
            // Otra partida termina mientras la sync anterior aún no
            // hizo ack: pisa memoria y disco con lo nuevo.
            val second = collection(nativeTrophy("Starcatcher", earned = true)).apply {
                npComId = "NPWR06875_00"
                totalCount = 20
                unlockedCount = 1
            }
            TrophySyncPending.store(arrayOf(second))
            // El ack de la primera sync no borra el archivo nuevo.
            TrophySyncPending.clearPersisted()
            assertTrue(pendingFile.isFile)
            // Lo nuevo se consume primero de memoria y luego, si el
            // proceso muriera antes de su ack, del disco.
            val fromMemory = TrophySyncPending.take()
            assertEquals(1, fromMemory?.size)
            val fromDisk = TrophySyncPending.take()
            assertEquals(1, fromDisk?.size)
            assertEquals("Starcatcher", fromDisk!![0].trophies[0].name)
            TrophySyncPending.clearPersisted()
            assertNull(TrophySyncPending.take())
            assertFalse(pendingFile.exists())
        } finally {
            TrophySyncPending.take()
            TrophySyncPending.resetForTest()
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptPersistedFileIsDiscarded() {
        val dir = tempPersistDir()
        try {
            TrophySyncPending.resetForTest()
            TrophySyncPending.initPersistence(dir)
            val pendingFile = File(dir, "trophy-sync-pending.txt")
            pendingFile.writeText("basura sin magia\nC\tnope\n", Charsets.UTF_8)
            assertNull(TrophySyncPending.take())
            assertFalse(pendingFile.exists())
        } finally {
            TrophySyncPending.take()
            TrophySyncPending.resetForTest()
            dir.deleteRecursively()
        }
    }
}
