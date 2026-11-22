package app.fieldwatch.radio

class HitWindow {
    private val counts = IntArray(WINDOW_SECONDS)
    private val stamps = LongArray(WINDOW_SECONDS) { Long.MIN_VALUE }

    @Synchronized
    fun record(now: Long) {
        val second = Math.floorDiv(now, 1_000L)
        val slot = Math.floorMod(second, WINDOW_SECONDS.toLong()).toInt()
        if (stamps[slot] != second) {
            stamps[slot] = second
            counts[slot] = 0
        }
        counts[slot]++
    }

    @Synchronized
    fun lastMinute(now: Long): Int {
        val oldest = Math.floorDiv(now, 1_000L) - WINDOW_SECONDS
        var total = 0
        for (slot in 0 until WINDOW_SECONDS) {
            if (stamps[slot] > oldest) total += counts[slot]
        }
        return total
    }

    private companion object {
        const val WINDOW_SECONDS = 60
    }
}
