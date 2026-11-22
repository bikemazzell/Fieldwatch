package app.fieldwatch.ui

import android.content.Context
import android.net.Uri
import app.fieldwatch.BuildConfig
import app.fieldwatch.data.ImportFileException
import app.fieldwatch.data.LanguageSnapshot
import app.fieldwatch.data.LanguageStore
import app.fieldwatch.domain.LanguageExportCopy
import app.fieldwatch.domain.LanguagePack
import app.fieldwatch.domain.LanguageParse
import app.fieldwatch.domain.LanguagePreview
import app.fieldwatch.domain.LanguageReportCopy
import app.fieldwatch.domain.LanguageText
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LanguageController(
    private val app: Context,
    private val language: () -> LanguageStore,
    private val scope: CoroutineScope,
    private val export: MutableStateFlow<ExportUi>,
    private val readImport: (Uri, Int) -> ByteArray,
) {
    private val _language = MutableStateFlow(LanguageUiState())
    val state: StateFlow<LanguageUiState> = _language.asStateFlow()

    fun restore(snap: LanguageSnapshot) {
        _language.value = LanguageUiState(snap)
    }

    fun chooseLanguage(choice: String) {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                language().choose(choice, Locale.getDefault())
            }
            publishLanguage(snap)
        }
    }

    fun importLanguageFromUri(uri: Uri) {
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    readImport(uri, LanguagePack.MAX_BYTES)
                }
                when (val parsed = LanguagePack.parse(bytes)) {
                    is LanguageParse.Reject ->
                        _language.value = _language.value.copy(pending = null, errorKey = parsed.messageKey)
                    is LanguageParse.Ok ->
                        _language.value = _language.value.copy(pending = parsed.preview, errorKey = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportFileException) {
                _language.value = _language.value.copy(pending = null, errorKey = e.key)
            } catch (_: Exception) {
                _language.value = _language.value.copy(pending = null, errorKey = "ui.could.not.read.file")
            }
        }
    }

    fun confirmLanguageImport() {
        val pending = _language.value.pending ?: return
        scope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    language().confirm(pending, Locale.getDefault())
                }
                publishLanguage(snap)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _language.value = _language.value.copy(pending = null, errorKey = "import.failed.save")
            }
        }
    }

    fun cancelLanguageImport() {
        _language.value = _language.value.copy(pending = null)
    }

    fun startLanguageReportShare() {
        val pending = _language.value.pending ?: return
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.Default) {
                    LanguagePack.report(pending, BuildConfig.VERSION_NAME)
                }
                val dir = File(app.cacheDir, "language").apply { mkdirs() }
                val file = File(dir, LanguageReportCopy.FILE)
                withContext(Dispatchers.IO) { file.writeText(text) }
                fileShare(app, file, "text/plain", "language-report", LanguageReportCopy.SHARE)
            }.onSuccess { intent ->
                export.value = ExportUi(share = intent, shareTitle = LanguageReportCopy.SHARE)
            }.onFailure {
                _language.value = _language.value.copy(errorKey = "language.report.failed")
            }
        }
    }

    fun dismissLanguageError() {
        _language.value = _language.value.copy(errorKey = null)
    }

    fun removeLanguagePack() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                language().remove(Locale.getDefault())
            }
            publishLanguage(snap)
        }
    }

    fun dismissLanguageDoor() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                language().dismissDoor(Locale.getDefault())
            }
            publishLanguage(snap, _language.value.pending, _language.value.errorKey)
        }
    }

    fun dismissLanguageAi() {
        val tag = _language.value.snap.activeTag ?: return
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                language().markAiSeen(tag, Locale.getDefault())
            }
            publishLanguage(snap, _language.value.pending, _language.value.errorKey)
        }
    }

    fun startLanguageTemplateShare() {
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) {
                    LanguagePack.template(BuildConfig.VERSION_NAME)
                }
                val dir = File(app.cacheDir, "language").apply { mkdirs() }
                val file = File(dir, "fieldwatch-language-en.json")
                withContext(Dispatchers.IO) { file.writeText(json) }
                fileShare(app, file, "application/json", "language", LanguageExportCopy.SHARE)
            }.onSuccess { intent ->
                export.value = ExportUi(share = intent, shareTitle = LanguageExportCopy.SHARE)
            }.onFailure { err ->
                export.value = ExportUi(
                    error = err.message ?: LanguageExportCopy.FAILED,
                    errorTitle = LanguageExportCopy.FAILED,
                )
            }
        }
    }

    private fun publishLanguage(
        snap: LanguageSnapshot,
        pending: LanguagePreview? = null,
        errorKey: String? = null,
    ) {
        LanguageText.apply(snap.overlay, snap.activeTag)
        _language.value = LanguageUiState(snap, pending, errorKey)
    }
}
