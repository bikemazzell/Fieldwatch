package app.fieldwatch.ui

import android.net.Uri
import app.fieldwatch.BuildConfig
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.data.CatalogRemote
import app.fieldwatch.data.ImportFileException
import app.fieldwatch.data.ImportFiles
import app.fieldwatch.data.PlaceLookup
import app.fieldwatch.domain.CatalogNeedsNewerApp
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.domain.SignatureExchange
import app.fieldwatch.domain.detectionPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignatureTransfer(
    private val app: FieldwatchApp,
    private val scope: CoroutineScope,
    private val export: MutableStateFlow<ExportUi>,
    private val imports: ImportNotices,
) {
    fun suggestedSignaturesName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US)
            .format(Date())
        return "fieldwatch-signatures-$stamp.rsi"
    }

    private fun signaturePackJson(): String {
        val cfg = app.config.config.value
        return SignatureExchange.encode(
            SignatureExchange.exportPack(
                fleets = cfg.fleets,
                catalogVersion = cfg.version,
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
            ),
        )
    }

    fun startSignatureShare() {
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) { signaturePackJson() }
                val dir = File(app.cacheDir, "signatures").apply { mkdirs() }
                val file = File(dir, suggestedSignaturesName())
                withContext(Dispatchers.IO) { file.writeText(json) }
                fileShare(app, file, "application/octet-stream", "signatures", LanguageText.text("ui.fieldwatch.signatures"))
            }.onSuccess { intent ->
                export.value = ExportUi(share = intent, shareTitle = LanguageText.text("ui.fieldwatch.signatures"))
            }.onFailure { err ->
                export.value = ExportUi(
                    error = err.message ?: LanguageText.text("ui.could.not.export.signatures"),
                    errorTitle = LanguageText.text("ui.could.not.export.signatures"),
                )
            }
        }
    }

    fun saveSignaturesToUri(uri: Uri) {
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) { signaturePackJson() }
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error(LanguageText.text("ui.could.not.write.location"))
                }
            }.onSuccess {
                export.value = ExportUi(
                    noticeTitle = LanguageText.text("ui.signatures.saved"),
                    noticeMessage = LanguageText.text("ui.pack.was.written.folder"),
                )
            }.onFailure { err ->
                export.value = ExportUi(
                    error = err.message ?: LanguageText.text("ui.could.not.save.signatures"),
                    errorTitle = LanguageText.text("ui.could.not.save.signatures"),
                )
            }
        }
    }

    fun updateStockCatalogFromGitHub() {
        if (export.value.active) return
        scope.launch {
            if (!PlaceLookup.online(app)) {
                export.value = ExportUi(
                    errorTitle = LanguageText.text("ui.no.internet"),
                    error = LanguageText.text("ui.no.internet.use.import"),
                )
                return@launch
            }
            export.value = ExportUi(
                active = true,
                spinner = true,
                message = LanguageText.text("ui.updating.stock.catalog"),
            )
            try {
                val fetched = withContext(Dispatchers.IO) {
                    CatalogRemote.fetch(userAgent = "Fieldwatch/${BuildConfig.VERSION_NAME}")
                }
                if (fetched is CatalogRemote.Fetch.Failed) {
                    export.value = catalogFetchFailure(fetched)
                    return@launch
                }
                val parsed = try {
                    SignatureExchange.parsePack((fetched as CatalogRemote.Fetch.Ok).text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: CatalogNeedsNewerApp) {
                    imports.showCatalogNeedsApp()
                    return@launch
                } catch (_: Exception) {
                    imports.showProblem(
                        "ui.could.not.import.catalog",
                        LanguageText.text("catalog.failed.read"),
                        kept = true,
                    )
                    return@launch
                }
                val result = app.config.overlayStockCatalog(parsed.pack)
                    .copy(skippedDecode = parsed.skippedDecode)
                if (result.error != null) {
                    imports.showProblem("ui.could.not.import.catalog", result.error, kept = true)
                    return@launch
                }
                if (!result.alreadyLatest) refreshDevicesAfterImport()
                export.value = if (result.alreadyLatest) {
                    ExportUi(
                        noticeTitle = LanguageText.text("ui.already.latest.catalog"),
                        noticeMessage = newerNote(
                            parsed.newer,
                            "Already on catalog ${result.catalogVersion}. Nothing to update.",
                        ),
                    )
                } else {
                    val bits = mutableListOf<String>()
                    if (result.updated > 0) bits += "updated ${result.updated}"
                    if (result.added > 0) bits += "added ${result.added}"
                    val change = if (bits.isEmpty()) LanguageText.text("ui.no.stock.rows.changed")
                    else bits.joinToString(" · ").replaceFirstChar { it.uppercase() } + "."
                    val skip = result.skippedDecode > 0
                    ExportUi(
                        noticeTitle = LanguageText.text("ui.catalog.updated"),
                        noticeMessage = newerNote(
                            parsed.newer,
                            "Stock catalog is now ${result.catalogVersion}. $change " +
                                LanguageText.text("ui.bookmarks.settings.were.not"),
                        ),
                        followUpTitle = if (skip) LanguageText.text("ui.signature.decoding.skipped") else null,
                        followUpMessage = if (skip) {
                            (LanguageText.text("ui.some.signature.field.maps") + " ") +
                                LanguageText.text("ui.signatures.still.match.install")
                        } else {
                            null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                imports.showProblem(
                    "ui.could.not.import.catalog",
                    LanguageText.text("import.failed.save"),
                    kept = false,
                )
            }
        }
    }

    fun importSignaturesFromUri(uri: Uri) {
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val text = imports.readText(uri, ImportFiles.SIGNATURE_MAX)
                    val parsed = SignatureExchange.parsePack(text)
                    val result = app.config.importFleets(parsed.pack.fleets)
                    if (result.error != null) error(result.error)
                    newerNote(parsed.newer, result.summary())
                }
                refreshDevicesAfterImport()
                export.value = ExportUi(
                    noticeTitle = LanguageText.text("ui.signatures.imported"),
                    noticeMessage = summary,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: CatalogNeedsNewerApp) {
                imports.showCatalogNeedsApp()
            } catch (e: ImportFileException) {
                imports.showProblem("ui.could.not.import.signatures", LanguageText.text(e.key), kept = true)
            } catch (e: IllegalArgumentException) {
                imports.showProblem(
                    "ui.could.not.import.signatures",
                    e.message ?: LanguageText.text("ui.could.not.import.signatures"),
                    kept = true,
                )
            } catch (e: IllegalStateException) {
                imports.showProblem(
                    "ui.could.not.import.signatures",
                    e.message ?: LanguageText.text("ui.could.not.import.signatures"),
                    kept = true,
                )
            } catch (_: Exception) {
                imports.showProblem(
                    "ui.could.not.import.signatures",
                    LanguageText.text("import.failed.save"),
                    kept = false,
                )
            }
        }
    }

    /** The pack is already saved. A refresh failure must not look like a bad file. */
    private suspend fun refreshDevicesAfterImport() {
        try {
            app.devices.refresh(
                app.config.fleets,
                app.config.settings.staleSec,
                policy = app.config.settings.detectionPolicy(),
                decaySec = app.config.settings.decaySec,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The live list catches up on the next hear.
        }
    }
}

internal fun newerNote(newer: Boolean, message: String): String {
    if (!newer) return message
    val note = "This file is newer than this app. Signatures this app understands were kept."
    return if (message.isBlank()) note else "$message $note"
}

internal fun catalogFetchFailure(failed: CatalogRemote.Fetch.Failed): ExportUi {
    val reason = failed.reason
    val code = failed.code
    val titleKey = if (reason == CatalogRemote.Reason.REACH) {
        "ui.could.not.reach.github"
    } else {
        "ui.could.not.import.catalog"
    }
    val bodyKey = when (reason) {
        CatalogRemote.Reason.REACH -> "ui.could.not.reach.catalog"
        CatalogRemote.Reason.TRUST -> "settings.layer.failed.trust"
        CatalogRemote.Reason.SERVER ->
            if (code > 0) "catalog.failed.server.code" else "catalog.failed.server"
        CatalogRemote.Reason.LARGE -> "language.err.large"
        CatalogRemote.Reason.EMPTY -> "catalog.failed.empty"
        CatalogRemote.Reason.OTHER -> "ui.could.not.import.catalog.2"
    }
    val body = if (reason == CatalogRemote.Reason.SERVER && code > 0) {
        LanguageText.text(bodyKey, mapOf("code" to code.toString()))
    } else {
        LanguageText.text(bodyKey)
    }
    val text = body.trim() + " " + LanguageText.text("import.failed.kept")
    return ExportUi(errorTitle = LanguageText.text(titleKey), error = text)
}
