package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.FindAndTapResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchedElementData
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * RestartAppMcpToolActionTest verifies restart_app behavior and optional post-restart tap_actions flow.
 */
class RestartAppMcpToolActionTest {
    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testInputSchemaShouldExposeTapActionsTapCompatibleFields() {
        val properties = RestartAppMcpToolAction().definition.inputSchema.properties
        val tapActions = properties["tap_actions"]
        Assert.assertNotNull(tapActions)
        Assert.assertEquals("array", tapActions?.type)

        val item = tapActions?.items
        Assert.assertNotNull(item)
        Assert.assertEquals("object", item?.type)
        Assert.assertEquals(false, item?.additionalProperties)

        val itemProperties = item?.properties ?: emptyMap()
        Assert.assertTrue(itemProperties.containsKey("action"))
        Assert.assertTrue(itemProperties.containsKey("x"))
        Assert.assertTrue(itemProperties.containsKey("y"))
        Assert.assertTrue(itemProperties.containsKey("endX"))
        Assert.assertTrue(itemProperties.containsKey("endY"))
        Assert.assertTrue(itemProperties.containsKey("xPercent"))
        Assert.assertTrue(itemProperties.containsKey("yPercent"))
        Assert.assertTrue(itemProperties.containsKey("endXPercent"))
        Assert.assertTrue(itemProperties.containsKey("endYPercent"))
        Assert.assertTrue(itemProperties.containsKey("duration"))
        Assert.assertTrue(itemProperties.containsKey("text"))
        Assert.assertTrue(itemProperties.containsKey("resourceId"))
        Assert.assertTrue(itemProperties.containsKey("contentDesc"))
        Assert.assertTrue(itemProperties.containsKey("className"))
        Assert.assertEquals(listOf("tap", "longPress", "swipe"), itemProperties["action"]?.`enum`)
    }

