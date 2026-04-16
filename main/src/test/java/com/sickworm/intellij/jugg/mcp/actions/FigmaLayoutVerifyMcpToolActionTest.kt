package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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
 * Tests for FigmaLayoutVerifyMcpToolAction after Method-B refactoring:
 * - androidJsonPath parameter is completely removed
 * - layout_dump is called internally; its errors are passed through to the caller
 */
class FigmaLayoutVerifyMcpToolActionTest {

    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    // --- Happy path ---

    @Test
    fun testVerifySucceedsWithoutAndroidJsonPath() {
        // androidJsonPath must NOT be required; internal layout_dump supplies the android layout
        val projectDir = createTempDir(prefix = "jugg_figma_verify_ok_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = FigmaLayoutVerifyMcpToolAction()
        val figmaFile = createMinimalFigmaJsonFile()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = MINIMAL_ANDROID_JSON, remoteFilePath = null)
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "figmaJsonPath" to figmaFile.absolutePath
                ),
                setup.runtime
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
        }
    }

    // --- Dump error passthrough ---

    @Test
    fun testVerifyPassthroughNoDeviceError() {
        // When internal layout_dump fails with NO_DEVICE, the same errorCode must be returned
        val projectDir = createTempDir(prefix = "jugg_figma_verify_no_dev_")
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())
        val action = FigmaLayoutVerifyMcpToolAction()
        val figmaFile = createMinimalFigmaJsonFile()

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "figmaJsonPath" to figmaFile.absolutePath
            ),
            buildRuntime(projectDir, deployTargetManager)
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
    }

    @Test
    fun testVerifyPassthroughAppNotReadyError() {
        // When internal layout_dump fails because the app is not ready, the error is passed through
        McpAppReadyGuard.preTimeoutOverrideForTest = 8L
        McpAppReadyGuard.prePollIntervalOverrideForTest = 1L
        val projectDir = createTempDir(prefix = "jugg_figma_verify_not_ready_")
        val setup = setup(projectDir, packageName = "com.example.app", isAppReadyProvider = { false })
        val action = FigmaLayoutVerifyMcpToolAction()
        val figmaFile = createMinimalFigmaJsonFile()

        Mockito.mockConstruction(ViewHierarchyClient::class.java).use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "figmaJsonPath" to figmaFile.absolutePath
                ),
                setup.runtime
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue(
                "message should mention app is not ready: ${result.message}",
                result.message.contains("app is not ready")
            )
        }
    }

    // --- Parameter validation ---

    @Test
    fun testVerifyReturnsErrorWhenFigmaJsonPathMissing() {
        val projectDir = createTempDir(prefix = "jugg_figma_verify_no_path_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = FigmaLayoutVerifyMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            setup.runtime
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
    }

    // --- Figma JSON validation ---

    @Test
    fun testVerifyReturnsInvalidFigmaFormatForBadJson() {
        // A JSON file that is syntactically valid but does not match the expected Figma schema
        val projectDir = createTempDir(prefix = "jugg_figma_verify_bad_fmt_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = FigmaLayoutVerifyMcpToolAction()
        val badFigmaFile = File.createTempFile("figma_bad", ".json").apply {
            writeText("""{"not_a_figma_node": true}""")
            deleteOnExit()
        }

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = MINIMAL_ANDROID_JSON, remoteFilePath = null)
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "figmaJsonPath" to badFigmaFile.absolutePath
                ),
                setup.runtime
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals("INVALID_FIGMA_FORMAT", result.errorCode)
        }
    }

    // --- Helpers ---

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
            adb = adb
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
            override val logger: Logger
                get() = Logger.getInstance("TestMcpRuntime")
            override val project: Project = project
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) =
                    throw UnsupportedOperationException("not used")

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult = throw UnsupportedOperationException("not used")

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult =
                    throw UnsupportedOperationException("not used")
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = FakeJuggConfigurationRunner()

            override fun isAppReadyDeploy(): Boolean = isAppReadyProvider()
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

        override fun execAdbShellCmd(cmd: String): String = ""
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean {
            to.parentFile?.mkdirs()
            to.writeText(MINIMAL_ANDROID_JSON, StandardCharsets.UTF_8)
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
        ): ConfirmResult = throw UnsupportedOperationException("not used")

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

        override fun invokeMcp(request: com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse =
            throw UnsupportedOperationException("not used")

        override fun getInitializedProjectDirs(): List<File> = emptyList()

        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) =
            throw UnsupportedOperationException("not used")
    }

    private fun createMinimalFigmaJsonFile(): File {
        return File.createTempFile("figma_test", ".json").apply {
            writeText(MINIMAL_FIGMA_JSON)
            deleteOnExit()
        }
    }

    companion object {
        /** Minimal Figma JSON that passes FigmaJsonParser.validate(). */
        private val MINIMAL_FIGMA_JSON = """
            {
              "id": "1",
              "name": "Root",
              "layout": [0, 0, 375, 812],
              "children": []
            }
        """.trimIndent()

        /** Minimal Android layout JSON with deviceInfo required by FigmaLayoutVerifyMcpToolAction. */
        private val MINIMAL_ANDROID_JSON = """
            {
              "windows": [
                {
                  "title": "TestActivity",
                  "root": {
                    "className": "FrameLayout",
                    "id": "1",
                    "bounds": [0, 0, 375, 812],
                    "children": []
                  }
                }
              ],
              "truncated": false,
              "deviceInfo": {
                "screenWidth": 375,
                "screenHeight": 812,
                "density": 1.0,
                "scaledDensity": 1.0
              }
            }
        """.trimIndent()
    }
}
