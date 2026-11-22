package app.fieldwatch.data

import android.content.Context
import android.content.ContextWrapper
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.Sighting
import java.io.File

class FilesDirContext(private val filesRoot: File) : ContextWrapper(null) {
    override fun getFilesDir(): File = filesRoot

    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any =
        throw UnsupportedOperationException("FilesDirContext has no system services: $name")
}

fun storeTempDir(label: String): File =
    File("build/tmp/$label-${System.nanoTime()}").apply { mkdirs() }

fun sighting(
    mac: String,
    kind: RadioKind = RadioKind.BLE,
    name: String = "",
    lastSeen: Long = 1_000L,
    fleetIds: Set<String> = emptySet(),
) = Sighting(
    key = "${kind.name}:$mac",
    kind = kind,
    mac = mac,
    name = name,
    rssi = -60,
    rssiMin = -60,
    rssiMax = -60,
    channel = 0,
    frequencyMhz = 2402,
    vendor = null,
    randomized = true,
    hiddenSsid = false,
    serviceUuids = emptyList(),
    manufacturerId = null,
    manufacturerDataHex = "",
    rawHex = "",
    extras = "",
    firstSeen = lastSeen,
    lastSeen = lastSeen,
    hitCount = 1,
    fleetIds = fleetIds,
    rssiHistory = emptyList(),
    presence = emptyList(),
    gone = false,
)
