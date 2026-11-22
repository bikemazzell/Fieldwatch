package app.fieldwatch.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseCacheTest {
    @Test
    fun reusesTheParseWhileBssidTimestampAndCapabilitiesMatch() {
        val cache = ParseCache<String>()
        var parses = 0
        val parse = { parses++; "parsed$parses" }
        assertEquals("parsed1", cache.getOrParse("aa", 10L, "WPA2", parse))
        assertEquals("parsed1", cache.getOrParse("aa", 10L, "WPA2", parse))
        assertEquals(1, parses)
    }

    @Test
    fun parsesAgainWhenTimestampOrCapabilitiesChange() {
        val cache = ParseCache<Int>()
        var parses = 0
        val parse = { ++parses }
        cache.getOrParse("aa", 10L, "WPA2", parse)
        assertEquals(2, cache.getOrParse("aa", 11L, "WPA2", parse))
        assertEquals(3, cache.getOrParse("aa", 11L, "WPA3", parse))
        assertEquals(4, cache.getOrParse("bb", 11L, "WPA3", parse))
    }

    @Test
    fun retainOnlyDropsBssidsOutsideTheCurrentSet() {
        val cache = ParseCache<Int>()
        var parses = 0
        val parse = { ++parses }
        cache.getOrParse("aa", 1L, null, parse)
        cache.getOrParse("bb", 1L, null, parse)
        cache.retainOnly(setOf("aa"))
        assertEquals(1, cache.getOrParse("aa", 1L, null, parse))
        assertEquals(3, cache.getOrParse("bb", 1L, null, parse))
    }
}
