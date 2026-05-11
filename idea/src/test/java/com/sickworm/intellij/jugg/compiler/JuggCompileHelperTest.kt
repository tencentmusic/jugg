package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JuggCompileHelperTest {

    @Test
    fun preprocessIncrementalCompile_noFileChanges_onlyRunAsyncGitCheck() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)

        invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        verify(fixture.gitChangeChecker).checkUndetectedFilesAsync(any())
        verify(fixture.gitChangeChecker, never()).checkUndetectedFiles(any())
    }

    @Test
    fun preprocessIncrementalCompile_hasFileChanges_onlyRunAsyncGitCheck() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(false)

        invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        verify(fixture.gitChangeChecker).checkUndetectedFilesAsync(any())
        verify(fixture.gitChangeChecker, never()).checkUndetectedFiles(any())
    }

    private fun createFixture(): Fixture {
        val project = mock<Project>()
        whenever(project.basePath).thenReturn("/tmp/jugg-test")
        val pathManager = mock<JuggPathManager>()
        val juggServer = mock<JuggServer>()
        val deployTargetManager = mock<IDeployTargetManager>()
        val deployStateManager = mock<DeployStateManager>()
        val deployFileManager = mock<DeployFileManager>()
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>()
        val compileContextManager = mock<CompileContextManager>()
        val fileChangesHandler = mock<IFileChangesHandler>()
        val dependencyChangeManager = mock<IDependencyChangeManager>()
        val gradleProjectInfoLocalFetchManager = mock<GradleProjectInfoLocalFetchManager>()
        val gitFileChangesDetector = mock<GitFileChangesDetector>()
        val taskRunnerManager = mock<TaskRunnerManager>()
        val logger = mock<Logger>()
        val gitChangeChecker = mock<GitChangesCompileChecker>()
        val uiHandler = mock<CompileUiHandler>()
        val options = mock<JuggGradleCompileOptions>()

        whenever(uiHandler.isForceGradleCompile).thenReturn(false)
        whenever(deployHistoryManager.isLastFullCompileFailed).thenReturn(false)
        whenever(deployHistoryManager.isBuildTargetChanged(options)).thenReturn(false)
        whenever(deployFileManager.getUndeployedFiles()).thenReturn(emptyList())
        whenever(deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)

        val helper = JuggCompilerHelper(
            project = project,
            pathManager = pathManager,
            juggServer = juggServer,
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            juggRunningTaskStatusManager = juggRunningTaskStatusManager,
            compileContextManager = compileContextManager,
            fileChangesHandler = fileChangesHandler,
            dependencyChangeManager = dependencyChangeManager,
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitFileChangesDetector = gitFileChangesDetector,
            taskRunnerManager = taskRunnerManager,
            logger = logger,
            gitChangeChecker = gitChangeChecker,
        )

        return Fixture(
            helper = helper,
            deployFileManager = deployFileManager,
            gitChangeChecker = gitChangeChecker,
            uiHandler = uiHandler,
            options = options,
        )
    }

    private fun invokePreprocessIncrementalCompile(
        helper: JuggCompilerHelper,
        options: JuggGradleCompileOptions,
        uiHandler: CompileUiHandler,
    ) {
        val method = JuggCompilerHelper::class.java.getDeclaredMethod(
            "preprocessIncrementalCompile",
            JuggGradleCompileOptions::class.java,
            CompileUiHandler::class.java,
        )
        method.isAccessible = true
        method.invoke(helper, options, uiHandler)
    }

    private data class Fixture(
        val helper: JuggCompilerHelper,
        val deployFileManager: DeployFileManager,
        val gitChangeChecker: GitChangesCompileChecker,
        val uiHandler: CompileUiHandler,
        val options: JuggGradleCompileOptions,
    )
}
