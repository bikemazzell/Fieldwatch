package app.fieldwatch.data

import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.RadioKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

class SitStoreTest {
    private val fleets = DefaultCatalog.fleets()

    @Test
    fun anOpenSitReloadsWithItsRadiosPathAndSummary() = runBlocking {
        val dir = storeTempDir("sit-store-test")
        val store = store(dir)
        store.load()
        val heard = listOf(
            sighting("AA:BB:CC:00:00:01", name = "one"),
            sighting("AA:BB:CC:00:00:02", RadioKind.WIFI, name = "two"),
        )
        val summary = store.start("lot", heard, fleets, emptyList(), now = 10_000L)!!
        store.recordPath(28.0, -81.0, 11_000L)
        store.recordPath(28.0001, -81.0001, 12_000L)
        store.flushOpen()
        val written = store.debriefSource(now = 20_000L)!!

        val again = store(dir)
        again.load()
        val reloaded = again.debriefSource(now = 20_000L)!!
        assertEquals(summary.id, again.openSitId())
        assertEquals(store.sitFile(summary.id)!!.summary, again.ui.value.open)
        assertEquals(2, again.ui.value.open!!.radioCount)
        assertEquals("lot", again.ui.value.open!!.name)
        assertEquals(written.devices.map { it.key }.toSet(), reloaded.devices.map { it.key }.toSet())
        assertEquals(setOf("BLE:AA:BB:CC:00:00:01", "WIFI:AA:BB:CC:00:00:02"), reloaded.devices.map { it.key }.toSet())
        assertEquals(written.operatorPath, reloaded.operatorPath)
        assertEquals(2, reloaded.operatorPath.size)
        assertEquals(written.name, reloaded.name)
        assertEquals(written.startAt, reloaded.startAt)
    }

    @Test
    fun aSitFlushedTwiceKeepsTheLatestContent() = runBlocking {
        val dir = storeTempDir("sit-store-test")
        val store = store(dir)
        store.load()
        val summary = store.start("lot", listOf(sighting("AA:BB:CC:00:00:01")), fleets, emptyList(), now = 10_000L)!!
        store.recordPath(28.0, -81.0, 11_000L)
        store.flushOpen()
        store.recordPath(28.0001, -81.0001, 12_000L)
        store.recordPath(28.0002, -81.0002, 13_000L)
        store.flushOpen()

        val again = store(dir)
        again.load()
        val file = again.sitFile(summary.id)!!
        assertEquals(3, file.operatorPath.size)
        assertEquals(listOf(11_000L, 12_000L, 13_000L), file.operatorPath.map { it.at })
        assertEquals(3, again.debriefSource()!!.operatorPath.size)
        assertEquals(1, File(dir, "sits").listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test
    fun aClosedSitReloadsAsClosed() = runBlocking {
        val dir = storeTempDir("sit-store-test")
        val store = store(dir)
        store.load()
        val started = store.start("lot", listOf(sighting("AA:BB:CC:00:00:01")), fleets, emptyList(), now = 10_000L)!!
        val ended = store.end(fleets, now = 50_000L)!!

        val again = store(dir)
        again.load()
        assertNull(again.ui.value.open)
        assertEquals(listOf(ended), again.ui.value.closed)
        assertEquals(started.id, ended.id)
        assertNotNull(again.sitFile(ended.id))
        assertTrue(again.ui.value.closed.single().endAt == 50_000L)
    }

    private fun store(dir: File) = SitStore(FilesDirContext(dir), CoroutineScope(EmptyCoroutineContext))
}
