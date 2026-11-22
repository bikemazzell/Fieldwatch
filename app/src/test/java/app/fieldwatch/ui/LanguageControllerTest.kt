package app.fieldwatch.ui

import app.fieldwatch.data.FilesDirContext
import app.fieldwatch.data.LanguageAssets
import app.fieldwatch.data.LanguageSnapshot
import app.fieldwatch.data.LanguageStore
import app.fieldwatch.data.storeTempDir
import app.fieldwatch.domain.LanguageText
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageControllerTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val dir = storeTempDir("language-controller")
    private val store = LanguageStore(File(dir, "language.json"), LanguageAssets { tag ->
        if (tag == "fr") FRENCH.toByteArray() else null
    }).also { it.load(Locale.ENGLISH) }
    private val controller = LanguageController(
        FilesDirContext(dir),
        { store },
        scope,
        MutableStateFlow(ExportUi()),
        { _, _ -> error("no import in this test") },
    )

    @After
    fun stop() {
        scope.cancel()
        LanguageText.clear()
    }

    @Test
    fun choosingABundledLanguagePublishesItsSnapshotAndTextThenFollowReturnsToEnglish() = runBlocking {
        controller.chooseLanguage("fr")
        val french = withTimeout(5_000) { controller.state.first { it.snap.choice == "fr" } }
        assertEquals("fr", french.snap.activeTag)
        assertEquals("Direct", LanguageText.text("tab.live"))
        controller.chooseLanguage(LanguageSnapshot.CHOICE_FOLLOW)
        withTimeout(5_000) { controller.state.first { it.snap.choice == LanguageSnapshot.CHOICE_FOLLOW } }
        assertEquals("Live", LanguageText.text("tab.live"))
    }

    @Test
    fun dismissingTheDoorSticksAndLeavesAnErrorKeyAlone() = runBlocking {
        controller.dismissLanguageDoor()
        val after = withTimeout(5_000) { controller.state.first { it.snap.doorDismissed } }
        assertTrue(after.snap.doorDismissed)
        assertEquals(null, after.errorKey)
    }

    private companion object {
        const val FRENCH =
            """{"format":"fieldwatch-language","formatVersion":1,"language":"Français","languageTag":"fr","writtenFor":"1.1.27","strings":{"tab.live":"Direct"}}"""
    }
}
