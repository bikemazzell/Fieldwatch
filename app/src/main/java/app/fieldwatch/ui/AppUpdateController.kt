package app.fieldwatch.ui

import android.content.Context
import android.content.Intent
import app.fieldwatch.BuildConfig
import app.fieldwatch.data.AppUpdateClient
import app.fieldwatch.data.AppUpdateInstall
import app.fieldwatch.data.PlaceLookup
import app.fieldwatch.domain.AppUpdate
import app.fieldwatch.domain.AppUpdateDecision
import app.fieldwatch.domain.AppUpdateNote
import app.fieldwatch.domain.AppUpdatePrompt
import app.fieldwatch.domain.AppUpdateSkip
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppUpdateController(private val app: Context, private val scope: CoroutineScope) {
    private val _appUpdate = MutableStateFlow<AppUpdatePrompt?>(null)
    val appUpdate: StateFlow<AppUpdatePrompt?> = _appUpdate.asStateFlow()
    private val _appUpdateIntent = MutableStateFlow<Intent?>(null)
    val appUpdateIntent: StateFlow<Intent?> = _appUpdateIntent.asStateFlow()
    private val appUpdateLock = Mutex()
    private var offeredUpdate: AppUpdateNote? = null
    private var downloadedApk: File? = null
    private var appUpdateJob: Job? = null
    private var appUpdateToken = 0

    fun checkAtStart() {
        scope.launch(Dispatchers.IO) {
            try {
                val prompt = lookForAppUpdate(fromSettings = false)
                if (prompt is AppUpdatePrompt.Offer && _appUpdate.value == null) {
                    offeredUpdate = prompt.note
                    _appUpdate.value = prompt
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No answer from GitHub. The app still opens.
            }
        }
    }

    fun checkForAppUpdate() {
        if (appUpdateJob?.isActive == true) return
        val token = ++appUpdateToken
        appUpdateJob = scope.launch {
            _appUpdate.value = AppUpdatePrompt.Checking
            val prompt = try {
                lookForAppUpdate(fromSettings = true) ?: AppUpdatePrompt.Unreachable
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AppUpdatePrompt.Unreachable
            }
            if (token != appUpdateToken) return@launch
            if (_appUpdate.value !is AppUpdatePrompt.Checking) return@launch
            if (prompt is AppUpdatePrompt.Offer) offeredUpdate = prompt.note
            _appUpdate.value = prompt
        }
    }

    fun installOfferedUpdate() {
        val note = (_appUpdate.value as? AppUpdatePrompt.Offer)?.note ?: offeredUpdate ?: return
        if (_appUpdate.value is AppUpdatePrompt.Downloading) return
        offeredUpdate = note
        val token = ++appUpdateToken
        appUpdateJob = scope.launch {
            val cached = try {
                withContext(Dispatchers.IO) {
                    downloadedApk?.takeIf {
                        AppUpdateInstall.trusted(app, it, note.versionCode, note.versionName)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (token != appUpdateToken) return@launch
            if (cached != null) {
                presentDownloadedUpdate(cached)
                return@launch
            }
            _appUpdate.value = AppUpdatePrompt.Downloading
            val file = try {
                withContext(Dispatchers.IO) {
                    val got = AppUpdateClient.downloadApk(
                        app.cacheDir,
                        userAgent = updateUserAgent(),
                    ) { token == appUpdateToken }
                    if (token != appUpdateToken) return@withContext null
                    if (got == null || !AppUpdateInstall.trusted(app, got, note.versionCode, note.versionName)) {
                        got?.delete()
                        null
                    } else {
                        got
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (token != appUpdateToken) return@launch
            if (file == null) {
                _appUpdate.value = AppUpdatePrompt.Failed
                return@launch
            }
            presentDownloadedUpdate(file)
        }
    }

    private fun presentDownloadedUpdate(file: File) {
        downloadedApk = file
        if (!AppUpdateInstall.allowed(app)) {
            _appUpdate.value = AppUpdatePrompt.Allow
            return
        }
        _appUpdateIntent.value = AppUpdateInstall.viewIntent(app, file)
        _appUpdate.value = null
    }

    fun laterAppUpdate() {
        val code = (_appUpdate.value as? AppUpdatePrompt.Offer)?.note?.versionCode ?: offeredUpdate?.versionCode
        appUpdateToken++
        appUpdateJob?.cancel()
        appUpdateJob = null
        offeredUpdate = null
        _appUpdate.value = null
        if (code != null) {
            scope.launch(Dispatchers.IO) { AppUpdateSkip.write(app.filesDir, code) }
        }
    }

    fun dismissAppUpdate() {
        when (_appUpdate.value) {
            is AppUpdatePrompt.Offer -> laterAppUpdate()
            is AppUpdatePrompt.Checking, is AppUpdatePrompt.Downloading -> {
                appUpdateToken++
                appUpdateJob?.cancel()
                appUpdateJob = null
                _appUpdate.value = null
            }
            else -> _appUpdate.value = null
        }
    }

    fun openUnknownInstallSettings() {
        if (_appUpdate.value !is AppUpdatePrompt.Allow) return
        _appUpdateIntent.value = AppUpdateInstall.settingsIntent(app)
    }

    /** After the unknown-apps screen, the notes come back so Update now can be tapped again. */
    fun onAppUpdateResume() {
        val note = offeredUpdate ?: return
        if (_appUpdate.value !is AppUpdatePrompt.Allow) return
        if (AppUpdateInstall.allowed(app)) _appUpdate.value = AppUpdatePrompt.Offer(note)
    }

    fun consumeAppUpdateIntent() {
        _appUpdateIntent.value = null
    }

    fun appUpdateIntentFailed() {
        _appUpdateIntent.value = null
        _appUpdate.value = AppUpdatePrompt.Failed
    }

    private suspend fun lookForAppUpdate(fromSettings: Boolean): AppUpdatePrompt? = withContext(Dispatchers.IO) {
        appUpdateLock.withLock {
            if (!PlaceLookup.online(app)) {
                return@withLock if (fromSettings) AppUpdatePrompt.Unreachable else null
            }
            val text = AppUpdateClient.fetchNote(userAgent = updateUserAgent())
            val note = text?.let(AppUpdate::parse)
            if (note == null) return@withLock if (fromSettings) AppUpdatePrompt.Unreachable else null
            val skipped = if (fromSettings) null else AppUpdateSkip.read(app.filesDir)
            when (val decision = AppUpdate.decide(BuildConfig.VERSION_CODE, note, skipped)) {
                is AppUpdateDecision.Newer -> AppUpdatePrompt.Offer(decision.note)
                AppUpdateDecision.Quiet -> if (fromSettings) AppUpdatePrompt.Current(note.versionName) else null
            }
        }
    }

    private fun updateUserAgent(): String = "Fieldwatch/${BuildConfig.VERSION_NAME}"
}
