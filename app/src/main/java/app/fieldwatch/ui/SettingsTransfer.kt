package app.fieldwatch.ui

import android.net.Uri
import app.fieldwatch.BuildConfig
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.data.ImportFileException
import app.fieldwatch.data.ImportFiles
import app.fieldwatch.domain.AppSettings
import app.fieldwatch.domain.CatalogNeedsNewerApp
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.domain.SettingsExchange
import app.fieldwatch.domain.SettingsPack
import app.fieldwatch.domain.anySensorAlert
import app.fieldwatch.domain.detectionPolicy
import app.fieldwatch.domain.gnssSettings
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsTransfer(
    private val app: FieldwatchApp,
    private val scope: CoroutineScope,
    private val export: MutableStateFlow<ExportUi>,
    private val imports: ImportNotices,
) {
    private val _pendingSettingsImport = MutableStateFlow<PendingSettingsImport?>(null)
    val pendingSettingsImport: StateFlow<PendingSettingsImport?> = _pendingSettingsImport

    fun suggestedSettingsName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US)
            .format(Date())
        return "fieldwatch-settings-$stamp.json"
    }

    private fun settingsPackJson(): String {
        val cfg = app.config.config.value
        return SettingsExchange.encode(
            SettingsExchange.pack(
                settings = cfg.settings,
                filter = cfg.filter,
                presets = cfg.presets,
                watchlist = cfg.watchlist,
                hiddenPresetIds = cfg.hiddenPresetIds,
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
            ),
        )
    }

    fun startSettingsShare() {
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) { settingsPackJson() }
                val dir = File(app.cacheDir, "settings").apply { mkdirs() }
                val file = File(dir, suggestedSettingsName())
                withContext(Dispatchers.IO) { file.writeText(json) }
                fileShare(app, file, "application/json", "settings", LanguageText.text("ui.fieldwatch.settings"))
            }.onSuccess { intent ->
                export.value = ExportUi(share = intent, shareTitle = LanguageText.text("ui.fieldwatch.settings"))
            }.onFailure { err ->
                export.value = ExportUi(
                    error = err.message ?: LanguageText.text("ui.could.not.export.settings"),
                    errorTitle = LanguageText.text("ui.could.not.export.settings"),
                )
            }
        }
    }

    fun saveSettingsToUri(uri: Uri) {
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) { settingsPackJson() }
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error(LanguageText.text("ui.could.not.write.location"))
                }
            }.onSuccess {
                export.value = ExportUi(
                    noticeTitle = LanguageText.text("ui.settings.saved"),
                    noticeMessage = LanguageText.text("ui.pack.was.written.folder.2"),
                )
            }.onFailure { err ->
                export.value = ExportUi(
                    error = err.message ?: LanguageText.text("ui.could.not.save.settings"),
                    errorTitle = LanguageText.text("ui.could.not.save.settings"),
                )
            }
        }
    }

    fun importSettingsFromUri(uri: Uri) {
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { imports.readText(uri, ImportFiles.SETTINGS_MAX) }
                val pack = SettingsExchange.parse(text)
                val warning = SettingsExchange.takImportWarning(app.config.settings, pack.settings)
                if (warning != null) {
                    _pendingSettingsImport.value = PendingSettingsImport(pack, warning)
                } else {
                    finishSettingsImport(pack)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: CatalogNeedsNewerApp) {
                imports.showCatalogNeedsApp()
            } catch (e: ImportFileException) {
                imports.showProblem("ui.could.not.import.settings", LanguageText.text(e.key), kept = true)
            } catch (e: IllegalArgumentException) {
                imports.showProblem(
                    "ui.could.not.import.settings",
                    e.message ?: LanguageText.text("ui.could.not.import.settings"),
                    kept = true,
                )
            } catch (e: IllegalStateException) {
                imports.showProblem(
                    "ui.could.not.import.settings",
                    e.message ?: LanguageText.text("ui.could.not.import.settings"),
                    kept = true,
                )
            } catch (_: Exception) {
                imports.showProblem(
                    "ui.could.not.import.settings",
                    LanguageText.text("import.failed.save"),
                    kept = false,
                )
            }
        }
    }

    fun confirmPendingSettingsImport() {
        val pending = _pendingSettingsImport.value ?: return
        _pendingSettingsImport.value = null
        scope.launch {
            try {
                finishSettingsImport(pending.pack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                imports.showProblem(
                    "ui.could.not.import.settings",
                    e.message ?: LanguageText.text("ui.could.not.import.settings"),
                    kept = true,
                )
            } catch (_: Exception) {
                imports.showProblem(
                    "ui.could.not.import.settings",
                    LanguageText.text("import.failed.save"),
                    kept = false,
                )
            }
        }
    }

    fun dismissPendingSettingsImport() {
        _pendingSettingsImport.value = null
    }

    private suspend fun finishSettingsImport(pack: SettingsPack) {
        val prev = app.config.settings
        val result = app.config.importSettings(pack)
        if (result.error != null) error(result.error)
        if (app.gnssProfile.profile.value == null && app.config.settings.gnssMonitor) {
            app.config.update { it.copy(settings = it.settings.copy(gnssMonitor = false)) }
        }
        val next = app.config.settings
        app.logs.configure(next.logFormat, next.logRotateKb, next.loggingEnabled)
        if (prev.tagLocation != next.tagLocation) app.syncLocationUpdates()
        if (prev.gnssSettings() != next.gnssSettings() || prev.tagLocation != next.tagLocation) {
            app.syncGnss()
        }
        if (prev.intensity != next.intensity && app.devices.stats.value.scanning) {
            app.startScanning()
        }
        app.devices.refresh(
            app.config.fleets,
            next.staleSec,
            policy = next.detectionPolicy(),
            decaySec = next.decaySec,
        )
        if (next.alertVoice || next.gnssVoice || next.trustedVoice || next.cellVoice || next.swarmVoice || next.trackerVoice || next.anySensorAlert()) {
            app.alerter.prepareVoice()
        }
        if (prev.trackerShade != next.trackerShade) {
            app.syncTrackerShade(app.tracker.notice.value)
        }
        app.announceCell()
        if (layerAlertsChanged(prev, next)) {
            app.syncFlock(force = prev.flockLayer != next.flockLayer || prev.sensorLayer != next.sensorLayer)
        }
        export.value = ExportUi(
            noticeTitle = LanguageText.text("ui.settings.imported"),
            noticeMessage = result.summary(),
        )
    }
}

internal fun layerAlertsChanged(prev: AppSettings, next: AppSettings): Boolean {
    return prev.flockLayer != next.flockLayer ||
        prev.sensorLayer != next.sensorLayer ||
        prev.flockAlert != next.flockAlert ||
        prev.speedAlert != next.speedAlert ||
        prev.redAlert != next.redAlert ||
        prev.gunshotAlert != next.gunshotAlert ||
        prev.cameraAlert != next.cameraAlert
}
