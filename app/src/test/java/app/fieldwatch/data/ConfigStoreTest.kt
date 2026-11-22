package app.fieldwatch.data

import app.fieldwatch.domain.PersistedConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ConfigStoreTest {
    private val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val compact = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun anUpdatedSettingPersistsAcrossStoreInstances() = runBlocking {
        val dir = storeTempDir("config-store-test")
        val store = ConfigStore(FilesDirContext(dir))
        store.load()
        val before = store.settings.logRotateKb
        val changed = before + 512
        store.update { it.copy(settings = it.settings.copy(logRotateKb = changed)) }

        val again = ConfigStore(FilesDirContext(dir))
        again.load()
        assertEquals(changed, again.settings.logRotateKb)
        assertEquals(store.config.value.fleets.map { it.id }, again.config.value.fleets.map { it.id })
    }

    @Test
    fun compactAndPrettyConfigFilesLoadTheSame() = runBlocking {
        val seedDir = storeTempDir("config-store-test")
        val seeding = ConfigStore(FilesDirContext(seedDir))
        seeding.load()
        val config = seeding.config.value.let { it.copy(settings = it.settings.copy(logRotateKb = 2048)) }

        val loadedPretty = loadFrom(config, pretty)
        val loadedCompact = loadFrom(config, compact)
        assertEquals(2048, loadedPretty.settings.logRotateKb)
        assertEquals(loadedPretty, loadedCompact)
    }

    private fun loadFrom(config: PersistedConfig, json: Json): PersistedConfig = runBlocking {
        val dir = storeTempDir("config-store-test")
        File(dir, "config.json").writeText(json.encodeToString(config))
        val store = ConfigStore(FilesDirContext(dir))
        store.load()
        store.config.value
    }
}
