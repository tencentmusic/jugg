package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.CompileTaskResult
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.JuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.ILastCompileProjectRegistry
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class JuggRunningTaskTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun `failure upload requires enabled eligible and unmatched exclusion`() {
        assertFalse(shouldAutoUploadFailureLogs(false, true, null, "compile failed"))
        assertFalse(shouldAutoUploadFailureLogs(true, false, null, "compile failed"))
        assertTrue(shouldAutoUploadFailureLogs(true, true, null, "compile failed"))
        assertTrue(shouldAutoUploadFailureLogs(true, true, "", "compile failed"))
        assertTrue(shouldAutoUploadFailureLogs(true, true, "OutOfMemoryError", "compile failed"))
        assertFalse(shouldAutoUploadFailureLogs(true, true, "(?i)compile failed", "Compile Failed"))
    }

    @Test
    fun `invalid exclusion regex disables failure upload`() {
        assertFalse(shouldAutoUploadFailureLogs(true, true, "[", "compile failed"))
    }

    @Test
    fun `failure upload eligibility follows final run boundary`() {
        val compileFailed = RunResult(false, false, false, false)
        val deployFailed = RunResult(false, true, false, false)
        val canceled = RunResult(false, false, false, true)
        val succeeded = RunResult(false, true, true, false)

        assertTrue(isFailureLogUploadEligible(compileFailed, hasAttemptedDeploy = false))
        assertTrue(isFailureLogUploadEligible(deployFailed, hasAttemptedDeploy = true))
        assertFalse(isFailureLogUploadEligible(deployFailed, hasAttemptedDeploy = false))
        assertFalse(isFailureLogUploadEligible(canceled, hasAttemptedDeploy = true))
        assertFalse(isFailureLogUploadEligible(succeeded, hasAttemptedDeploy = true))
    }

    @Test
    fun `deploy failure reason excludes successful devices`() {
        val results = listOf(
            DeployTaskResult(isSuccess = true, costTime = 1),
            DeployTaskResult(isSuccess = false, costTime = 1, failedReason = "real failure"),
        )

        assertEquals("real failure", buildDeployFailureReason(results))
    }

    @Test
    fun `final compile failure triggers automatic log upload once`() {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(temporaryFolder.root.absolutePath)
        JuggLogger.register(project, temporaryFolder.newFolder("logs"))
        try {
            val server = Mockito.mock(JuggServer::class.java)
            val compileHelper = Mockito.mock(JuggCompilerHelper::class.java)
            whenever(compileHelper.compile(any(), any(), any())).thenReturn(
                CompileTaskResult.incrementalFailed(false, "compile failed"),
            )
            val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
            Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
            val dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java)
            val lastCompileProjectRegistry = Mockito.mock(ILastCompileProjectRegistry::class.java)
            val options = Mockito.mock(JuggGradleCompileOptions::class.java)
            Mockito.`when`(options.projectRootPath).thenReturn(temporaryFolder.root.absolutePath)
            val task = JuggRunningTask(
                options = options,
                project = project,
                juggServer = server,
                deployTargetManager = deployTargetManager,
                dependencyChangeManager = dependencyChangeManager,
                statusManager = JuggRunningTaskStatusManager(),
                deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
                juggCompileHelper = compileHelper,
                juggDeployHelper = Mockito.mock(JuggDeployerHelper::class.java),
                initIncrementalCompileTask = {},
                baseCompileUiHandler = CompileUiHandler.DEFAULT,
                eventModel = JuggControlPanelModel(),
                lastCompileProjectRegistry = lastCompileProjectRegistry,
                logger = JuggLogger.getInstance(project, "JuggRunningTaskTest"),
                autoUploadFailureLogs = true,
            )

            task.run(Mockito.mock(ProgressIndicator::class.java))

            Mockito.verify(server, Mockito.times(1)).uploadFailureLogs()
        } finally {
            JuggLogger.unregister(project)
        }
    }
}
