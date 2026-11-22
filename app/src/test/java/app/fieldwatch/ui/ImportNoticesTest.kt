package app.fieldwatch.ui

import app.fieldwatch.data.FilesDirContext
import app.fieldwatch.data.storeTempDir
import app.fieldwatch.domain.LanguageText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportNoticesTest {
    private val export = MutableStateFlow(ExportUi())
    private val notices = ImportNotices(FilesDirContext(storeTempDir("import-notices")), export)

    @Test
    fun aKeptProblemAddsThatNothingWasChanged() {
        notices.showProblem("ui.could.not.import.settings", " Bad file. ", kept = true)
        val shown = export.value
        assertEquals(LanguageText.text("ui.could.not.import.settings"), shown.errorTitle)
        assertEquals("Bad file. " + LanguageText.text("import.failed.kept"), shown.error)
    }

    @Test
    fun anUnkeptProblemShowsTheBodyAsGiven() {
        notices.showProblem("ui.could.not.import.settings", " Save failed ", kept = false)
        assertEquals(" Save failed ", export.value.error)
    }

    @Test
    fun aPackFromANewerAppOffersTheUpdate() {
        notices.showCatalogNeedsApp()
        assertTrue(export.value.updateApp)
        assertEquals(LanguageText.text("catalog.needs.app.title"), export.value.errorTitle)
    }
}
