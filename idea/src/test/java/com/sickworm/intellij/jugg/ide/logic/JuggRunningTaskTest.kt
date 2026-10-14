package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.CompileTaskResult
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.TestGlobal
import java.io.File
import javax.swing.SwingUtilities

class JuggRunningTaskTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    companion object {
        @BeforeClass @JvmStatic
        fun initTestEnvironment() = TestGlobal.init()
    }

    @Test
    fun `device exception after successful compilation remains a deployment failure`() {
        listOf(false, true).forEach { gradle ->
            val compiler = mock<JuggCompilerHelper>()
            whenever(compiler.compile(any(), any(), any())).thenReturn(CompileTaskResult(true, gradle, false, 0))
            val targets = mock<IDeployTargetManager>()
            whenever(targets.getTargetDevices(null)).thenThrow(IllegalStateException("device lookup failed"))

            val result = runFailureFlow(compiler, targets)

            assertTrue(result.isCompileSuccess)
            assertFalse(result.isDeploySuccess)
            assertEquals(gradle, result.isGradleCompile)
            assertEquals("device lookup failed", result.failedReason)
        }
    }

    @Test
    fun `compiler exception remains a compilation failure`() {
        val compiler = mock<JuggCompilerHelper>()
        whenever(compiler.compile(any(), any(), any())).thenThrow(IllegalStateException("compiler failed"))

        val result = runFailureFlow(compiler, mock())

        assertFalse(result.isCompileSuccess)
        assertFalse(result.isDeploySuccess)
        assertEquals("compiler failed", result.failedReason)
    }

    @Test
    fun `exception during fallback does not reuse the previous successful compilation`() {
        val compiler = mock<JuggCompilerHelper>()
        whenever(compiler.compile(any(), any(), any())).thenReturn(CompileTaskResult(true, false, false, 0))
            .thenThrow(IllegalStateException("fallback compiler failed"))
        val device = mock<IDevice>()
        whenever(device.name).thenReturn("test device")
        whenever(device.serialNumber).thenReturn("test-serial")
        whenever(device.version).thenReturn(com.sickworm.intellij.jugg.deploy.api.AndroidVersion(30))
        whenever(device.clientCount).thenReturn(0)
        val targets = mock<IDeployTargetManager>()
        whenever(targets.getTargetDevices(null)).thenReturn(listOf(device))
        val deployer = mock<JuggDeployerHelper>()
        whenever(deployer.deploy(any())).thenReturn(DeployTaskResult(false, 0, true,
            JuggDeployData.DeployType.HOT_RELOAD, "deploy failed"))
        val previousFallback = JuggSettings.isAutoFallbackToGradleWhenDeployError
        try {
            JuggSettings.isAutoFallbackToGradleWhenDeployError = true

            val result = runFailureFlow(compiler, targets, deployer)

            assertFalse(result.toString(), result.isCompileSuccess)
            assertEquals("fallback compiler failed", result.failedReason)
            Mockito.verify(compiler, Mockito.times(2)).compile(any(), any(), any())
        } finally {
            JuggSettings.isAutoFallbackToGradleWhenDeployError = previousFallback
        }
    }

    @Test
    fun `normal missing device result preserves successful compilation`() {
        val compiler = mock<JuggCompilerHelper>()
        whenever(compiler.compile(any(), any(), any())).thenReturn(CompileTaskResult(true, true, false, 0))
        val targets = mock<IDeployTargetManager>()
        whenever(targets.getTargetDevices(null)).thenReturn(emptyList())

        val result = runFailureFlow(compiler, targets)

        assertTrue(result.isCompileSuccess)
        assertFalse(result.isDeploySuccess)
        assertEquals("No device found. Stop installing.", result.failedReason)
    }

    private fun runFailureFlow(compiler: JuggCompilerHelper, targets: IDeployTargetManager,
        deployer: JuggDeployerHelper = mock()): RunResult {
        val project = mock<Project>()
        val projectPath = temporaryFolder.newFolder().absolutePath
        whenever(project.basePath).thenReturn(projectPath)
        val toolWindowManager = mock<ToolWindowManager>()
        whenever(project.getService(ToolWindowManager::class.java)).thenReturn(toolWindowManager)
        JuggLogger.register(project, File(project.basePath!!, "logs"))
        val handler = mock<CompileUiHandler>()
        val processHandler = mock<IProcessHandler>()
        val progressIndicator = mock<ProgressIndicator>()
        whenever(handler.processHandler).thenReturn(processHandler)
        whenever(handler.progressIndicator).thenReturn(progressIndicator)
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.projectRootPath).thenReturn(projectPath)
        val task = JuggRunningTask(options, project, mock(), targets, mock(), mock(), mock(), compiler,
            deployer, {}, handler, JuggControlPanelModel(),
            lastCompileProjectRegistry = mock(), logger = mock(),
            taskRunnerManager = com.sickworm.intellij.jugg.createTestTaskRunnerManager(
                com.sickworm.intellij.jugg.project.runtime.JuggPathManager(File(projectPath))),
            projectCustomConfigManager = mock(), recoverAfterRuntimeOwnerChange = { false })
        try {
            task.run(mock<ProgressIndicator>())
            SwingUtilities.invokeAndWait { }
            val result = org.mockito.kotlin.argumentCaptor<RunResult>()
            Mockito.verify(handler).onEnd(result.capture())
            return result.firstValue
        } finally {
            JuggLogger.unregister(project)
        }
    }

    @Test
    fun `compile event title should distinguish selected path and no-op compile`() {
        assertEquals("Incremental compile completed", buildCompileEventTitle(false, true, false, true))
        assertEquals("Gradle compile completed", buildCompileEventTitle(true, true, false, true))
        assertEquals("Incremental compile failed", buildCompileEventTitle(false, false, false, true))
        assertEquals("Gradle compile failed", buildCompileEventTitle(true, false, false, true))
        assertEquals("Incremental compile canceled", buildCompileEventTitle(false, false, true, true))
        assertEquals("No compile needed", buildCompileEventTitle(false, true, false, false))
    }

    @Test
    fun `gradle compile install success uses BUILD_AND_INSTALL headline`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.INSTALL,
            isGradleCompile = true,
            totalTimeMillis = 13_000,
        )

        assertEquals("\nGradle BUILD_AND_INSTALL SUCCESSFUL in 13s.", lines.headline)
        assertEquals("App launched.", lines.followUp)
    }

    @Test
    fun `incremental recover install success uses Jugg INSTALL headline`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.INSTALL,
            isGradleCompile = false,
            totalTimeMillis = 2_827,
        )

        assertEquals("\nJugg INSTALL SUCCESSFUL in 2s.", lines.headline)
        assertEquals("App launched.", lines.followUp)
    }

    @Test
    fun `incremental hot reload success keeps Jugg deploy headline`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.HOT_RELOAD,
            isGradleCompile = false,
            totalTimeMillis = 5_000,
        )

        assertEquals("\nJugg HOT_RELOAD SUCCESSFUL in 5s.", lines.headline)
        assertEquals("App deployed.", lines.followUp)
    }

    @Test
    fun `hot fix success reports app restarted`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.HOT_FIX,
            isGradleCompile = false,
            totalTimeMillis = 1_000,
        )

        assertEquals("\nJugg HOT_FIX SUCCESSFUL in 1s.", lines.headline)
        assertEquals("App restarted.", lines.followUp)
    }

    @Test
    fun `first task start creates run tool window without activating it`() {
        val handler = Mockito.mock(CompileUiHandler::class.java)

        prepareRunToolWindowOnTaskStart(isFirstTimeRun = true, handler)

        Mockito.verify(handler).ensureRunWindowCreated()
        Mockito.verify(handler, Mockito.never()).showRunWindow()
    }

    @Test
    fun `non-first task start does not touch run tool window`() {
        val handler = Mockito.mock(CompileUiHandler::class.java)

        prepareRunToolWindowOnTaskStart(isFirstTimeRun = false, handler)

        Mockito.verify(handler, Mockito.never()).ensureRunWindowCreated()
        Mockito.verify(handler, Mockito.never()).showRunWindow()
    }

    @Test
    fun `checkAndMarkFirstRun returns true on first call and false on subsequent calls for same project`() {
        JuggRunningTask.resetHasRunProjects()
        val project1 = Mockito.mock(com.intellij.openapi.project.Project::class.java)
        Mockito.`when`(project1.basePath).thenReturn("/path/to/project1")

        assertTrue(JuggRunningTask.checkAndMarkFirstRun(project1))
        assertFalse(JuggRunningTask.checkAndMarkFirstRun(project1))

        val project2 = Mockito.mock(com.intellij.openapi.project.Project::class.java)
        Mockito.`when`(project2.basePath).thenReturn("/path/to/project2")
        assertTrue(JuggRunningTask.checkAndMarkFirstRun(project2))
    }

    @Test
    fun `normal run detaches process when task stops`() {
        assertTrue(shouldDetachProcessOnTaskStop(isProcessCanceled = false))
    }

    @Test
    fun `debug run detaches jugg process when task stops`() {
        assertTrue(shouldDetachProcessOnTaskStop(isProcessCanceled = false))
    }

    @Test
    fun `canceled run does not detach process again when task stops`() {
        assertFalse(shouldDetachProcessOnTaskStop(isProcessCanceled = true))
    }
}
