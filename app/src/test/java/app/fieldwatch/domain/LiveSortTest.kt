package app.fieldwatch.domain

import app.fieldwatch.data.sighting
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSortTest {
    private val now = 100_000L
    private val windowMs = 30_000L
    private val fleetNames = mapOf("f1" to "Zeta", "f2" to "alpha")
    private val watchNames = mapOf("BLE:AA" to "Mango")
    private val alerts = mapOf("BLE:AA" to 5L, "BLE:CC" to 9L)

    private val devices = listOf(
        sighting("CC", name = "beta", lastSeen = 3_000L, fleetIds = setOf("f1")),
        sighting("AA", name = "Gamma", lastSeen = 3_000L),
        sighting("EE", name = "gamma", lastSeen = 2_000L, fleetIds = setOf("f2")),
        sighting("BB", name = "Beta", lastSeen = 3_000L),
        sighting("DD", name = "alpha", lastSeen = 1_000L, fleetIds = setOf("f1", "f2")),
    )

    private fun reference(sort: ListSort): List<Sighting> = devices.sortedWith(
        when (sort) {
            ListSort.STRENGTH ->
                compareByDescending { it.sortRssi(StrengthSort.INSTANT, windowMs, now) }
            ListSort.NEWEST ->
                compareByDescending<Sighting> { it.lastSeen }
                    .thenByDescending { it.sortRssi(StrengthSort.INSTANT, windowMs, now) }
            ListSort.NEWEST_ALERT ->
                compareByDescending<Sighting> { alerts[it.key] ?: 0L }.thenByDescending { it.lastSeen }
            ListSort.FIRST_SEEN ->
                compareByDescending<Sighting> { it.firstSeen }.thenByDescending { it.lastSeen }
            ListSort.ARRIVAL ->
                compareBy<Sighting> { it.firstSeen }.thenBy { it.mac }
            ListSort.NAME ->
                compareBy<Sighting> { d ->
                    val names = d.fleetIds.map { fleetNames[it] ?: it }
                    d.listLineText(ListLine.NAME_AND_TYPE, names, watchNames[d.key]).lowercase()
                }.thenBy { it.mac }
            ListSort.SIGNATURES ->
                compareByDescending<Sighting> { it.fleetIds.isNotEmpty() }
                    .thenByDescending { it.sortRssi(StrengthSort.INSTANT, windowMs, now) }
        },
    )

    @Test
    fun everySortKeepsTheOrderOfThePerComparisonComparators() {
        for (sort in ListSort.values()) {
            val sorted = devices.sortedForList(
                sort, StrengthSort.INSTANT, windowMs, now, alerts, ListLine.NAME_AND_TYPE, fleetNames, watchNames,
            )
            assertEquals(sort.name, reference(sort).map { it.key }, sorted.map { it.key })
        }
    }

    @Test
    fun tiesKeepTheInputOrder() {
        val sorted = devices.sortedForList(
            ListSort.STRENGTH, StrengthSort.INSTANT, windowMs, now, alerts, ListLine.NAME_AND_TYPE, fleetNames, watchNames,
        )
        assertEquals(devices.map { it.key }, sorted.map { it.key })
    }
}
