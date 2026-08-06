package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class JuggDebugSessionManagerTest {

    @Test
    fun `debug attach rejects multiple devices`() {
        val device1 = Mockito.mock(IDevice::class.java)
        val device2 = Mockito.mock(IDevice::class.java)
        val compat = RecordingCompat()
        val uiHandler = CapturingCompileUiHandler()
        val manager = createManager(listOf(device1, device2), compat)

        manager.attachAfterSuccessfulRun(successRunResult, uiHandler)

        assertTrue(compat.attachCount == 0)
        assertTrue(uiHandler.messages.any { it.contains("does not support multiple devices") })
        assertTrue(uiHandler.showRunWindowCalled)
    }

    @Test
    fun `debug attach reports compat failure to run output`() {
        val device = Mockito.mock(IDevice::class.java)
        val compat = RecordingCompat(error = UnsupportedOperationException("debug API unavailable"))
        val uiHandler = CapturingCompileUiHandler()
        val manager = createManager(listOf(device), compat)

        manager.attachAfterSuccessfulRun(successRunResult, uiHandler)

        assertTrue(uiHandler.messages.any { it.contains("debug API unavailable") })
        assertTrue(uiHandler.showRunWindowCalled)
    }

    @Test
    fun `debug attach retries when app process is not ready yet`() {
        val device = Mockito.mock(IDevice::class.java)
        val compat = RecordingCompat(
            errors = mutableListOf(IllegalStateException("App process not found for package com.example.app."))
        )
        val retryController = RecordingRetryController(retryCount = 1)
        val uiHandler = CapturingCompileUiHandler()
        val manager = createManager(listOf(device), compat, retryController)

        manager.attachAfterSuccessfulRun(successRunResult, uiHandler)

        assertTrue(compat.attachCount == 2)
        assertTrue(retryController.beforeRetryCount == 1)
        assertTrue(uiHandler.messages.isEmpty())
    }

    @Test
    fun `debug attach logs native debugger attach checkpoints`() {
        val device = Mockito.mock(IDevice::class.java)
        val compat = RecordingCompat()
        val uiHandler = CapturingCompileUiHandler()
        val logger = Mockito.mock(Logger::class.java)
        val manager = createManager(listOf(device), compat, logger = logger)

        manager.attachAfterSuccessfulRun(successRunResult, uiHandler)

        val inOrder = Mockito.inOrder(logger)
        inOrder.verify(logger).info("\nStart Debugger attaching.")
        inOrder.verify(logger).info("Waiting for com.example.app to enter debugger WAITING state.")
        inOrder.verify(logger).info("\nDebugger attached.")
    }

    @Test
    fun `debug attach is skipped when run failed`() {
        val device = Mockito.mock(IDevice::class.java)
        val compat = RecordingCompat()
        val uiHandler = CapturingCompileUiHandler()
        val manager = createManager(listOf(device), compat)

        manager.attachAfterSuccessfulRun(RunResult.FAILED, uiHandler)

        assertTrue(compat.attachCount == 0)
        assertTrue(uiHandler.messages.isEmpty())
    }

    private fun createManager(
        devices: List<IDevice>,
        compat: IAsDeployerCompat,
        retryController: IDebugAttachRetryController = DebugAttachRetryController(),
        logger: Logger = Mockito.mock(Logger::class.java),
    ): JuggDebugSessionManager {
        val project = Mockito.mock(Project::class.java)
        val deployTargetManager = FakeDeployTargetManager(devices)
        return JuggDebugSessionManager(project, deployTargetManager, compat, logger, retryController)
    }

    private class FakeDeployTargetManager(
        private val devices: List<IDevice>,
    ) : IDeployTargetManager {
        override fun setApks(apks: List<ApkInfo>) = Unit
        override fun getApks(): List<ApkInfo> = listOf(ApkInfo(File("app-debug.apk"), "com.example.app"))
        override fun getSelectedDevices(): List<IDevice> = devices
        override fun getConnectedDevices(): List<IDevice> = devices
        override fun startApp(device: IDevice): Boolean = true
        override fun restartApp(device: IDevice): Boolean = true
        override fun stopApp(device: IDevice): Boolean = true
        override fun isAppForeground(device: IDevice): Boolean = true
        override fun getPackageName(): String = "com.example.app"
    }

    private class RecordingCompat(
        private val error: Throwable? = null,
        private val errors: MutableList<Throwable> = mutableListOf(),
    ) : IAsDeployerCompat by Mockito.mock(IAsDeployerCompat::class.java) {
        var attachCount = 0
            private set

        override fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
            attachCount++
            if (errors.isNotEmpty()) {
                throw errors.removeAt(0)
            }
            error?.let { throw it }
        }
    }

    private class RecordingRetryController(
        private val retryCount: Int,
    ) : IDebugAttachRetryController {
        var beforeRetryCount = 0
            private set

        override fun shouldRetry(attempt: Int, error: Throwable): Boolean {
            return attempt <= retryCount
        }

        override fun beforeRetry(attempt: Int, error: Throwable) {
            beforeRetryCount++
        }
    }

    private class CapturingCompileUiHandler : CompileUiHandler {
        val messages = mutableListOf<String>()
        var showRunWindowCalled = false
        override var isForceGradleCompile: Boolean = false
        override val isSkipDeploy: Boolean = false
        override val isAlwaysRestartApp: Boolean = false
        override val isCanceled: Boolean = false
        override var processHandler: IProcessHandler = IProcessHandler.DEFAULT
        override var progressIndicator: ProgressIndicator = DumbProgressIndicator()
        override var testEventSinkFactory: ((String, Boolean) -> ((InstrumentationEvent) -> Unit)?)? = null
        override fun createCompileStatusHolder(): CompileStatusHolder = CompileStatusHolder.DEFAULT
        override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener =
            IGradleCompileClient.TerminalOutputListener.DEFAULT
        override fun confirmFallbackWhenNoFileChanges(): ConfirmResult = ConfirmResult.NEGATIVE
        override fun confirmBuildChanges(project: Project, changedBuildFiles: List<Pair<File, File?>>): BuildChangesConfirmResult =
            BuildChangesConfirmResult.FALLBACK
        override fun confirmDependencyChanges(runResult: DependencyDiffResultSet?): ConfirmResult = ConfirmResult.POSITIVE
        override fun confirmEmbeddedToApk(): ConfirmResult = ConfirmResult.POSITIVE
        override fun updateIndicatorText(text: String) = Unit
        override fun listenCancelAction(listener: (() -> Unit)?) = Unit
        override fun notifyByBalloon(text: String) {
            messages += text
        }
        override fun ensureRunWindowCreated() = Unit
        override fun showRunWindow() {
            showRunWindowCalled = true
        }
        override fun onDeployUiMessage(message: String) {
            messages += message
        }
        override fun cancel() = Unit
    }

    companion object {
        private val successRunResult = RunResult(
            isGradleCompile = false,
            isCompileSuccess = true,
            isDeploySuccess = true,
            isCancel = false,
        )
    }
}
