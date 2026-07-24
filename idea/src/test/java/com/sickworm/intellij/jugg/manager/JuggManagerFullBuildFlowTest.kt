package com.sickworm.intellij.jugg.manager

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IdeSyncProblemResolver
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.CustomConfigManager
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.IFileChangesDetector
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.JuggHotUpdateDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JuggManagerFullBuildFlowTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun initIncrementalCompileAfterFullBuild_remoteClasspathFetch_waitsForProjectInfoAndPreservesOldBaseline() {
        val project = mock<Project>()
        whenever(project.basePath).thenReturn(temporaryFolder.root.absolutePath)
        val pathManager = mock<JuggPathManager>()
        whenever(pathManager.projectDir).thenReturn(temporaryFolder.root)
        whenever(pathManager.stagingDir).thenReturn(temporaryFolder.newFolder("staging"))
        whenever(pathManager.compileRootDir).thenReturn(temporaryFolder.newFolder("compile"))
        val compileContextManager = mock<CompileContextManager>()
        val compileContext = mock<ICompileContext>()
        whenever(compileContext.tempCompileDir).thenReturn(temporaryFolder.newFolder("temp-compile"))
        whenever(compileContextManager.compileContext).thenReturn(compileContext)
        whenever(compileContextManager.getProjectInfo()).thenReturn(JuggProjectInfo(emptyMap()))
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val juggCompilerHelper = mock<JuggCompilerHelper>()
        whenever(juggCompilerHelper.fetchClasspath(any(), any(), anyOrNull(), any())).thenReturn(null)
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.isRemoteCompile).thenReturn(true)
        whenever(options.buildTarget).thenReturn(BuildTarget.APP)
        val coroutineScope = CoroutineScope(Dispatchers.Unconfined)
        val logger = mock<Logger>()
        val gradleProjectInfoLocalFetchManager = mock<GradleProjectInfoLocalFetchManager>()
        val manager = JuggManager(
            project = project,
            pathManager = pathManager,
            coroutineScope = coroutineScope,
            logger = logger,
            juggServer = mock<JuggServer>(),
            juggHotUpdateDownloader = mock<JuggHotUpdateDownloader>(),
            fileChangesHandler = mock<IFileChangesHandler>(),
            fileChangesDetector = mock<IFileChangesDetector>(),
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = mock<IDeployTargetManager>(),
            deployStateManager = mock<DeployStateManager>(),
            taskRunnerManager = mock<TaskRunnerManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            deployFileManager = mock<DeployFileManager>(),
            compileContextManager = compileContextManager,
            juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>(),
            dependencyChangeManager = mock<IDependencyChangeManager>(),
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitFileChangesDetector = mock<GitFileChangesDetector>(),
            juggDeployerHelper = mock<JuggDeployerHelper>(),
            juggCompilerHelper = juggCompilerHelper,
            customConfigManager = mock<CustomConfigManager>(),
            ideSyncProblemResolver = mock<IdeSyncProblemResolver>(),
        )

        manager.initIncrementalCompileAfterFullBuild(1L, options)

        inOrder(gradleProjectInfoLocalFetchManager, compileContextManager, juggCompilerHelper) {
            verify(gradleProjectInfoLocalFetchManager).waitForUpdate()
            verify(compileContextManager).getProjectInfo()
            verify(juggCompilerHelper).fetchClasspath(any(), any(), anyOrNull(), any())
        }
        verify(deployHistoryManager, never()).deleteDeployHistory()
    }
}
