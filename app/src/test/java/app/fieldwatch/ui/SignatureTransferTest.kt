package app.fieldwatch.ui

import app.fieldwatch.data.CatalogRemote
import app.fieldwatch.domain.LanguageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureTransferTest {
    @Test
    fun serverFailureWithACodeNamesTheCode() {
        val shown = catalogFetchFailure(CatalogRemote.Fetch.Failed(CatalogRemote.Reason.SERVER, 503))
        assertEquals(LanguageText.text("ui.could.not.import.catalog"), shown.errorTitle)
        assertTrue(shown.error!!.contains("503"))
        assertTrue(shown.error!!.endsWith(LanguageText.text("import.failed.kept")))
    }

    @Test
    fun unreachableCatalogUsesTheGitHubTitle() {
        val shown = catalogFetchFailure(CatalogRemote.Fetch.Failed(CatalogRemote.Reason.REACH))
        assertEquals(LanguageText.text("ui.could.not.reach.github"), shown.errorTitle)
    }

    @Test
    fun newerNoteAddsTheNoticeOnlyForANewerFile() {
        assertEquals("Done.", newerNote(false, "Done."))
        assertTrue(newerNote(true, "Done.").startsWith("Done. This file is newer"))
        assertTrue(newerNote(true, " ").startsWith("This file is newer"))
    }
}
