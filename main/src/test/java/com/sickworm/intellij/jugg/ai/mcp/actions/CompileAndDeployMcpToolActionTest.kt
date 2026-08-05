package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.LastCompileTimestampRegistry
import com.sickworm.intellij.jugg.deploy.LastChangedDeployRegistry
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class CompileAndDeployMcpToolActionTest {

    @Before
    fun setUp() {
        CompileJobManager.resetForTest()
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        CompileJobManager.resetForTest()
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testFailureDetailIsTruncatedAndFullLogIsArtifact() {
        val fullDetail = buildString {
            repeat(16_000) { append('x') }
        }
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "mock run failed",
                detail = fullDetail,
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        val detailPreview = data["detail"] as String
        Assert.assertTrue(detailPreview.contains("[truncated"))
        Assert.assertEquals(16_000.0, (data["detailLength"] as Number).toDouble(), 0.0)
        Assert.assertEquals(true, data["detailTruncated"])
        Assert.assertEquals(true, data["accepted"])
        Assert.assertEquals(true, data["isFinal"])
        Assert.assertEquals("failed", data["status"])
        Assert.assertEquals("local", data["executionType"])
        Assert.assertEquals(CompileJobManager.COMPILE_LATEST_LOG_PATH, data["logPath"])
        Assert.assertFalse(data.containsKey("isCompileSuccess"))
        Assert.assertFalse(data.containsKey("isDeploySuccess"))
        Assert.assertFalse(result.artifacts.isEmpty())
        val logArtifact = result.artifacts.first()
        Assert.assertEquals("log", logArtifact.type)
        Assert.assertTrue(Files.exists(Paths.get(logArtifact.path)))
    }

    @Test
    fun testLongFailureDetailKeepsHeadAndTail() {
        val fullDetail = buildString {
            appendLine("Compile project failed, please check the error message.")
            appendLine("[Jugg] Found error in logs:")
            appendLine("e: java.lang.IllegalAccessError: superclass access check failed")
            repeat(14_000) { append('x') }
            appendLine()
            appendLine("> Task :library1:kaptGenerateStubsDebugKotlin FAILED")
            appendLine("BUILD FAILED in 9s")
        }
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "mock run failed",
                detail = fullDetail,
            )
        )

        val result = action.execute(emptyMap(), runtime)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        val detailPreview = data["detail"] as String
        Assert.assertTrue(detailPreview.contains("Compile project failed, please check the error message."))
        Assert.assertTrue(detailPreview.contains("[Jugg] Found error in logs:"))
        Assert.assertTrue(detailPreview.contains("java.lang.IllegalAccessError"))
        Assert.assertTrue(detailPreview.contains("> Task :library1:kaptGenerateStubsDebugKotlin FAILED"))
        Assert.assertTrue(detailPreview.contains("BUILD FAILED in 9s"))
        Assert.assertTrue(detailPreview.contains("[truncated"))
        Assert.assertTrue(detailPreview.contains("showing first 4096 and last 4096 chars"))
        Assert.assertEquals(true, data["detailTruncated"])
    }

    @Test
    fun testSuccessIncludesDetailWhenNonEmpty() {
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
                detail = "compile logs that should be returned on success",
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertTrue(data.containsKey("runResult"))
        Assert.assertEquals(true, data["accepted"])
        Assert.assertEquals(true, data["isFinal"])
        Assert.assertEquals("success", data["status"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(true, data["isDeploySuccess"])
        Assert.assertEquals("local", data["executionType"])
        Assert.assertEquals(CompileJobManager.COMPILE_LATEST_LOG_PATH, data["logPath"])
        Assert.assertEquals("compile logs that should be returned on success", data["detail"])
        Assert.assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun testCompileOnlyReturnsCompileAndDeployResult() {
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = false,
                    isCancel = false,
                ),
                detail = "",
            )
        )

        val result = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.COMPILE,
            isSkipDeploy = true,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(false, data["isDeploySuccess"])
    }

    @Test
    fun testTimeoutReturnsRunningThenJobCanReachFinalState() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        McpAppReadyGuard.postTimeoutOverrideForTest = 1_500L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
        val action = CompileAndDeployMcpToolAction()
        var readyChecks = 0
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                Thread.sleep(80L)
                JuggRunInvocationResult(
                    isSuccess = true,
                    runResult = RunResult(
                        isGradleCompile = false,
                        isCompileSuccess = true,
                        isDeploySuccess = true,
                        isCancel = false,
                    ),
                    detail = "",
                )
            },
            isAppReadyProvider = {
                readyChecks += 1
                readyChecks >= 2
            },
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["accepted"])
        Assert.assertEquals(false, data["isFinal"])
        Assert.assertEquals("running", data["status"])
        Assert.assertEquals("local", data["executionType"])
        Assert.assertEquals(CompileJobManager.COMPILE_LATEST_LOG_PATH, data["logPath"])
        val jobId = data["jobId"] as String
        Assert.assertTrue(jobId.isNotBlank())
        Assert.assertTrue(result.message.contains("get-compile-status"))

        val finalState = waitUntilTerminal(jobId)
        Assert.assertEquals("success", finalState.status)
    }

    @Test
    fun testIsAlwaysRestartAppDefaultsTrue() {
        var capturedIsAlwaysRestartApp: Boolean? = null
        val runtime = runtimeWithRunnerCapturing(runFirstConfiguration = { isAlwaysRestartApp ->
            capturedIsAlwaysRestartApp = isAlwaysRestartApp
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            )
        })

        CompileAndDeployMcpToolAction().execute(emptyMap(), runtime)

        Assert.assertEquals(true, capturedIsAlwaysRestartApp)
    }

    @Test
    fun testIsAlwaysRestartAppFalseIsPassedThrough() {
        var capturedIsAlwaysRestartApp: Boolean? = null
        val runtime = runtimeWithRunnerCapturing(runFirstConfiguration = { isAlwaysRestartApp ->
            capturedIsAlwaysRestartApp = isAlwaysRestartApp
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            )
        })

        CompileAndDeployMcpToolAction().execute(mapOf("alwaysRestartApp" to false), runtime)

        Assert.assertEquals(false, capturedIsAlwaysRestartApp)
    }

    @Test
    fun testCompileSuccessDoesNotWaitForAppReadyByDefault() {
        McpAppReadyGuard.postTimeoutOverrideForTest = 5L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                JuggRunInvocationResult(
                    isSuccess = true,
                    runResult = RunResult(
                        isGradleCompile = false,
                        isCompileSuccess = true,
                        isDeploySuccess = true,
                        isCancel = false,
                    ),
                    detail = "",
                )
            },
            isAppReadyProvider = { false },
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("success", data["status"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(true, data["isDeploySuccess"])
    }

    @Test
    fun testCompileSuccessTurnsFailedWhenExplicitAppReadyWaitTimesOut() {
        McpAppReadyGuard.postTimeoutOverrideForTest = 5L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                JuggRunInvocationResult(
                    isSuccess = true,
                    runResult = RunResult(
                        isGradleCompile = false,
                        isCompileSuccess = true,
                        isDeploySuccess = true,
                        isCancel = false,
                    ),
                    detail = "",
                )
            },
            isAppReadyProvider = { false },
        )

        val result = action.execute(mapOf("waitAppReadyAfterSuccess" to true), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("failed", data["status"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(false, data["isDeploySuccess"])
    }

    @Test
    fun testDeployDoesNotRecordLastCompileTimeDirectly() {
        val projectDir = "/fake/project/deploy"
        val registry = LastCompileTimestampRegistry.INSTANCE
        registry.setTimestamp(projectDir, "2000-01-01 00:00:00")
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
                detail = "",
            ),
        )

        action.execute(mapOf("projectDir" to projectDir), runtime)

        Assert.assertEquals("2000-01-01 00:00:00", registry.getTimestamp(projectDir))
    }

    private fun runtimeWithResult(
        result: JuggRunInvocationResult,
        projectDir: String = "/fake/project",
    ): IMcpRuntime {
        return runtimeWithRunner(
            runFirstConfiguration = { result },
            projectDir = projectDir,
        )
    }

    private fun runtimeWithRunner(
        runFirstConfiguration: () -> JuggRunInvocationResult,
        isAppReadyProvider: () -> Boolean = { true },
        projectDir: String = "/fake/project",
    ): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
            override val projectDir: String = projectDir

            override val deployTargetManager: IDeployTargetManager
                get() = throw UnsupportedOperationException("not used in this test")

            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun executeGradleCompileBlocking(autoConfirm: Boolean, useCleanAndReinstall: Boolean): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun resolveExecutionType(): String {
                    return "local"
                }

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    return RemoteSshInfoResult(
                        approved = false,
                        message = "not used in this test",
                    )
                }
            }

            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false

                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler, executor: Executor?, runProfile: RunProfile?, androidTestRunSpec: AndroidTestRunSpec?): ExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean, isAlwaysRestartApp: Boolean): JuggRunInvocationResult {
                    return runFirstConfiguration()
                }
            }

            override fun isAppReadyDeploy(): Boolean {
                return isAppReadyProvider()
            }
        }
    }

    private fun runtimeWithRunnerCapturing(
        runFirstConfiguration: (isAlwaysRestartApp: Boolean) -> JuggRunInvocationResult,
        isAppReadyProvider: () -> Boolean = { true },
    ): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")

            override val deployTargetManager: IDeployTargetManager
                get() = throw UnsupportedOperationException("not used in this test")

            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun executeGradleCompileBlocking(autoConfirm: Boolean, useCleanAndReinstall: Boolean): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    return RemoteSshInfoResult(approved = false, message = "not used in this test")
                }
            }

            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false

                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler, executor: Executor?, runProfile: RunProfile?, androidTestRunSpec: AndroidTestRunSpec?): ExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean, isAlwaysRestartApp: Boolean): JuggRunInvocationResult {
                    return runFirstConfiguration(isAlwaysRestartApp)
                }
            }

            override fun isAppReadyDeploy(): Boolean = isAppReadyProvider()
        }
    }

    @Test
    fun testSuccessMessageIncludesCompiledFiles() {
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            )
        )

        val result = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.COMPILE,
            isSkipDeploy = true,
            compiledFiles = listOf("Foo.kt", "Bar.java", "Baz.kt"),
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(
            "message should contain compiled files info, got: ${result.message}",
            result.message.contains("compile executed successfully.")
        )
        Assert.assertTrue(
            "message should contain compiled files info, got: ${result.message}",
            result.message.contains("Compiled files (total: 3): Foo.kt, Bar.java, Baz.kt")
        )
    }

    @Test
    fun testCompileWithoutChangedFilesShowsNoPendingMessage() {
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            )
        )

        val result = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.COMPILE,
            isSkipDeploy = true,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals("compile executed successfully. No pending file changes.", result.message)
    }

    @Test
    fun testAsyncCompileWithoutChangedFilesKeepsNoPendingMessage() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                Thread.sleep(80L)
                JuggRunInvocationResult(
                    isSuccess = true,
                    runResult = RunResult(
                        isGradleCompile = false,
                        isCompileSuccess = true,
                        isDeploySuccess = false,
                        isCancel = false,
                    ),
                )
            },
        )

        val triggerResult = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.COMPILE,
            isSkipDeploy = true,
        )
        @Suppress("UNCHECKED_CAST")
        val jobId = (triggerResult.data as Map<String, Any>)["jobId"] as String
        waitUntilTerminal(jobId)

        val statusResult = GetCompileStatusMcpToolAction().execute(
            mapOf("projectDir" to "/fake/project", "jobId" to jobId),
            runtime,
        )

        @Suppress("UNCHECKED_CAST")
        val statusData = statusResult.data as Map<String, Any>
        Assert.assertEquals("compile executed successfully. No pending file changes.", statusData["message"])
    }

    @Test
    fun testAsyncDeployWithoutChangedFilesKeepsNoPendingMessage() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        val projectDir = "/fake/project/async-no-pending-deploy"
        LastChangedDeployRegistry.INSTANCE.record(
            projectDir = projectDir,
            files = listOf(File("$projectDir/app/src/main/java/Foo.kt")),
        )
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                Thread.sleep(80L)
                JuggRunInvocationResult(
                    isSuccess = true,
                    runResult = RunResult(
                        isGradleCompile = false,
                        isCompileSuccess = true,
                        isDeploySuccess = true,
                        isCancel = false,
                    ),
                )
            },
            projectDir = projectDir,
        )

        val triggerResult = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.DEPLOY,
        )
        @Suppress("UNCHECKED_CAST")
        val jobId = (triggerResult.data as Map<String, Any>)["jobId"] as String
        waitUntilTerminal(jobId)

        val statusResult = GetCompileStatusMcpToolAction().execute(
            mapOf("projectDir" to projectDir, "jobId" to jobId),
            runtime,
        )

        @Suppress("UNCHECKED_CAST")
        val statusData = statusResult.data as Map<String, Any>
        val message = statusData["message"] as String
        Assert.assertTrue(message.startsWith("deploy executed successfully. No pending file changes."))
        Assert.assertTrue(message.contains("Last successful deployment with file changes:"))
        Assert.assertTrue(message.contains("files (1): Foo.kt"))
    }

    @Test
    fun testNoPendingDeployShowsLastSuccessfulChangedDeployment() {
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            ),
            projectDir = "/fake/project/last-changed-deploy",
        )

        LastChangedDeployRegistry.INSTANCE.record(
            projectDir = "/fake/project/last-changed-deploy",
            files = listOf(
                File("/fake/project/last-changed-deploy/module_features/feature-bubble/BubbleMsgAudio.kt"),
                File("/fake/project/last-changed-deploy/module_features/feature-bubble/BubbleMsgCell.kt"),
            ),
        )
        val result = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.DEPLOY,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(result.message.contains("All changes currently detected by Jugg are already deployed."))
        Assert.assertTrue(result.message.contains("Last successful deployment with file changes:"))
        Assert.assertTrue(result.message.contains("deployedAt:"))
        Assert.assertTrue(result.message.contains("ago)"))
        Assert.assertTrue(result.message.contains("files (2): BubbleMsgAudio.kt, BubbleMsgCell.kt"))
        Assert.assertFalse(result.message.contains("module_features/feature-bubble"))
    }

    @Test
    fun testNoPendingDeployLimitsLastDeploymentFilePreview() {
        val projectDir = "/fake/project/last-changed-deploy-preview"
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            ),
            projectDir = projectDir,
        )
        LastChangedDeployRegistry.INSTANCE.record(
            projectDir = projectDir,
            files = (1..22).map { File("$projectDir/module/File$it.kt") },
        )

        val result = CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = McpToolActionRegistry.ToolNames.DEPLOY,
        )

        Assert.assertTrue(result.message.contains("files (22): File1.kt, File2.kt"))
        Assert.assertTrue(result.message.contains("File20.kt, ... and 2 more"))
        Assert.assertFalse(result.message.contains("module/File21.kt"))
    }

    @Test
    fun testCompileSuccessDeployFailedMessageSaysDeploy() {
        // When compile succeeds but deploy fails due to no device, errorMessage should
        // indicate "no device", not a generic "deploy failed" or wrong "compile failed".
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "no device",
                detail = "No device found. Stop deploying.",
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = false,
                    isCancel = false,
                    failedReason = "no device",
                ),
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "message should mention 'no device', got: ${result.message}",
            result.message.contains("no device"),
        )
        Assert.assertFalse(
            "message should NOT say 'compile failed', got: ${result.message}",
            result.message.contains("compile failed"),
        )
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("no device", data["message"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(false, data["isDeploySuccess"])
    }

    @Test
    fun testAsyncFailureDetailIsReturnedByGetCompileStatus() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        val errorDetail = "error: unresolved reference: Foo"
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                Thread.sleep(80L)
                JuggRunInvocationResult(
                    isSuccess = false,
                    errorMessage = "compile failed",
                    detail = errorDetail,
                )
            },
        )

        val triggerResult = action.execute(emptyMap(), runtime)
        Assert.assertEquals(McpToolStatus.OK, triggerResult.status)
        @Suppress("UNCHECKED_CAST")
        val triggerData = triggerResult.data as Map<String, Any>
        val jobId = triggerData["jobId"] as String
        Assert.assertEquals("running", triggerData["status"])

        val finalState = waitUntilTerminal(jobId)
        Assert.assertEquals("failed", finalState.status)

        val getStatusAction = GetCompileStatusMcpToolAction()
        val statusResult = getStatusAction.execute(
            mapOf("projectDir" to "/fake/project", "jobId" to jobId),
            runtimeWithResult(JuggRunInvocationResult(isSuccess = true)),
        )

        Assert.assertEquals(McpToolStatus.ERROR, statusResult.status)
        @Suppress("UNCHECKED_CAST")
        val statusData = statusResult.data as Map<String, Any>
        Assert.assertEquals("failed", statusData["status"])
        Assert.assertEquals(errorDetail, statusData["detail"])
    }

    @Test
    fun testAsyncFailureLongDetailIsTruncatedByGetCompileStatus() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        val longDetail = buildString { repeat(16_000) { append('x') } }
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithRunner(
            runFirstConfiguration = {
                Thread.sleep(80L)
                JuggRunInvocationResult(
                    isSuccess = false,
                    errorMessage = "compile failed",
                    detail = longDetail,
                )
            },
        )

        val triggerResult = action.execute(emptyMap(), runtime)
        @Suppress("UNCHECKED_CAST")
        val jobId = (triggerResult.data as Map<String, Any>)["jobId"] as String
        waitUntilTerminal(jobId)

        val getStatusAction = GetCompileStatusMcpToolAction()
        val statusResult = getStatusAction.execute(
            mapOf("projectDir" to "/fake/project", "jobId" to jobId),
            runtimeWithResult(JuggRunInvocationResult(isSuccess = true)),
        )

        @Suppress("UNCHECKED_CAST")
        val statusData = statusResult.data as Map<String, Any>
        val detailPreview = statusData["detail"] as String
        Assert.assertTrue(detailPreview.contains("[truncated"))
        Assert.assertEquals(16_000.0, (statusData["detailLength"] as Number).toDouble(), 0.0)
        Assert.assertEquals(true, statusData["detailTruncated"])
        Assert.assertFalse(statusResult.artifacts.isEmpty())
    }

    private fun waitUntilTerminal(jobId: String): CompileJobStatus {
        val deadline = System.currentTimeMillis() + 1_500L
        while (System.currentTimeMillis() <= deadline) {
            val state = CompileJobManager.getStatus(jobId)
            if (state.status != "running") {
                return state
            }
            Thread.sleep(20L)
        }
        return CompileJobManager.getStatus(jobId)
    }
}
