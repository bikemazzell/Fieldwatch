package app.fieldwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class AtomicFileTest {
    @Test
    fun aWriteThatThrowsPartwayLeavesTheOldFileIntact() {
        val dir = storeTempDir("atomic-file-test")
        val target = File(dir, "state.json")
        target.writeText("old")

        assertThrows(IOException::class.java) {
            writeAtomic(target) {
                it.write("new but incomplete".toByteArray())
                throw IOException("killed")
            }
        }

        assertEquals("old", target.readText())
        assertFalse(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun aCompleteWriteReplacesTheFile() {
        val target = File(storeTempDir("atomic-file-test"), "state.json")
        target.writeText("old")

        writeAtomic(target) { it.write("new".toByteArray()) }

        assertEquals("new", target.readText())
    }

    @Test
    fun overlappingWritesNeverShareATempFile() {
        val dir = storeTempDir("atomic-file-test")
        val target = File(dir, "state.json")
        val body = "x".repeat(200_000)

        writeAtomic(target) { outer ->
            outer.write(body.toByteArray())
            writeAtomic(target) { it.write("inner".toByteArray()) }
            assertEquals("inner", target.readText())
        }

        assertEquals(body, target.readText())
    }
}
