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
import com.sickworm.intellij.jugg.mcp.viewhierarchy.LayoutDumpResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutDumpMcpToolActionTest verifies app-side server-only behavior (no uiautomator fallback).
 */
class LayoutDumpMcpToolActionTest {
    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testLayoutDumpUsesServerInlineJsonWhenAvailable() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_json_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = """{"windows":[{"title":"MainActivity"}]}""",
                    remoteFilePath = null,
                )
            )
        }.use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(result.message.contains("windows"))
            Assert.assertTrue(result.message.contains("nodes"))
            Assert.assertEquals(1, construction.constructed().size)
            Assert.assertEquals(0, setup.adb.pullCount)
            Assert.assertEquals("json", result.artifacts.firstOrNull()?.type)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            Assert.assertTrue(filePath.endsWith(".json"))
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            Assert.assertTrue(content.contains("MainActivity"))
        }
    }

    @Test
    fun testLayoutDumpUsesServerFileModeWhenAvailable() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_file_mode_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val remotePath = "/data/local/tmp/jugg_vh/layout_remote.json"

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = null,
                    remoteFilePath = remotePath,
                )
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertEquals(1, setup.adb.pullCount)
            Assert.assertTrue(setup.adb.pullFromPaths.contains(remotePath))
            Assert.assertEquals("json", result.artifacts.firstOrNull()?.type)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            Assert.assertTrue(filePath.endsWith(".json"))
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            Assert.assertTrue(content.contains("remote"))
        }
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenServerUnavailable() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_unavailable_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(null)
        }.use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(1, construction.constructed().size)
            Assert.assertTrue(result.message.contains("ViewHierarchy server is unavailable"))
            Assert.assertTrue(result.artifacts.isEmpty())
            Assert.assertEquals(0, setup.adb.pullCount)
        }
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenPackageNameMissing() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_no_pkg_")
        val setup = setup(projectDir, packageName = null)
        val action = LayoutDumpMcpToolAction()

        val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("failed to resolve package name"))
    }

    @Test
    fun testLayoutDumpNoDeviceReturnsNoDeviceError() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_no_device_")
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())
        val action = LayoutDumpMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            buildRuntime(projectDir, deployTargetManager)
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.errorCode)
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenAppNotReadyAfterRetries() {
        McpAppReadyGuard.preTimeoutOverrideForTest = 8L
        McpAppReadyGuard.prePollIntervalOverrideForTest = 1L
        val projectDir = createTempDir(prefix = "jugg_layout_dump_not_ready_")
        var checks = 0
        val setup = setup(
            projectDir = projectDir,
            packageName = "com.example.app",
            isAppReadyProvider = {
                checks += 1
                false
            },
        )
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java).use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue(result.message.contains("app is not ready"))
            Assert.assertTrue(checks >= 2)
            Assert.assertEquals(0, construction.constructed().size)
        }
    }

    @Test
    fun testLayoutDumpReturnsInlineContent() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_inline_content_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val inlineJson = """{"windows":[{"title":"InlineTest"}],"truncated":false}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = inlineJson,
                    remoteFilePath = null,
                )
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertNotNull("data.content should be present", data["content"])
            val contentStr = data["content"].toString()
            Assert.assertTrue("content should contain InlineTest", contentStr.contains("InlineTest"))
            Assert.assertEquals(false, data["inlineOmitted"])
            Assert.assertEquals(16, data["inlineThresholdKb"])
            Assert.assertTrue((data["contentBytes"] as Number).toInt() > 0)
        }
    }

    @Test
    fun testLayoutDumpPassesRootLayoutToClient() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_root_layout_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val rootLayoutId = "content"

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = """{"windows":[],"truncated":false,"rootLayout":"content"}""",
                    remoteFilePath = null,
                )
            )
        }.use { construction ->
            val result = action.execute(
                mapOf("projectDir" to projectDir.absolutePath, "rootLayout" to rootLayoutId),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            val client = construction.constructed().first()
            Mockito.verify(client).dumpLayout(rootLayoutId, true, true)
        }
    }

    @Test
    fun testLayoutDumpOmitInlineContentWhenPayloadTooLarge() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_large_payload_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val largeText = "x".repeat(20_000)
        val largeJson = """{"windows":[{"title":"MainActivity","root":{"className":"Root","text":"$largeText","bounds":[0,0,10,10]}}],"truncated":false}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = largeJson,
                    remoteFilePath = null,
                )
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals(true, data["inlineOmitted"])
            Assert.assertEquals(16, data["inlineThresholdKb"])
            Assert.assertTrue((data["contentBytes"] as Number).toInt() > 16 * 1024)
            Assert.assertFalse(data.containsKey("content"))
        }
    }

    private fun setup(
        projectDir: File,
        packageName: String? = null,
        isAppReadyProvider: () -> Boolean = { true },
    ): SetupResult {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        if (packageName != null) {
            Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        }
        return SetupResult(
            runtime = buildRuntime(projectDir, deployTargetManager, isAppReadyProvider),
            adb = adb,
        )
    }

    private fun buildRuntime(
        projectDir: File,
        deployTargetManager: IDeployTargetManager,
        isAppReadyProvider: () -> Boolean = { true },
    ): IMcpRuntime {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(projectDir.absolutePath)
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
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
                return isAppReadyProvider()
            }
        }
    }

    private data class SetupResult(
        val runtime: IMcpRuntime,
        val adb: FakeDeviceAdb,
    )

    private class FakeDeviceAdb : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        val pullFromPaths: MutableList<String> = mutableListOf()
        var pullCount: Int = 0
            private set

        override fun execAdbShellCmd(cmd: String): String = ""

        override fun push(from: File, to: String): Boolean = true

        override fun pull(from: String, to: File): Boolean {
            pullCount += 1
            pullFromPaths.add(from)
            to.parentFile?.mkdirs()
            if (from.endsWith(".json")) {
                to.writeText("""{"windows":[{"source":"remote"}]}""", StandardCharsets.UTF_8)
            }
            return true
        }

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
