package app.fieldwatch.radio

import java.util.ArrayDeque
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitWindowTest {
    private class DequeReference {
        val hitAt = ArrayDeque<Long>()

        fun lastMinute(now: Long): Int = hitAt.count { it >= now - 60_000L }

        fun record(now: Long) {
            hitAt.addLast(now)
            val cut = now - 60_000L
            while (hitAt.isNotEmpty() && hitAt.first() < cut) hitAt.removeFirst()
            while (hitAt.size > 4_000) hitAt.removeFirst()
        }
    }

    @Test
    fun countsMatchTheDequeAcrossTheMinuteWindow() {
        val random = Random(7)
        val window = HitWindow()
        val reference = DequeReference()
        var now = 1_700_000_000_123L
        var compared = 0
        repeat(3_000) {
            now += random.nextLong(0, 400)
            window.record(now)
            reference.record(now)
            val query = Math.floorDiv(now + random.nextLong(0, 30_000), 1_000L) * 1_000L + 999L
            if (reference.hitAt.none { it == query - 60_000L }) {
                assertEquals("query $query", reference.lastMinute(query), window.lastMinute(query))
                compared++
            }
        }
        assertTrue(compared > 2_500)
    }

    @Test
    fun hitsLeaveTheCountAfterSixtySeconds() {
        val window = HitWindow()
        val start = 5_000_500L
        window.record(start)
        window.record(start + 10_000)
        assertEquals(2, window.lastMinute(start + 10_000))
        assertEquals(1, window.lastMinute(start + 61_000))
        assertEquals(0, window.lastMinute(start + 71_000))
    }

    @Test
    fun reusedSlotsDoNotKeepOldCounts() {
        val window = HitWindow()
        window.record(1_000_000L)
        window.record(1_060_000L)
        assertEquals(1, window.lastMinute(1_060_000L))
    }
}
