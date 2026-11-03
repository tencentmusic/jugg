package com.sickworm.intellij.jugg.manager

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IdeSyncProblemResolver
import com.sickworm.intellij.jugg.mock.TestGlobal
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
    fun deselectingCompiledInputReleasesCompilerStateButOrdinarySelectionKeepsIt() {
        val files = mock<DeployFileManager>()
        val source = temporaryFolder.newFile("strings.xml")
        val changed = com.sickworm.intellij.jugg.project.ChangedFile(
            com.sickworm.intellij.jugg.compiler.CompileFile.Type.Resource, source, temporaryFolder.root,
            com.sickworm.intellij.jugg.project.data.ModuleInfo.virtualModule)
        whenever(files.getCompiledFiles()).thenReturn(listOf(changed))
        val contextManager = mock<CompileContextManager>()
        whenever(contextManager.compileContext).thenReturn(mock<ICompileContext>())
        val helper = org.mockito.Mockito.mock(JuggCompilerHelper::class.java, org.mockito.Mockito.CALLS_REAL_METHODS)
        val oldCompiler = mock<com.sickworm.intellij.jugg.compiler.JuggCompiler>()
        helper.juggCompiler = oldCompiler
        var released = false
        com.intellij.openapi.util.Disposer.register(oldCompiler, com.intellij.openapi.Disposable { released = true })
        val manager = fileSelectionManager(files, contextManager, helper)
        org.mockito.Mockito.mockConstruction(com.sickworm.intellij.jugg.compiler.JuggCompiler::class.java).use {
            kotlin.test.assertTrue(manager.applyFileSelection(listOf(changed), emptySet()))
            kotlin.test.assertTrue(released, "Canceled compiled inputs must not remain in the cached resource table")
            val replacement = helper.juggCompiler
            kotlin.test.assertNotSame(oldCompiler, replacement)
            whenever(files.getCompiledFiles()).thenReturn(emptyList())
            kotlin.test.assertTrue(manager.applyFileSelection(listOf(changed), setOf(source.absolutePath)))
            kotlin.test.assertSame(replacement, helper.juggCompiler)
        }
        helper.dispose()
        com.intellij.openapi.util.Disposer.dispose(manager)
    }

    private fun fileSelectionManager(
        files: DeployFileManager,
        contextManager: CompileContextManager,
        compilerHelper: JuggCompilerHelper,
    ): JuggManager {
        val project = mock<Project>()
        whenever(project.basePath).thenReturn(temporaryFolder.root.absolutePath)
        val paths = mock<JuggPathManager>()
        whenever(paths.projectDir).thenReturn(temporaryFolder.root)
        return JuggManager(
            project = project, pathManager = paths, logger = TestGlobal.logger,
            juggServer = mock(), juggHotUpdateDownloader = mock(),
            fileChangesHandler = mock(), fileChangesDetector = mock(),
            deployHistoryManager = mock(), deployTargetManager = mock(),
            deployStateManager = mock(), taskRunnerManager = mock(),
            customCompilerManager = mock(), deployFileManager = files,
            compileContextManager = contextManager, juggRunningTaskStatusManager = mock(),
            dependencyChangeManager = mock(), gradleProjectInfoLocalFetchManager = mock(),
            gitFileChangesDetector = mock(), juggDeployerHelper = mock(),
            juggCompilerHelper = compilerHelper, customConfigManager = mock(), ideSyncProblemResolver = mock(),
        )
    }

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
        whenever(project.getService(com.intellij.execution.RunManager::class.java))
            .thenReturn(mock<com.intellij.execution.RunManager>())
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
        val files = mock<DeployFileManager>()
        val gradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(
            project,
            pathManager,
            compileContextManager,
            taskRunnerManager,
            dependencyChangeManager,
            deployHistoryManager,
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
            fileChangesDetector = mock<IFileChangesDetector>(),
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = deployTargetManager,
            deployStateManager = mock<DeployStateManager>(),
            taskRunnerManager = taskRunnerManager,
            customCompilerManager = mock<CustomCompilerManager>(),
            deployFileManager = files,
            compileContextManager = compileContextManager,
            juggRunningTaskStatusManager = mock<IJuggRunningTaskStatusManager>(),
            dependencyChangeManager = dependencyChangeManager,
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitFileChangesDetector = mock<GitFileChangesDetector>(),
            juggDeployerHelper = mock<JuggDeployerHelper>(),
            juggCompilerHelper = mock<JuggCompilerHelper>(),
            customConfigManager = mock<CustomConfigManager>(),
            ideSyncProblemResolver = ideSyncProblemResolver,
        )

        assertFullBuildRefreshesPendingFiles(manager, files, options)

        verify(compileContextManager).updateCompileContext(eq(true), eq(true), any())
    }

    private fun assertFullBuildRefreshesPendingFiles(
        manager: JuggManager,
        files: DeployFileManager,
        options: JuggGradleCompileOptions,
    ) {
        val tracker = com.sickworm.intellij.jugg.deploy.DeployFileStateTracker()
        val source = temporaryFolder.newFile("Pending.kt")
        tracker.addChangedFiles(listOf(com.sickworm.intellij.jugg.project.ChangedFile(
            com.sickworm.intellij.jugg.compiler.CompileFile.Type.Kotlin, source, temporaryFolder.root,
            com.sickworm.intellij.jugg.project.data.ModuleInfo.virtualModule)))
        whenever(files.getUndeployedFiles()).thenAnswer { tracker.getUndeployedFiles() }
        org.mockito.kotlin.doAnswer {
            tracker.resetKeepingRecentUncompiled(it.getArgument(2))
            null
        }.`when`(files).init(any(), any(), anyOrNull())
        val models = mutableListOf<com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel>()
        org.mockito.Mockito.mockConstruction(com.sickworm.intellij.jugg.ide.ui.JuggControlPanel::class.java) { _, context ->
            models.add(context.arguments()[1] as com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel)
        }.use {
            manager.getJuggControlPanel("overview")
            kotlin.test.assertEquals(1, models.single().snapshot().context.changedFileCount)
            manager.initIncrementalCompileAfterFullBuild(System.currentTimeMillis(), options)
            kotlin.test.assertTrue(tracker.getUndeployedFiles().isEmpty())
            kotlin.test.assertTrue(models.single().snapshot().context.changedFiles.isEmpty(),
                "Finishing asynchronous full-build initialization must refresh the panel")
        }
        com.intellij.openapi.util.Disposer.dispose(manager)
    }
}
