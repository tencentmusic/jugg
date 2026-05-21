package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployHistoryData
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.FullBuildInfoSerializer
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.LastCompileTimestampRegistry
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

/**
 * GetStatusMcpToolActionTest verifies status tool returns deploy state, file counts, and file path list with detail.
 */
class GetStatusMcpToolActionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testStatusReturnsDeployStateAndEmptyFilesWhenNoChanges() {
        val deployState = JuggDeployState.READY
        val runtime = runtimeWith(deployState = deployState, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["hasDevice"])
        Assert.assertEquals(false, data["needFallback"])
        @Suppress("UNCHECKED_CAST")
        val pendingModifiedFiles = data["pendingModifiedFiles"] as Map<String, Any>
        Assert.assertEquals(0, (pendingModifiedFiles["total"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val files = data["files"] as List<*>
        Assert.assertTrue(files.isEmpty())
        Assert.assertEquals("", data["detail"])
        Assert.assertEquals("", data["lastFileModifiedTime"])
        Assert.assertEquals("", data["lastCompileTime"])
        Assert.assertFalse(data.containsKey("lastFileModifiedTimeMillis"))
        Assert.assertEquals(false, data["isCompiling"])
    }

    @Test
    fun testStatusReturnsIsCompilingWhenRunnerIsBusy() {
        val runtime = runtimeWith(
            deployState = JuggDeployState.READY,
            hasDevice = true,
            uncompiledFiles = emptyList(),
            isCompiling = true,
        )

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["isCompiling"])
    }

    @Test
    fun testStatusReturnsRecordedLastCompileTime() {
        val deployState = JuggDeployState.READY
        val runtime = runtimeWith(deployState = deployState, hasDevice = true, uncompiledFiles = emptyList())
        val projectDir = "/fake/project"
        val lastCompileRegistry = LastCompileTimestampRegistry().apply {
            setTimestamp(projectDir, "2026-04-26 10:00:00")
        }

        val result = GetStatusMcpToolAction(lastCompileTimestampRegistry = lastCompileRegistry)
            .execute(mapOf("projectDir" to projectDir), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("2026-04-26 10:00:00", data["lastCompileTime"])
    }

    @Test
    fun testStatusEnabledAndroidTestTrueWhenLastFullBuildTargetIsAndroidTest() {
        val projectDir = tempFolder.newFolder("project-android-test")
        writeFullBuildInfo(projectDir, BuildTarget.ANDROID_TEST)
        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(mapOf("projectDir" to projectDir.absolutePath), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["enabledAndroidTest"])
    }

    @Test
    fun testStatusEnabledAndroidTestFalseWhenNoFullBuildInfo() {
        val projectDir = tempFolder.newFolder("project-no-full-build")
        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(mapOf("projectDir" to projectDir.absolutePath), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(false, data["enabledAndroidTest"])
    }

    @Test
    fun testStatusReturnsHasBeenFullCompiledWhenCompleteBaselineExists() {
        val projectDir = tempFolder.newFolder("project-full-compiled")
        writeFullCompileState(projectDir, BuildTarget.APP)
        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(mapOf("projectDir" to projectDir.absolutePath), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["hasBeenFullCompiled"])
    }

    @Test
    fun testStatusDoesNotTreatPartialFullBuildInfoAsFullCompiled() {
        val projectDir = tempFolder.newFolder("project-partial-full-build")
        writeFullBuildInfo(projectDir, BuildTarget.APP)
        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(mapOf("projectDir" to projectDir.absolutePath), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(false, data["hasBeenFullCompiled"])
    }

    @Test
    fun testStatusRefreshesChangedFilesByDefault() {
        val projectDir = tempFolder.newFolder("project-refresh")
        val javaFile = File(projectDir, "ExternalEdit.java")
        javaFile.writeText("class ExternalEdit {}")
        javaFile.setLastModified(1_000L)
        val module = ModuleInfo.virtualModule
        var refreshedFiles = emptyList<ChangedFile>()
        val runtime = runtimeWith(
            deployState = JuggDeployState.READY,
            hasDevice = true,
            uncompiledFiles = emptyList(),
            uncompiledFilesProvider = { refreshedFiles },
            statusRefresh = {
                refreshedFiles = listOf(ChangedFile(CompileFile.Type.Java, javaFile, projectDir, module))
            },
        )

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pendingModifiedFiles = data["pendingModifiedFiles"] as Map<String, Any>
        Assert.assertEquals(1, (pendingModifiedFiles["total"] as Number).toInt())
        Assert.assertEquals(1, (pendingModifiedFiles["Java"] as Number).toInt())
    }

    @Test
    fun testStatusRefreshesChangedFilesWhenRefreshChangesIsTrue() {
        val projectDir = tempFolder.newFolder("project-refresh-enabled")
        val javaFile = File(projectDir, "ExternalEdit.java")
        javaFile.writeText("class ExternalEdit {}")
        javaFile.setLastModified(1_000L)
        val module = ModuleInfo.virtualModule
        var refreshedFiles = emptyList<ChangedFile>()
        val runtime = runtimeWith(
            deployState = JuggDeployState.READY,
            hasDevice = true,
            uncompiledFiles = emptyList(),
            uncompiledFilesProvider = { refreshedFiles },
            statusRefresh = {
                refreshedFiles = listOf(ChangedFile(CompileFile.Type.Java, javaFile, projectDir, module))
            },
        )

        val result = GetStatusMcpToolAction().execute(mapOf("refreshChanges" to true), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pendingModifiedFiles = data["pendingModifiedFiles"] as Map<String, Any>
        Assert.assertEquals(1, (pendingModifiedFiles["total"] as Number).toInt())
        Assert.assertEquals(1, (pendingModifiedFiles["Java"] as Number).toInt())
    }

    @Test
    fun testStatusSkipsRefreshChangedFilesWhenRefreshChangesIsFalse() {
        val projectDir = tempFolder.newFolder("project-refresh-disabled")
        val javaFile = File(projectDir, "ExternalEdit.java")
        javaFile.writeText("class ExternalEdit {}")
        javaFile.setLastModified(1_000L)
        val module = ModuleInfo.virtualModule
        var refreshedFiles = emptyList<ChangedFile>()
        val runtime = runtimeWith(
            deployState = JuggDeployState.READY,
            hasDevice = true,
            uncompiledFiles = emptyList(),
            uncompiledFilesProvider = { refreshedFiles },
            statusRefresh = {
                refreshedFiles = listOf(ChangedFile(CompileFile.Type.Java, javaFile, projectDir, module))
            },
        )

        val result = GetStatusMcpToolAction().execute(mapOf("refreshChanges" to false), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pendingModifiedFiles = data["pendingModifiedFiles"] as Map<String, Any>
        Assert.assertEquals(0, (pendingModifiedFiles["total"] as Number).toInt())
    }

    @Test
    fun testStatusHasDeviceFalseWhenNoDeviceConnected() {
        val deployState = JuggDeployState(
            JuggDeployState.State.NOTHING_CAN_DO,
            "no device",
            IdeDeployState.ok,
        )
        val runtime = runtimeWith(deployState = deployState, hasDevice = false, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(false, data["hasDevice"])
        Assert.assertEquals(false, data["needFallback"])
    }

    @Test
    fun testStatusNeedFallbackTrueWhenReadyFullCompile() {
        val deployState = JuggDeployState(
            JuggDeployState.State.READY_FULL_COMPILE,
            "need full compile",
            IdeDeployState.ok,
        )
        val runtime = runtimeWith(deployState = deployState, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["hasDevice"])
        Assert.assertEquals(true, data["needFallback"])
    }

    @Test
    fun testStatusNeedFallbackFalseWhenReadyIncrementalCompile() {
        val deployState = JuggDeployState(
            JuggDeployState.State.READY_INCREMENTAL_COMPILE,
            "ready for incremental compile",
            IdeDeployState.ok,
        )
        val runtime = runtimeWith(deployState = deployState, hasDevice = true, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["hasDevice"])
        Assert.assertEquals(false, data["needFallback"])
    }

    @Test
    fun testStatusReturnsCorrectCountsByType() {
        val projectDir = tempFolder.newFolder("project")
        val javaFile1 = tempFolder.newFile("A.java")
        val javaFile2 = tempFolder.newFile("B.java")
        val kotlinFile = tempFolder.newFile("C.kt")
        val resourceFile = tempFolder.newFile("D.xml")
        javaFile1.setLastModified(1_000L)
        javaFile2.setLastModified(2_000L)
        kotlinFile.setLastModified(3_000L)
        resourceFile.setLastModified(4_000L)

        val module = ModuleInfo.virtualModule
        val uncompiledFiles = listOf(
            ChangedFile(CompileFile.Type.Java, javaFile1, projectDir, module),
            ChangedFile(CompileFile.Type.Java, javaFile2, projectDir, module),
            ChangedFile(CompileFile.Type.Kotlin, kotlinFile, projectDir, module),
            ChangedFile(CompileFile.Type.Resource, resourceFile, projectDir, module),
        )

        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = uncompiledFiles)

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pendingModifiedFiles = data["pendingModifiedFiles"] as Map<String, Any>
        Assert.assertEquals(4, (pendingModifiedFiles["total"] as Number).toInt())
        Assert.assertEquals(2, (pendingModifiedFiles["Java"] as Number).toInt())
        Assert.assertEquals(1, (pendingModifiedFiles["Kotlin"] as Number).toInt())
        Assert.assertEquals(1, (pendingModifiedFiles["Resource"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val files = data["files"] as List<*>
        Assert.assertEquals(4, files.size)
        Assert.assertEquals("", data["detail"])
        Assert.assertTrue((data["lastFileModifiedTime"] as String).isNotBlank())
        Assert.assertFalse(data.containsKey("lastFileModifiedTimeMillis"))
    }

    @Test
    fun testStatusTruncatesFilesWhenExceedsTwenty() {
        val projectDir = tempFolder.newFolder("project2")
        val module = ModuleInfo.virtualModule
        val uncompiledFiles = (1..25).map { i ->
            val f = File(projectDir, "File$i.java")
            ChangedFile(CompileFile.Type.Java, f, projectDir, module)
        }

        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = uncompiledFiles)

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pendingModifiedFiles = data["pendingModifiedFiles"] as Map<String, Any>
        Assert.assertEquals(25, (pendingModifiedFiles["total"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val files = data["files"] as List<*>
        Assert.assertEquals(20, files.size)
        val detail = data["detail"] as String
        Assert.assertTrue("detail should mention 20 and 25", detail.contains("20") && detail.contains("25"))
    }

    @Test
    fun testStatusReturnsAbsolutePaths() {
        val projectDir = tempFolder.newFolder("project3")
        val javaFile = tempFolder.newFile("Main.java")
        val module = ModuleInfo.virtualModule
        val uncompiledFiles = listOf(
            ChangedFile(CompileFile.Type.Java, javaFile, projectDir, module),
        )

        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = uncompiledFiles)

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val files = data["files"] as List<String>
        Assert.assertEquals(1, files.size)
        Assert.assertTrue("path should be absolute", files[0].startsWith("/"))
        Assert.assertEquals(javaFile.absolutePath, files[0])
    }

    @Test
    fun testStatusReturnsNothingCanDoState() {
        val deployState = JuggDeployState(
            JuggDeployState.State.NOTHING_CAN_DO,
            "no device",
            IdeDeployState.ok,
        )
        val runtime = runtimeWith(deployState = deployState, hasDevice = false, uncompiledFiles = emptyList())

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(false, data["hasDevice"])
        Assert.assertNotNull(data["stateMessage"])
    }

    @Test
    fun testStatusNeedFallbackTrueWhenCheckerReturnsReason() {
        val deployState = JuggDeployState.READY
        val checker = IIncrementalCompileFallbackChecker { "Build file changed: build.gradle" }
        val runtime = runtimeWith(deployState = deployState, hasDevice = true, uncompiledFiles = emptyList(), fallbackChecker = checker)

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["needFallback"])
    }

    @Test
    fun testStatusFallbackReasonPrependedToDetail() {
        val deployState = JuggDeployState.READY
        val checker = IIncrementalCompileFallbackChecker { "Build file changed: build.gradle" }
        val runtime = runtimeWith(deployState = deployState, hasDevice = true, uncompiledFiles = emptyList(), fallbackChecker = checker)

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        val detail = data["detail"] as String
        Assert.assertTrue(
            "detail should start with fallback reason",
            detail.startsWith("Build file changed: build.gradle"),
        )
    }

    @Test
    fun testStatusFallbackReasonPrependedBeforeTruncationNote() {
        val projectDir = tempFolder.newFolder("project4")
        val module = ModuleInfo.virtualModule
        val uncompiledFiles = (1..25).map { i ->
            val f = File(projectDir, "File$i.java")
            ChangedFile(CompileFile.Type.Java, f, projectDir, module)
        }
        val checker = IIncrementalCompileFallbackChecker { "Too many changes" }
        val runtime = runtimeWith(deployState = JuggDeployState.READY, hasDevice = true, uncompiledFiles = uncompiledFiles, fallbackChecker = checker)

        val result = GetStatusMcpToolAction().execute(emptyMap(), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        val detail = data["detail"] as String
        Assert.assertTrue("detail should start with fallback reason", detail.startsWith("Too many changes"))
        Assert.assertTrue("detail should also include truncation note", detail.contains("20") && detail.contains("25"))
    }

    private fun runtimeWith(
        deployState: JuggDeployState,
        hasDevice: Boolean,
        uncompiledFiles: List<ChangedFile>,
        fallbackChecker: IIncrementalCompileFallbackChecker? = null,
        uncompiledFilesProvider: (() -> List<ChangedFile>)? = null,
        statusRefresh: () -> Unit = {},
        isCompiling: Boolean = false,
    ): IMcpRuntime {
        val deployStateManager = object : IDeployStateManager {
            override fun updateDeployState(): JuggDeployState = deployState
        }
        val mockDeployFileManager = Mockito.mock(DeployFileManager::class.java)
        val filesProvider = uncompiledFilesProvider ?: { uncompiledFiles }
        Mockito.`when`(mockDeployFileManager.getUncompiledFiles()).thenAnswer { filesProvider() }
        val mockDeployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(mockDeployTargetManager.hasDevice).thenReturn(hasDevice)

        return object : IMcpRuntime {
            override val logger: Logger
                get() = Logger.getInstance("TestMcpRuntime")
            override val project: Project
                get() = throw UnsupportedOperationException("not used in this test")
            override val deployTargetManager: IDeployTargetManager = mockDeployTargetManager
            override val deployStateManager: IDeployStateManager = deployStateManager
            override val deployFileManager: DeployFileManager = mockDeployFileManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) =
                    throw UnsupportedOperationException("not used in this test")
                override fun executeGradleCompileBlocking(autoConfirm: Boolean, useCleanAndReinstall: Boolean): GradleCompileExecutionResult =
                    throw UnsupportedOperationException("not used in this test")
                override fun resolveExecutionType(): String = "local"
                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult =
                    RemoteSshInfoResult(approved = false, message = "not used in this test")
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = isCompiling
                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: com.sickworm.intellij.jugg.compiler.CompileUiHandler, executor: com.intellij.execution.Executor?, runProfile: com.intellij.execution.configurations.RunProfile?, androidTestRunSpec: com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec?): com.intellij.execution.ExecutionResult =
                    throw UnsupportedOperationException("not used in this test")
                override fun forceReInstallNextTime() = throw UnsupportedOperationException("not used in this test")
                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean, isAlwaysRestartApp: Boolean): JuggRunInvocationResult =
                    throw UnsupportedOperationException("not used in this test")
            }
            override val incrementalCompileFallbackChecker: IIncrementalCompileFallbackChecker? = fallbackChecker
            override fun refreshChangedFilesForStatus() = statusRefresh()
        }
    }

    private fun writeFullBuildInfo(projectDir: File, buildTarget: BuildTarget) {
        val pathManager = JuggPathManager(projectDir)
        val fullBuildInfoFile = File(pathManager.compileContextDbDir, "full_build_info.json")
        fullBuildInfoFile.parentFile?.mkdirs()
        fullBuildInfoFile.writeText(
            FullBuildInfoSerializer().serialize(
                FullBuildInfo(
                    compileCommand = "./gradlew :app:assembleDebug",
                    buildTarget = buildTarget,
                    createdAt = 123L,
                )
            ),
            Charsets.UTF_8,
        )
    }

    private fun writeFullCompileState(projectDir: File, buildTarget: BuildTarget) {
        val pathManager = JuggPathManager(projectDir)
        writeFullBuildInfo(projectDir, buildTarget)
        File(pathManager.compileContextDbDir, "complete_flag").createNewFile()
        DeployHistoryData(
            fullCompileGitCommitHash = "abcdef",
            subModulesFullCompileGitCommitHash = null,
            incDeployTimes = 0,
            changedFiles = emptyMap(),
        ).save(File(pathManager.deployHistoryDbDir, "deploy_history.json"))
    }
}
