package app.fieldwatch.domain

/** Each sort key is computed once per device. The sort is stable, so ties keep the input order. */
fun List<Sighting>.sortedForList(
    sort: ListSort,
    strengthSort: StrengthSort,
    windowMs: Long,
    now: Long,
    alerts: Map<String, Long>,
    titleLine: ListLine,
    fleetNames: Map<String, String>,
    watchNames: Map<String, String>,
): List<Sighting> {
    fun rssi(d: Sighting) = d.sortRssi(strengthSort, windowMs, now)
    return when (sort) {
        ListSort.STRENGTH ->
            sortedOnKeys(::rssi, compareByDescending { it })
        ListSort.NEWEST ->
            sortedOnKeys(
                { d -> d.lastSeen to rssi(d) },
                compareByDescending<Pair<Long, Double>> { it.first }.thenByDescending { it.second },
            )
        ListSort.NEWEST_ALERT ->
            sortedWith(
                compareByDescending<Sighting> { alerts[it.key] ?: 0L }.thenByDescending { it.lastSeen },
            )
        ListSort.FIRST_SEEN ->
            sortedWith(compareByDescending<Sighting> { it.firstSeen }.thenByDescending { it.lastSeen })
        ListSort.ARRIVAL ->
            sortedWith(compareBy<Sighting> { it.firstSeen }.thenBy { it.mac })
        ListSort.NAME ->
            sortedOnKeys(
                { d ->
                    val names = d.fleetIds.map { fleetNames[it] ?: it }
                    d.listLineText(titleLine, names, watchNames[d.key]).lowercase() to d.mac
                },
                compareBy<Pair<String, String>> { it.first }.thenBy { it.second },
            )
        ListSort.SIGNATURES ->
            sortedOnKeys(
                { d -> d.fleetIds.isNotEmpty() to rssi(d) },
                compareByDescending<Pair<Boolean, Double>> { it.first }.thenByDescending { it.second },
            )
    }
}

private fun <K> List<Sighting>.sortedOnKeys(
    keyOf: (Sighting) -> K,
    order: Comparator<K>,
): List<Sighting> =
    map { it to keyOf(it) }
        .sortedWith { a, b -> order.compare(a.second, b.second) }
        .map { it.first }
