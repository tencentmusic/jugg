package com.sickworm.intellij.jugg.manager

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.context.IdeaCompileEnvironmentSource
import com.sickworm.intellij.jugg.compiler.context.IdeaProjectModelSource
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IdeSyncProblemResolver
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.project.runtime.ProjectCustomConfigManager
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.change.FileChangeManager
import com.sickworm.intellij.jugg.project.change.IFileChangeMonitor
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.IdeaHotUpdateCoordinator
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.BeforeClass
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JuggManagerFullBuildFlowTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }

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
        whenever(compileContextManager.getProjectInfo()).thenReturn(
            JuggProjectInfo(emptyMap(), agpR8Classpath = null)
        )
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
            runtimeInfo = RuntimeInfo("idea", "test", "test", "0"),
            juggServer = mock<JuggServer>(),
            ideaHotUpdateCoordinator = mock<IdeaHotUpdateCoordinator>(),
            fileChangesHandler = mock<IFileChangesHandler>(),
            fileChangesDetector = mock<IFileChangeMonitor>(),
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = mock<IDeployTargetManager>(),
            deployStateManager = mock<DeployStateManager>(),
            taskRunnerManager = mock<TaskRunnerManager>(),
            deploymentService = mock<JuggDeploymentService>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            deployFileManager = mock<DeployFileManager>(),
            compileEnvironmentSource = mock<IdeaCompileEnvironmentSource>(),
            projectModelSource = mock<IdeaProjectModelSource>(),
            compileContextManager = compileContextManager,
            juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>(),
            dependencyChangeManager = mock<IDependencyChangeManager>(),
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitFileChangesDetector = mock<GitFileChangesDetector>(),
            fileChangeManager = mock<FileChangeManager>(),
            juggDeployerHelper = mock<JuggDeployerHelper>(),
            juggCompilerHelper = juggCompilerHelper,
            projectCustomConfigManager = mock<ProjectCustomConfigManager>(),
            ideSyncProblemResolver = mock<IdeSyncProblemResolver>(),
        )

        manager.initIncrementalCompileAfterFullBuild(1L, options)

        inOrder(gradleProjectInfoLocalFetchManager, compileContextManager, juggCompilerHelper) {
            verify(gradleProjectInfoLocalFetchManager).waitForRemoteInitUpdate()
            verify(compileContextManager).getProjectInfo()
            verify(juggCompilerHelper).fetchClasspath(any(), any(), anyOrNull(), any())
        }
        verify(deployHistoryManager, never()).deleteDeployHistory()
    }

    @Test
    fun initIncrementalCompileAfterFullBuild_ideFallback_prefersGradleLibraries() {
        val project = mock<Project>()
        whenever(project.basePath).thenReturn(temporaryFolder.root.absolutePath)
        val pathManager = mock<JuggPathManager>()
        whenever(pathManager.projectDir).thenReturn(temporaryFolder.root)
        whenever(pathManager.stagingDir).thenReturn(temporaryFolder.newFolder("staging-fallback"))
        whenever(pathManager.compileRootDir).thenReturn(temporaryFolder.newFolder("compile-fallback"))
        whenever(pathManager.gradleProjectInfoFile).thenReturn(temporaryFolder.newFile("gradle-project-info.json"))
        whenever(pathManager.markProjectInfoNeedUpdateFlagFile).thenReturn(
            temporaryFolder.root.resolve("project-info-needs-update")
        )
        val logger = mock<Logger>()
        val compileContextManager = mock<CompileContextManager>()
        val compileContext = mock<ICompileContext>()
        whenever(compileContext.logger).thenReturn(logger)
        whenever(compileContext.tempCompileDir).thenReturn(temporaryFolder.newFolder("temp-compile-fallback"))
        whenever(compileContext.modules).thenReturn(emptyMap())
        whenever(compileContext.customCompilers).thenReturn(emptyList())
        whenever(compileContextManager.compileContext).thenReturn(compileContext)
        whenever(compileContextManager.getProjectInfo()).thenReturn(
            JuggProjectInfo(emptyMap(), agpR8Classpath = null)
        )
        whenever(compileContextManager.updateCompileContext(any(), any(), any())).thenReturn(false)
        val apkInfo = ApkInfo(temporaryFolder.newFile("app.apk"), "com.example.app")
        val deployHistoryManager = mock<IDeployHistoryManager>()
        whenever(deployHistoryManager.reInitAfterFullCompiled(any(), any(), any(), any())).thenReturn(
            CompileContextInfo(listOf(apkInfo), emptyMap())
        )
        val deployTargetManager = mock<IDeployTargetManager>()
        whenever(deployTargetManager.getApks()).thenReturn(listOf(apkInfo))
        val ideSyncProblemResolver = mock<IdeSyncProblemResolver>()
        whenever(ideSyncProblemResolver.isNeedSyncAfterBuild()).thenReturn(true)
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.isRemoteCompile).thenReturn(false)
        whenever(options.buildTarget).thenReturn(BuildTarget.APP)
        whenever(options.compileCommand).thenReturn("./gradlew :app:assembleDebug")
        val taskRunnerManager = mock<TaskRunnerManager>()
        val dependencyChangeManager = mock<IDependencyChangeManager>()
        val compileEnvironmentSource = mock<IdeaCompileEnvironmentSource>()
        val gradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(
            pathManager,
            compileContextManager,
            taskRunnerManager,
            dependencyChangeManager,
            deployHistoryManager,
            compileEnvironmentSource,
            logger,
        )
        val manager = JuggManager(
            project = project,
            pathManager = pathManager,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            logger = logger,
            juggServer = mock<JuggServer>(),
            juggHotUpdateDownloader = mock<JuggHotUpdateDownloader>(),
            fileChangesHandler = mock<IFileChangesHandler>(),
            fileChangesDetector = mock<IFileChangeMonitor>(),
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = deployTargetManager,
            deployStateManager = mock<DeployStateManager>(),
            taskRunnerManager = taskRunnerManager,
            deploymentService = mock<JuggDeploymentService>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            deployFileManager = mock<DeployFileManager>(),
            compileEnvironmentSource = compileEnvironmentSource,
            projectModelSource = mock<IdeaProjectModelSource>(),
            compileContextManager = compileContextManager,
            juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>(),
            dependencyChangeManager = dependencyChangeManager,
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitFileChangesDetector = mock<GitFileChangesDetector>(),
            fileChangeManager = mock<FileChangeManager>(),
            juggDeployerHelper = mock<JuggDeployerHelper>(),
            juggCompilerHelper = mock<JuggCompilerHelper>(),
            projectCustomConfigManager = mock<ProjectCustomConfigManager>(),
            ideSyncProblemResolver = ideSyncProblemResolver,
        )

        manager.initIncrementalCompileAfterFullBuild(1L, options)

        verify(compileContextManager).updateCompileContext(eq(true), eq(true), any())
    }
}
