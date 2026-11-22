package app.fieldwatch.data

import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.LogFormat
import app.fieldwatch.domain.LogRadio
import app.fieldwatch.domain.LogReplay
import app.fieldwatch.domain.RadioKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogStoreTest {
    private val fleets = DefaultCatalog.fleets()

    @Test
    fun readRadiosMatchesALogReplayOverTheSameLinesAcrossRotation() = runBlocking {
        val store = LogStore(FilesDirContext(storeTempDir("log-store-test")))
        store.configure(LogFormat.JSON, rotateKb = 64)
        val radios = (0 until 400).map { n ->
            val kind = if (n % 3 == 0) RadioKind.WIFI else RadioKind.BLE
            sighting("AA:BB:CC:%02X:%02X:%02X".format(n / 65536, n / 256 % 256, n % 256), kind, "radio-$n", 1_000L + n)
        }
        radios.forEach { store.append(it, fleets) }
        radios.take(50).forEach { store.append(it.copy(lastSeen = 9_000L), fleets) }

        val read = store.readRadios()
        assertTrue("log crossed a rotation boundary", store.logParts().size > 1)
        assertEquals(replayOf(store.logParts()), read)
        assertEquals(400, read.size)
        assertEquals(radios.map { it.mac }.toSet(), read.map { it.mac }.toSet())
    }

    @Test
    fun clearEmptiesTheLogAndAppendStartsItAgain() = runBlocking {
        val store = LogStore(FilesDirContext(storeTempDir("log-store-test")))
        store.configure(LogFormat.JSON, rotateKb = 64)
        store.append(sighting("AA:BB:CC:00:00:01", name = "old"), fleets)
        assertEquals(1, store.readRadios().size)

        assertEquals(0L, store.clear())
        assertEquals(emptyList<LogRadio>(), store.readRadios())

        store.append(sighting("AA:BB:CC:00:00:02", name = "new"), fleets)
        val read = store.readRadios()
        assertEquals(listOf("AA:BB:CC:00:00:02"), read.map { it.mac })
        assertEquals(replayOf(store.logParts()), read)
    }

    @Test
    fun readRadiosStaysEqualToAFullReplayAsAppendsRotationAndClearInterleave() = runBlocking {
        val store = LogStore(FilesDirContext(storeTempDir("log-store-test")))
        store.configure(LogFormat.JSON, rotateKb = 64)
        var next = 0
        suspend fun appendBatch(count: Int) = repeat(count) {
            val n = next++
            store.append(sighting("AA:BB:CC:00:%02X:%02X".format(n / 256 % 256, n % 256), name = "radio-${n % 40}", lastSeen = 1_000L + n), fleets)
        }
        suspend fun assertMatchesDisk() {
            val read = store.readRadios()
            assertEquals(replayOf(store.logParts()), read)
        }

        assertMatchesDisk()
        appendBatch(30)
        assertMatchesDisk()
        appendBatch(30)
        assertMatchesDisk()
        appendBatch(400)
        assertTrue("log crossed a rotation boundary", store.logParts().size > 1)
        assertMatchesDisk()
        appendBatch(5)
        assertMatchesDisk()
        store.clear()
        assertMatchesDisk()
        appendBatch(20)
        assertMatchesDisk()
    }

    @Test
    fun aCachedReadDoesNotTouchTheFiles() = runBlocking {
        val store = LogStore(FilesDirContext(storeTempDir("log-store-test")))
        store.configure(LogFormat.JSON, rotateKb = 64)
        store.append(sighting("AA:BB:CC:00:00:01"), fleets)
        store.readRadios()
        store.logParts().single().appendText("""{"kind":"BLE","mac":"AA:BB:CC:00:00:09","ts":5}""" + "\n")

        assertEquals(listOf("AA:BB:CC:00:00:01"), store.readRadios().map { it.mac })
    }

    @Test
    fun aStoreOpenedOnExistingPartsStillMatchesAFullReplay() = runBlocking {
        val dir = storeTempDir("log-store-test")
        val first = LogStore(FilesDirContext(dir))
        first.configure(LogFormat.JSON, rotateKb = 64)
        (0 until 400).forEach { n ->
            first.append(sighting("AA:BB:CC:00:%02X:%02X".format(n / 256, n % 256), name = "radio-$n", lastSeen = 1_000L + n), fleets)
        }
        assertTrue(first.logParts().size > 1)

        val reopened = LogStore(FilesDirContext(dir))
        reopened.configure(LogFormat.JSON, rotateKb = 64)
        assertEquals(replayOf(reopened.logParts()), reopened.readRadios())
        reopened.append(sighting("AA:BB:CC:00:00:01", name = "again", lastSeen = 9_000L), fleets)
        assertEquals(replayOf(reopened.logParts()), reopened.readRadios())
    }

    @Test
    fun aTornLastLineDoesNotBreakEqualityWithAFullReplay() = runBlocking {
        val dir = storeTempDir("log-store-test")
        File(dir, "logs").mkdirs()
        File(dir, "logs/fieldwatch-000.jsonl").writeText("""{"kind":"BLE","mac":"AA:BB:CC:00:00:01","ts":5}""" + "\n" + """{"kind":"BLE","mac":"AA:BB:CC""")
        val store = LogStore(FilesDirContext(dir))
        store.configure(LogFormat.JSON, rotateKb = 64)
        store.readRadios()
        store.append(sighting("AA:BB:CC:00:00:02"), fleets)

        assertEquals(replayOf(store.logParts()), store.readRadios())
    }

    @Test
    fun aNameWithALoneSurrogateAndNonAsciiTextStillMatchesAFullReplay() = runBlocking {
        val store = LogStore(FilesDirContext(storeTempDir("log-store-test")))
        store.configure(LogFormat.JSON, rotateKb = 64)
        store.readRadios()
        store.append(sighting("AA:BB:CC:00:00:01", name = "caf\u00e9 \u65e5\u672c \uD800 \uD83D\uDE00"), fleets)

        val read = store.readRadios()
        assertEquals(replayOf(store.logParts()), read)
    }

    private fun replayOf(parts: List<File>): List<LogRadio> {
        val acc = LinkedHashMap<String, LogRadio>()
        parts.forEach { file ->
            LogReplay.ingest(file.readLines().asSequence(), json = true, acc = acc)
        }
        return acc.values.toList()
    }
}