    @Test
    fun testRestartAppShouldExecuteTapActionsSequentially() {
        val (runtime, _) = runtimeWithMocks()
        val action = RestartAppMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("MCP Test Page", null, null, null)).thenReturn(
                FindAndTapResult.Success(
                    x = 100,
                    y = 200,
                    matchedElement = matchedElement(text = "MCP Test Page", resourceId = "btn_mcp_test_page"),
                    matchCount = 1,
                )
            )
            Mockito.`when`(mock.findAndTap(null, "btn_some_secondary_entry", null, null)).thenReturn(
                FindAndTapResult.Success(
                    x = 300,
                    y = 400,
                    matchedElement = matchedElement(text = "Secondary Entry", resourceId = "btn_some_secondary_entry"),
                    matchCount = 1,
                )
            )
        }.use { construction ->
            val result = action.execute(
                mapOf(
                    "projectDir" to "/tmp/test",
                    "tap_actions" to listOf(
                        mapOf("text" to "MCP Test Page"),
                        mapOf("resourceId" to "btn_some_secondary_entry"),
                    ),
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertEquals(2, construction.constructed().size)
            val clients = construction.constructed()
            Mockito.verify(clients[0]).findAndTap("MCP Test Page", null, null, null)
            Mockito.verify(clients[1]).findAndTap(null, "btn_some_secondary_entry", null, null)
            Assert.assertTrue(result.message.contains("restart_app executed successfully"))
        }
    }

    @Test
    fun testRestartAppShouldSupportSwipeAndLongPressInTapActions() {
        val (runtime, adb) = runtimeWithMocksAndAdb()
        val action = RestartAppMcpToolAction()

        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "tap_actions" to listOf(
                    mapOf(
                        "action" to "swipe",
                        "x" to 10,
                        "y" to 20,
                        "endX" to 200,
                        "endY" to 300,
                        "duration" to 350,
                    ),
                    mapOf(
                        "action" to "longPress",
                        "x" to 100,
                        "y" to 120,
                        "duration" to 700,
                    ),
                ),
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(adb.executedCommands.contains("input swipe 10 20 200 300 350"))
        Assert.assertTrue(adb.executedCommands.contains("input swipe 100 120 100 120 700"))
    }

    @Test
    fun testRestartAppShouldRetryTapActionWhenElementNotFoundThenSucceed() {
        McpAppReadyGuard.sleepForTest = {}
        val (runtime, _) = runtimeWithMocks()
        val action = RestartAppMcpToolAction()
        var attempt = 0

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("MCP Test Page", null, null, null)).thenAnswer {
                attempt += 1
                if (attempt < 3) {
                    FindAndTapResult.NotFound(
                        candidates = emptyList(),
                        message = "not found",
                    )
                } else {
                    FindAndTapResult.Success(
                        x = 100,
                        y = 200,
                        matchedElement = matchedElement(text = "MCP Test Page", resourceId = "btn_mcp_test_page"),
                        matchCount = 1,
                    )
                }
            }
        }.use { construction ->
            val result = action.execute(
                mapOf(
                    "projectDir" to "/tmp/test",
                    "tap_actions" to listOf(
                        mapOf("text" to "MCP Test Page"),
                    ),
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertEquals(3, construction.constructed().size)
            Assert.assertEquals(3, attempt)
        }
    }

    @Test
    fun testRestartAppShouldReturnStepIndexWhenTapActionFails() {
        McpAppReadyGuard.sleepForTest = {}
        val (runtime, _) = runtimeWithMocks()
        val action = RestartAppMcpToolAction()
        var step = 0

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            step += 1
            if (step == 1) {
                Mockito.`when`(mock.findAndTap("MCP Test Page", null, null, null)).thenReturn(
                    FindAndTapResult.Success(
                        x = 100,
                        y = 200,
                        matchedElement = matchedElement(text = "MCP Test Page", resourceId = "btn_mcp_test_page"),
                        matchCount = 1,
                    )
                )
            } else {
                Mockito.`when`(mock.findAndTap(null, "btn_missing", null, null)).thenReturn(
                    FindAndTapResult.NotFound(
                        candidates = emptyList(),
                        message = "not found",
                    )
                )
            }
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to "/tmp/test",
                    "tap_actions" to listOf(
                        mapOf("text" to "MCP Test Page"),
                        mapOf("resourceId" to "btn_missing"),
                    ),
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("tap_actions step 2 failed"))
            Assert.assertTrue(result.message.contains("No matching UI element found"))
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals(2, data["failedStep"])
        }
    }

    private fun matchedElement(text: String, resourceId: String): MatchedElementData {
        return MatchedElementData(
            text = text,
            className = "Button",
            resourceId = resourceId,
            contentDesc = "",
            bounds = listOf(0, 0, 100, 100),
            centerX = 50,
            centerY = 50,
        )
    }

    private fun runtimeWithMocks(): Pair<IMcpRuntime, IDeployTargetManager> {
        val (runtime, deployTargetManager, _) = runtimeWithMocksInternal()
        return runtime to deployTargetManager
    }

    private fun runtimeWithMocksAndAdb(): Pair<IMcpRuntime, FakeDeviceAdb> {
        val (runtime, _, adb) = runtimeWithMocksInternal()
        return runtime to adb
    }

    private fun runtimeWithMocksInternal(): Triple<IMcpRuntime, IDeployTargetManager, FakeDeviceAdb> {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.restartApp(device)).thenReturn(true)

        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/test")

        val runtime = object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("RestartAppMcpToolActionTest")
            override val project: Project = project
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used")
                }

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used")
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    throw UnsupportedOperationException("not used")
                }
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false
                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler) =
                    throw UnsupportedOperationException("not used")

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean): JuggRunInvocationResult {
                    throw UnsupportedOperationException("not used")
                }
            }

            override fun isAppReadyDeploy(): Boolean {
                return true
            }
        }

        return Triple(runtime, deployTargetManager, adb)
    }

    private class FakeDeviceAdb : IDeviceAdb {
        val executedCommands = mutableListOf<String>()
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            executedCommands.add(cmd)
            if (cmd == "dumpsys activity activities") {
                return "topResumedActivity=ActivityRecord{100 com.example.app/.MainActivity t10}"
            }
            return ""
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class FakePlatformApi(
        private val adbByDevice: Map<IDevice, IDeviceAdb>,
    ) : IPlatformApi {
        override fun showDialog(
            title: String,
            content: String,
            okButtonText: String?,
            cancelButtonText: String?,
            isShowCancelButton: Boolean,
        ): Boolean = false

        override fun showChangeConfirmDialog(
            diffResult: DependencyDiffResult?,
            isRunLater: Boolean,
            logger: Logger,
        ): ConfirmResult {
            throw UnsupportedOperationException("not used")
        }

        override fun showUserAndPasswordInputDialog(
            content: String,
            subTitle: String?,
            isPassword: Boolean,
            defaultInputText: String?,
            title: String?,
        ): String? = null

        override fun allAvailableJavaHomes(): List<String> = emptyList()

        override fun getGradleJdkPath(project: Project, logger: Logger): String? = null

        override fun getAndroidHomePath(logger: Logger): String? = null

        override fun getIdeVersion(): String = "test"

        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]

        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean = false

        override fun invokeMcp(request: com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse {
            throw UnsupportedOperationException("not used")
        }

        override fun getInitializedProjectDirs(): List<File> = emptyList()

        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
            throw UnsupportedOperationException("not used")
        }
    }
}
