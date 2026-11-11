package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.TROPHIES_20
import com.danielsnagustin.childoflight.launcher.model.TrophyPopupQueue
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cola de popups de trofeos (R7-P1-06, 0.2.58): si una partida
 * desbloquea varios trofeos, cada uno tiene su popup, uno tras otro
 * en orden numérico; antes solo el último aparecía.
 */
class TrophyPopupQueueTest {

    private fun progress(number: Int, atMillis: Long = 1000L): TrophyProgress {
        val trophy = TROPHIES_20.first { it.number == number }
        return TrophyProgress(trophy, unlocked = true, unlockedAtMillis = atMillis)
    }

    @Test
    fun emptyQueueHasNoCurrent() {
        val queue = TrophyPopupQueue()
        assertNull(queue.current)
        assertEquals(0, queue.size)
        assertNull(queue.dismiss())
        assertNull(queue.current)
    }

    @Test
    fun enqueuedInNumericOrderShowOneAfterAnother() {
        // La sync encola en orden numérico (mapNativeTrophies ordena
        // por número): 4, 14 y 17 se muestran en ese orden.
        val queue = TrophyPopupQueue()
        queue.enqueue(progress(4, 1000L))
        queue.enqueue(progress(14, 2000L))
        queue.enqueue(progress(17, 3000L))
        assertEquals(3, queue.size)
        assertEquals(4, queue.current?.trophy?.number)
        assertEquals("Coronación", queue.current?.trophy?.name)
        assertEquals(14, queue.dismiss()?.trophy?.number)
        assertEquals(2, queue.size)
        assertEquals(17, queue.dismiss()?.trophy?.number)
        assertEquals(1, queue.size)
        assertNull(queue.dismiss())
        assertEquals(0, queue.size)
        assertNull(queue.current)
    }

    @Test
    fun dismissOnEmptyStaysEmpty() {
        val queue = TrophyPopupQueue()
        assertNull(queue.dismiss())
        queue.enqueue(progress(4))
        queue.dismiss()
        assertNull(queue.dismiss())
        assertEquals(0, queue.size)
    }

    @Test
    fun clearEmptiesQueueAndEnqueueWorksAgain() {
        val queue = TrophyPopupQueue()
        queue.enqueue(progress(4))
        queue.enqueue(progress(14))
        queue.clear()
        assertNull(queue.current)
        assertEquals(0, queue.size)
        queue.enqueue(progress(17))
        assertEquals(17, queue.current?.trophy?.number)
        assertEquals(1, queue.size)
    }
}
