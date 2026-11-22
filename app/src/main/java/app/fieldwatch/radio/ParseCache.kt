package app.fieldwatch.radio

class ParseCache<V> {
    private class Entry<V>(val timestamp: Long, val capabilities: String?, val value: V)

    private val entries = HashMap<String, Entry<V>>()

    @Synchronized
    fun getOrParse(bssid: String, timestamp: Long, capabilities: String?, parse: () -> V): V {
        val hit = entries[bssid]
        if (hit != null && hit.timestamp == timestamp && hit.capabilities == capabilities) return hit.value
        return parse().also { entries[bssid] = Entry(timestamp, capabilities, it) }
    }

    @Synchronized
    fun retainOnly(bssids: Set<String>) {
        entries.keys.retainAll(bssids)
    }
}
