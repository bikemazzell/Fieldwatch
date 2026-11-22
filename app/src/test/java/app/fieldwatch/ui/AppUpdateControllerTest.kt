package app.fieldwatch.ui

import app.fieldwatch.data.FilesDirContext
import app.fieldwatch.data.storeTempDir
import app.fieldwatch.domain.AppUpdatePrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateControllerTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val controller = AppUpdateController(FilesDirContext(storeTempDir("app-update")), scope)

    @After
    fun stop() = scope.cancel()

    @Test
    fun checkFromSettingsEndsUnreachableWhenThePhoneCannotAskForNetworkState() = runBlocking {
        controller.checkForAppUpdate()
        val prompt = withTimeout(5_000) { controller.appUpdate.first { it != null && it !is AppUpdatePrompt.Checking } }
        assertTrue(prompt is AppUpdatePrompt.Unreachable)
    }

    @Test
    fun failedInstallIntentClearsTheIntentAndShowsFailureUntilDismissed() {
        controller.appUpdateIntentFailed()
        assertNull(controller.appUpdateIntent.value)
        assertTrue(controller.appUpdate.value is AppUpdatePrompt.Failed)
        controller.dismissAppUpdate()
        assertNull(controller.appUpdate.value)
    }
}
