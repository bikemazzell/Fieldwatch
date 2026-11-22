package app.fieldwatch.ui

import android.content.Context
import android.net.Uri
import app.fieldwatch.data.ImportFileException
import app.fieldwatch.data.ImportFiles
import app.fieldwatch.domain.LanguageText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

class ImportNotices(private val app: Context, private val export: MutableStateFlow<ExportUi>) {
    fun readBytes(uri: Uri, maxBytes: Int): ByteArray {
        try {
            val stream = app.contentResolver.openInputStream(uri)
                ?: throw ImportFileException("ui.could.not.read.file")
            return stream.use { ImportFiles.read(it, maxBytes) }
        } catch (e: ImportFileException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw ImportFileException("ui.could.not.read.file")
        }
    }

    fun readText(uri: Uri, maxBytes: Int): String =
        readBytes(uri, maxBytes).toString(Charsets.UTF_8)

    /** The pack named a class, a rule, or a radio this app does not know. Nothing was saved. */
    fun showCatalogNeedsApp() {
        export.value = ExportUi(
            errorTitle = LanguageText.text("catalog.needs.app.title"),
            error = LanguageText.text("catalog.needs.app.body").trim() +
                " " + LanguageText.text("import.failed.kept"),
            updateApp = true,
        )
    }

    /** A failed import names the reason. [kept] adds that this phone was not changed. */
    fun showProblem(titleKey: String, body: String, kept: Boolean) {
        val text = if (kept) body.trim() + " " + LanguageText.text("import.failed.kept") else body
        export.value = ExportUi(
            errorTitle = LanguageText.text(titleKey),
            error = text,
        )
    }
}
