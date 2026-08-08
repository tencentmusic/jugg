package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.JuggCompiler
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.RecompileFiles
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.JuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.context.CompileEnvironmentSource
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.apache.log4j.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class JuggCompileHelperTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    companion object {
        private const val DIRECT_RUN_FALLBACK_HINT = "Run again directly will fall back to gradle compile."
        private const val NO_FILE_CHANGES_FALLBACK = "No file changes. will fallback to gradle compile."
        private const val NO_FILE_CHANGES_DRY_DEPLOY = "No file changes, dry deploy once."

        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
            JuggLogger.register("/tmp/jugg-test", TestGlobal.projectInfo.projectRoot)
        }
    }

    @Test
    fun replacingCompilerDisposesRegisteredChildren() {
        val fixture = createFixture()
        val oldCompiler = mock<JuggCompiler>()
        val replacement = mock<JuggCompiler>()
        val childDisposed = AtomicBoolean()
        Disposer.register(oldCompiler, Disposable { childDisposed.set(true) })

        try {
            fixture.helper.juggCompiler = oldCompiler
            fixture.helper.juggCompiler = replacement

            assertTrue(childDisposed.get())
        } finally {
            Disposer.dispose(oldCompiler)
            fixture.helper.close()
        }
    }

    @Test
    fun closingHelperDisposesRegisteredCompilerChildren() {
        val fixture = createFixture()
        val compiler = mock<JuggCompiler>()
        val childDisposed = AtomicBoolean()
        Disposer.register(compiler, Disposable { childDisposed.set(true) })

        try {
            fixture.helper.juggCompiler = compiler
            fixture.helper.close()

            assertTrue(childDisposed.get())
        } finally {
            Disposer.dispose(compiler)
        }
    }

    @Test
    fun checkingFallbackAgainstSnapshotDoesNotRefreshDeployState() {
        val fixture = createFixture()

        assertEquals(null, fixture.helper.checkFallback(JuggDeployState.READY))

        verify(fixture.deployStateManager, never()).updateDeployState()
    }

    @Test
    fun preprocessIncrementalCompile_noFileChanges_onlyRunAsyncGitCheck() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)

        invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        verify(fixture.gitChangeChecker).checkUndetectedFilesAsync(any())
        verify(fixture.gitChangeChecker, never()).checkUndetectedFiles(any())
    }

    @Test
    fun incrementalCompile_noFileChanges_projectSwitched_deployDirectlyWithoutConfirm() {
        val fixture = createFixture()
        fixture.juggRunningTaskStatusManager.isProjectSwitchedThisRun = true
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.deployTargetManager.getDeviceNameList()).thenReturn("device-1")
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        fixture.helper.juggCompiler = mock<JuggCompiler>()

        val result = fixture.helper.incrementalCompile(fixture.uiHandler)

        assertTrue(result.isSuccess)
        verify(fixture.uiHandler, never()).confirmFallbackWhenNoFileChanges()
    }

    @Test
    fun incrementalCompile_noFileChanges_pendingCompiledFilesForApp_fallbackToGradle() {
        val logger = CapturingLogger()
        val fixture = createFixture(logger)
        val pendingFile = ChangedFile(
            CompileFile.Type.Kotlin,
            File("/tmp/jugg-test/src/androidTest/PendingTest.kt"),
            File("/tmp/jugg-test"),
            ModuleInfo.virtualModule,
        ).apply {
            compiledTimes = 1
        }
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(fixture.deployFileManager.getCompiledFiles()).thenReturn(listOf(pendingFile))
        whenever(fixture.deployTargetManager.getDeviceNameList()).thenReturn("device-1")
        whenever(fixture.uiHandler.confirmFallbackWhenNoFileChanges()).thenReturn(ConfirmResult.POSITIVE)
        fixture.juggRunningTaskStatusManager.setHasRun("device-1")
        fixture.helper.juggCompiler = mock<JuggCompiler>()

        val result = fixture.helper.incrementalCompile(fixture.uiHandler)

        assertFalse(result.isSuccess)
        assertFalse(result.isGradleCompile)
        assertTrue(result.isCanFallback)
        assertFalse(result.hasFileChanges)
        assertTrue(logger.messages.contains(NO_FILE_CHANGES_FALLBACK))
        assertFalse(logger.messages.contains(NO_FILE_CHANGES_DRY_DEPLOY))
        verify(fixture.uiHandler).confirmFallbackWhenNoFileChanges()
        verify(fixture.helper.juggCompiler!!, never()).compile(any())
    }

    @Test
    fun incrementalCompile_noFileChanges_negativeConfirmation_reportsDryDeploy() {
        val logger = CapturingLogger()
        val fixture = createFixture(logger)
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.dependencyChangeManager.changeStatus)
            .thenReturn(IDependencyChangeManager.ChangeStatus.NO_CHANGE)
        whenever(fixture.deployTargetManager.getDeviceNameList()).thenReturn("device-1")
        whenever(fixture.uiHandler.confirmFallbackWhenNoFileChanges()).thenReturn(ConfirmResult.NEGATIVE)
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        whenever(fixture.pathManager.stagingDir).thenReturn(temporaryFolder.newFolder("staging"))
        whenever(fixture.fileChangesHandler.filter(emptyList())).thenReturn(emptyList())
        doReturn(RecompileFiles(emptyList(), emptyList(), JuggDeployData.forDryDeploy(emptyList())))
            .whenever(fixture.deployFileManager)
            .getRecompileFiles(any(), any(), anyOrNull())
        fixture.juggRunningTaskStatusManager.setHasRun("device-1")

        val juggCompiler = mock<JuggCompiler>()
        whenever(juggCompiler.context).thenReturn(mock())
        whenever(juggCompiler.compile(any())).thenAnswer { invocation ->
            CompileResult.empty(invocation.getArgument<CompileTask>(0))
        }
        fixture.helper.juggCompiler = juggCompiler

        val result = fixture.helper.incrementalCompile(fixture.uiHandler)

        assertTrue(result.isSuccess)
        assertFalse(result.isCanFallback)
        assertTrue(logger.messages.contains(NO_FILE_CHANGES_DRY_DEPLOY))
        assertFalse(logger.messages.contains(NO_FILE_CHANGES_FALLBACK))
    }

    @Test
    fun incrementalCompile_noFileChanges_androidTest_deployDirectlyWithoutRecompile() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(fixture.deployFileManager.getCompiledFiles()).thenReturn(emptyList())
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        fixture.helper.juggCompiler = mock<JuggCompiler>()

        val result = fixture.helper.incrementalCompile(
            fixture.uiHandler,
            BuildTarget.ANDROID_TEST,
            isAndroidTestRun = true,
        )

        assertTrue(result.isSuccess)
        assertFalse(result.isGradleCompile)
        assertFalse(result.hasFileChanges)
        verify(fixture.uiHandler, never()).confirmFallbackWhenNoFileChanges()
        verify(fixture.helper.juggCompiler!!, never()).compile(any())
    }

    @Test
    fun incrementalCompile_noFileChanges_appRunWithAndroidTestBuildTarget_showsFallbackConfirm() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(fixture.deployTargetManager.getDeviceNameList()).thenReturn("device-1")
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        whenever(fixture.uiHandler.confirmFallbackWhenNoFileChanges()).thenReturn(ConfirmResult.POSITIVE)
        fixture.juggRunningTaskStatusManager.setHasRun("device-1")
        fixture.helper.juggCompiler = mock<JuggCompiler>()

        val result = fixture.helper.incrementalCompile(fixture.uiHandler, BuildTarget.ANDROID_TEST)

        assertFalse(result.isSuccess)
        assertTrue(result.isCanFallback)
        assertFalse(result.hasFileChanges)
        verify(fixture.uiHandler).confirmFallbackWhenNoFileChanges()
        verify(fixture.helper.juggCompiler!!, never()).compile(any())
    }

    @Test
    fun incrementalCompile_noFileChanges_debugRun_deployDirectlyWithoutConfirm() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(true)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(fixture.deployFileManager.getCompiledFiles()).thenReturn(emptyList())
        whenever(fixture.deployTargetManager.getDeviceNameList()).thenReturn("device-1")
        whenever(fixture.uiHandler.isDebugRun).thenReturn(true)
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        whenever(fixture.uiHandler.confirmFallbackWhenNoFileChanges()).thenReturn(ConfirmResult.POSITIVE)
        fixture.juggRunningTaskStatusManager.setHasRun("device-1")
        fixture.helper.juggCompiler = mock<JuggCompiler>()

        val result = fixture.helper.incrementalCompile(fixture.uiHandler)

        assertTrue(result.isSuccess)
        assertFalse(result.isGradleCompile)
        assertFalse(result.hasFileChanges)
        verify(fixture.uiHandler, never()).confirmFallbackWhenNoFileChanges()
        verify(fixture.helper.juggCompiler!!, never()).compile(any())
    }

    @Test
    fun incrementalCompile_skipsMissingUndeployedSourceFiles() {
        val fixture = createFixture()
        val existingFile = File.createTempFile("Existing", ".kt")
        existingFile.deleteOnExit()
        val missingFile = File(existingFile.parentFile, "Missing_${System.nanoTime()}.kt")
        val existingChanged = ChangedFile(
            CompileFile.Type.Kotlin,
            existingFile,
            existingFile.parentFile,
            ModuleInfo.virtualModule,
        )
        val missingChanged = ChangedFile(
            CompileFile.Type.Kotlin,
            missingFile,
            missingFile.parentFile,
            ModuleInfo.virtualModule,
        )

        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(false)
        whenever(fixture.dependencyChangeManager.isNeedCompilation).thenReturn(false)
        whenever(fixture.dependencyChangeManager.changeStatus).thenReturn(IDependencyChangeManager.ChangeStatus.NO_CHANGE)
        whenever(fixture.deployFileManager.getUndeployedFiles()).thenReturn(listOf(existingChanged, missingChanged))
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        whenever(fixture.pathManager.stagingDir).thenReturn(temporaryFolder.newFolder("staging"))
        doReturn(RecompileFiles(emptyList(), emptyList(), JuggDeployData.forDryDeploy(emptyList())))
            .whenever(fixture.deployFileManager)
            .getRecompileFiles(any(), any(), anyOrNull())

        val juggCompiler = mock<JuggCompiler>()
        whenever(juggCompiler.context).thenReturn(mock())
        whenever(juggCompiler.compile(any())).thenAnswer { invocation ->
            val task = invocation.getArgument<CompileTask>(0)
            CompileResult.empty(task)
        }
        fixture.helper.juggCompiler = juggCompiler

        val result = fixture.helper.incrementalCompile(fixture.uiHandler)

        assertTrue(result.isSuccess)
        verify(fixture.deployFileManager).removeChangedFile(listOf(missingFile))

        val compileTaskCaptor = argumentCaptor<CompileTask>()
        verify(juggCompiler).compile(compileTaskCaptor.capture())
        assertEquals(
            listOf(existingFile.absolutePath),
            compileTaskCaptor.firstValue.files.map { it.file.absolutePath },
        )
    }

    @Test
    fun preprocessIncrementalCompile_hasFileChanges_onlyRunAsyncGitCheck() {
        val fixture = createFixture()
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(false)

        invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        verify(fixture.gitChangeChecker).checkUndetectedFilesAsync(any())
        verify(fixture.gitChangeChecker, never()).checkUndetectedFiles(any())
    }

    @Test
    fun preprocessIncrementalCompile_compileCommandChanged_forcesGradleFallback() {
        val fixture = createFixture()
        whenever(fixture.options.compileCommand).thenReturn("./gradlew :app:assembleRelease")
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1L),
        )

        val result = invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        assertTrue(result!!.isCanFallback)
        assertEquals("Compile command changed", result.failedReason)
    }

    @Test
    fun preprocessIncrementalCompile_gradleProjectInfoUnavailable_forcesGradleFallback() {
        val fixture = createFixture()
        whenever(fixture.gradleProjectInfoLocalFetchManager.isIncrementalCompileAvailable).thenReturn(false)

        val result = invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        assertTrue(result!!.isCanFallback)
        assertEquals("Gradle project info unavailable", result.failedReason)
    }

    @Test
    fun preprocessIncrementalCompile_missingProjectInfoRebuilding_waitsBeforeIncrementalDecision() {
        val fixture = createFixture()
        val isRebuilding = AtomicBoolean(true)
        val rebuildCheckStarted = CountDownLatch(1)
        val preprocessFinished = CountDownLatch(1)
        whenever(fixture.gradleProjectInfoLocalFetchManager.isRebuildingMissingProjectInfo).thenAnswer {
            rebuildCheckStarted.countDown()
            isRebuilding.get()
        }

        thread(isDaemon = true) {
            invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)
            preprocessFinished.countDown()
        }

        assertTrue(rebuildCheckStarted.await(1, TimeUnit.SECONDS))
        assertFalse(preprocessFinished.await(100, TimeUnit.MILLISECONDS))

        isRebuilding.set(false)

        assertTrue(preprocessFinished.await(1, TimeUnit.SECONDS))
    }

    @Test(timeout = 1_000)
    fun preprocessIncrementalCompile_forceGradle_doesNotWaitForProjectInfoRebuild() {
        val fixture = createFixture()
        whenever(fixture.uiHandler.isForceGradleCompile).thenReturn(true)
        whenever(fixture.gradleProjectInfoLocalFetchManager.isRebuildingMissingProjectInfo).thenReturn(true)

        val result = invokePreprocessIncrementalCompile(fixture.helper, fixture.options, fixture.uiHandler)

        assertEquals("Force fallback", result!!.failedReason)
    }

    @Test
    fun prepareRemoteProjectInfo_compileCommandChanged_startsCurrentCommandRefresh() {
        val fixture = createFixture()
        val compileCommand = "./gradlew :app:assembleDevDebug"
        whenever(fixture.options.compileCommand).thenReturn(compileCommand)
        whenever(fixture.options.buildTarget).thenReturn(BuildTarget.APP)
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo("./gradlew :app:assembleProdDebug", BuildTarget.APP, 1L),
        )
        whenever(fixture.gradleProjectInfoLocalFetchManager.isProjectInfoAvailable).thenReturn(true)

        invokePrepareRemoteProjectInfo(fixture.helper, fixture.options)

        verify(fixture.gradleProjectInfoLocalFetchManager).runUpdateIfNeeded(
            isForce = true,
            specificCompileCommand = compileCommand,
            buildTarget = BuildTarget.APP,
            shouldWaitForRemoteInit = true,
        )
    }

    @Test
    fun prepareRemoteProjectInfo_buildFileChanged_marksRefreshForRemoteInitWait() {
        val fixture = createFixture()
        val compileCommand = "./gradlew :app:assembleDebug"
        val buildFile = temporaryFolder.newFile("build.gradle")
        val changedFile = ChangedFile(
            CompileFile.Type.BuildFile,
            buildFile,
            buildFile.parentFile,
            ModuleInfo.virtualModule,
        )
        whenever(fixture.options.compileCommand).thenReturn(compileCommand)
        whenever(fixture.options.buildTarget).thenReturn(BuildTarget.APP)
        whenever(fixture.deployHistoryManager.getFullBuildInfo()).thenReturn(
            FullBuildInfo(compileCommand, BuildTarget.APP, 1L),
        )
        whenever(fixture.gradleProjectInfoLocalFetchManager.isProjectInfoAvailable).thenReturn(true)
        whenever(fixture.deployFileManager.getUndeployedFiles()).thenReturn(listOf(changedFile))

        invokePrepareRemoteProjectInfo(fixture.helper, fixture.options)

        verify(fixture.gradleProjectInfoLocalFetchManager).markIsNeedUpdate(true, buildFile.lastModified())
        verify(fixture.gradleProjectInfoLocalFetchManager).runUpdateIfNeeded(
            isForce = false,
            specificCompileCommand = compileCommand,
            buildTarget = BuildTarget.APP,
            shouldWaitForRemoteInit = true,
        )
    }

    @Test
    fun checkFallback_bindingAdapterSourceChanged_allowsIncrementalCompile() {
        val fixture = createFixture()
        val sourceFile = temporaryFolder.newFile("BindingAdapters.kt").apply {
            writeText(
                """
                import androidx.databinding.BindingAdapter

                @BindingAdapter("android:visibility")
                fun setVisibility(view: android.view.View, visible: Boolean) = Unit
                """.trimIndent()
            )
        }
        val changedFile = ChangedFile(
            CompileFile.Type.Kotlin,
            sourceFile,
            sourceFile.parentFile,
            ModuleInfo.virtualModule,
        )
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(listOf(changedFile))

        assertEquals(null, fixture.helper.checkFallback())
    }

    @Test
    fun checkFallback_bindingAdapterRemoved_allowsIncrementalCompile() {
        val fixture = createFixture()
        val sourceFile = temporaryFolder.newFile("RemovedBindingAdapters.kt").apply {
            writeText("fun ordinaryMethod() = Unit")
        }
        val previousSourceFile = temporaryFolder.newFile("PreviousBindingAdapters.kt").apply {
            writeText(
                """
                import androidx.databinding.BindingAdapter

                @BindingAdapter("android:visibility")
                fun setVisibility(view: android.view.View, visible: Boolean) = Unit
                """.trimIndent()
            )
        }
        val changedFile = ChangedFile(
            CompileFile.Type.Kotlin,
            sourceFile,
            sourceFile.parentFile,
            ModuleInfo.virtualModule,
        )
        whenever(fixture.deployFileManager.getUncompiledFiles()).thenReturn(listOf(changedFile))
        whenever(fixture.deployHistoryManager.getLastBuildFiles(listOf(changedFile)))
            .thenReturn(listOf(changedFile to previousSourceFile))

        assertEquals(null, fixture.helper.checkFallback())
    }

    @Test
    fun compile_incrementalFailure_nonRpcModePrintsDirectRunFallbackHint() {
        val logger = CapturingLogger()
        val fixture = createFixture(logger = logger)
        prepareIncrementalCompileFailure(fixture)
        whenever(fixture.uiHandler.isRpcMode).thenReturn(false)

        fixture.helper.compile(fixture.options, fixture.uiHandler)

        assertTrue(logger.messages.any { it.contains(DIRECT_RUN_FALLBACK_HINT) })
    }

    @Test
    fun compile_incrementalFailure_rpcModeHidesDirectRunFallbackHint() {
        val logger = CapturingLogger()
        val fixture = createFixture(logger = logger)
        prepareIncrementalCompileFailure(fixture)
        whenever(fixture.uiHandler.isRpcMode).thenReturn(true)

        fixture.helper.compile(fixture.options, fixture.uiHandler)

        assertTrue(logger.messages.any { it.contains("Found incremental compile error") })
        assertFalse(logger.messages.any { it.contains(DIRECT_RUN_FALLBACK_HINT) })
    }

    @Test
    fun compile_incrementalFailure_doesNotPrintDuplicatedVisibleErrorSummary() {
        val logger = CapturingLogger()
        val fixture = createFixture(logger = logger)
        prepareIncrementalCompileFailure(fixture)

        fixture.helper.compile(fixture.options, fixture.uiHandler)

        assertFalse(logger.messages.any { it.contains("Found incremental compile error:\n") })
    }

    private fun prepareIncrementalCompileFailure(fixture: Fixture) {
        val sourceFile = temporaryFolder.newFile("Broken.kt")
        val changedFile = ChangedFile(
            CompileFile.Type.Kotlin,
            sourceFile,
            sourceFile.parentFile,
            ModuleInfo.virtualModule,
        )
        whenever(fixture.pathManager.projectDir).thenReturn(temporaryFolder.root)
        whenever(fixture.pathManager.stagingDir).thenReturn(temporaryFolder.newFolder("staging"))
        whenever(fixture.options.buildTarget).thenReturn(BuildTarget.APP)
        whenever(fixture.deployFileManager.isNoFileChanges()).thenReturn(false)
        whenever(fixture.deployFileManager.getUndeployedFiles()).thenReturn(listOf(changedFile))
        whenever(fixture.dependencyChangeManager.changeStatus)
            .thenReturn(IDependencyChangeManager.ChangeStatus.NO_CHANGE)
        whenever(fixture.uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)

        val juggCompiler = mock<JuggCompiler>()
        whenever(juggCompiler.compile(any())).thenAnswer { invocation ->
            val task = invocation.getArgument<CompileTask>(0)
            task.allFailed("compile failed")
        }
        fixture.helper.juggCompiler = juggCompiler
    }

    private fun createFixture(logger: Logger = TestGlobal.getLogger()): Fixture {
        val project = mock<Project>()
        whenever(project.basePath).thenReturn("/tmp/jugg-test")
        val pathManager = mock<JuggPathManager>()
        whenever(pathManager.projectDir).thenReturn(temporaryFolder.root)
        val juggServer = mock<JuggServer>()
        val deployTargetManager = mock<IDeployTargetManager>()
        val deployStateManager = mock<DeployStateManager>()
        val deployFileManager = mock<DeployFileManager>()
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val juggRunningTaskStatusManager = JuggRunningTaskStatusManager()
        val compileContextManager = mock<CompileContextManager>()
        val fileChangesHandler = mock<IFileChangesHandler>()
        val dependencyChangeManager = mock<IDependencyChangeManager>()
        val gradleProjectInfoLocalFetchManager = mock<GradleProjectInfoLocalFetchManager>()
        val gitFileChangesDetector = mock<GitFileChangesDetector>()
        val taskRunnerManager = mock<TaskRunnerManager>()
        val gitChangeChecker = mock<GitChangesCompileChecker>()
        val uiHandler = mock<CompileUiHandler>()
        val options = mock<JuggGradleCompileOptions>()

        whenever(uiHandler.isForceGradleCompile).thenReturn(false)
        whenever(deployHistoryManager.isLastFullCompileFailed).thenReturn(false)
        whenever(deployHistoryManager.isBuildTargetChanged(options)).thenReturn(false)
        whenever(deployFileManager.getUndeployedFiles()).thenReturn(emptyList())
        whenever(deployFileManager.getUncompiledFiles()).thenReturn(emptyList())
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        whenever(gradleProjectInfoLocalFetchManager.isIncrementalCompileAvailable).thenReturn(true)

        val helper = JuggCompilerHelper(
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
            compileEnvironmentSource = CompileEnvironmentSource(null, emptyList()),
            gitFileChangesDetector = gitFileChangesDetector,
            taskRunnerManager = taskRunnerManager,
            logger = logger,
            gitChangeChecker = gitChangeChecker,
        )

        return Fixture(
            helper = helper,
            pathManager = pathManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            dependencyChangeManager = dependencyChangeManager,
            gradleProjectInfoLocalFetchManager = gradleProjectInfoLocalFetchManager,
            gitChangeChecker = gitChangeChecker,
            fileChangesHandler = fileChangesHandler,
            uiHandler = uiHandler,
            options = options,
            deployTargetManager = deployTargetManager,
            juggRunningTaskStatusManager = juggRunningTaskStatusManager,
        )
    }

    private class CapturingLogger : Logger() {
        val messages = mutableListOf<String>()

        override fun isDebugEnabled(): Boolean = true
        override fun debug(message: String) = Unit
        override fun debug(t: Throwable?) = Unit
        override fun debug(message: String, t: Throwable?) = Unit
        override fun info(message: String) {
            messages += message
        }
        override fun info(message: String, t: Throwable?) {
            messages += message
        }
        override fun warn(message: String, t: Throwable?) {
            messages += message
        }
        override fun error(message: String, t: Throwable?, vararg details: String?) {
            messages += message
        }
        @Suppress("UnstableApiUsage")
        override fun setLevel(level: Level) = Unit
    }

    private fun invokePreprocessIncrementalCompile(
        helper: JuggCompilerHelper,
        options: JuggGradleCompileOptions,
        uiHandler: CompileUiHandler,
    ): CompileTaskResult? {
        val method = JuggCompilerHelper::class.java.getDeclaredMethod(
            "preprocessIncrementalCompile",
            JuggGradleCompileOptions::class.java,
            CompileUiHandler::class.java,
        )
        method.isAccessible = true
        return method.invoke(helper, options, uiHandler) as CompileTaskResult?
    }

    private fun invokePrepareRemoteProjectInfo(
        helper: JuggCompilerHelper,
        options: JuggGradleCompileOptions,
    ) {
        val method = JuggCompilerHelper::class.java.getDeclaredMethod(
            "prepareRemoteProjectInfo",
            JuggGradleCompileOptions::class.java,
        )
        method.isAccessible = true
        method.invoke(helper, options)
    }

    private data class Fixture(
        val helper: JuggCompilerHelper,
        val pathManager: JuggPathManager,
        val deployStateManager: DeployStateManager,
        val deployFileManager: DeployFileManager,
        val deployHistoryManager: IDeployHistoryManager,
        val deployTargetManager: IDeployTargetManager,
        val dependencyChangeManager: IDependencyChangeManager,
        val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager,
        val gitChangeChecker: GitChangesCompileChecker,
        val fileChangesHandler: IFileChangesHandler,
        val uiHandler: CompileUiHandler,
        val options: JuggGradleCompileOptions,
        val juggRunningTaskStatusManager: JuggRunningTaskStatusManager,
    )
}
