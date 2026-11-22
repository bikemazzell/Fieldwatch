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

    private fun replayOf(parts: List<File>): List<LogRadio> {
        val acc = LinkedHashMap<String, LogRadio>()
        parts.forEach { file ->
            LogReplay.ingest(file.readLines().asSequence(), json = true, acc = acc)
        }
        return acc.values.toList()
    }
}
