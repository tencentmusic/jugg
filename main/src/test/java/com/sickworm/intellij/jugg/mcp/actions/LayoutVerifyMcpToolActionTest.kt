package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.VerifyResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutVerifyMcpToolActionTest covers dumpFile mode (pure JSON parsing) and live query mode (socket).
 */
class LayoutVerifyMcpToolActionTest {

    // ---- dumpFile mode: validation ----

    @Test
    fun testDumpFileModeReturnsErrorWhenTargetMissing() {
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to "/tmp/fake.json"),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("target"))
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenAssertAndRelationBothMissing() {
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to "/tmp/fake.json",
                "target" to mapOf("resourceId" to "btn_ok"),
            ),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("assert or relation"))
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenFileNotFound() {
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to "/nonexistent/path/layout.json",
                "target" to mapOf("resourceId" to "btn_ok"),
                "assert" to mapOf("property" to "exists"),
            ),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("dumpFile not found"))
    }

    // ---- dumpFile mode: assert.property = exists ----

    @Test
    fun testDumpFileModePassesForExistsByResourceId() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"com.example:id/root","children":[
                {"className":"Button","id":"com.example:id/btn_ok","bounds":[0,100,300,200],"text":"OK","clickable":true}
            ]}}],"deviceInfo":{"density":3.0,"scaledDensity":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_ok"),
                "assert" to mapOf("property" to "exists"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: assert.property = text ----

    @Test
    fun testDumpFileModeAssertTextEquals() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Hello World"}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "value" to "Hello World"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS but got: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextFail() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Actual"}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "value" to "Expected"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL but got: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: assert.property = bounds.width with dp conversion ----

    @Test
    fun testDumpFileModeAssertBoundsWidthInDp() {
        // Element bounds [0,0,300,100], density=3.0 → width=300px=100dp
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,300,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "bounds.width", "value" to 100, "unit" to "dp"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS but got: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: target not found ----

    @Test
    fun testDumpFileModeReturnsErrorWhenTargetNotFound() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root","children":[]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "nonexistent_btn"),
                "assert" to mapOf("property" to "exists"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "Expected 'target not found' in message: ${result.message}",
            result.message.contains("not found", ignoreCase = true),
        )
        dumpFile.delete()
    }

    // ---- dumpFile mode: relation = spacing ----

    @Test
    fun testDumpFileModeRelationSpacingPass() {
        // Two buttons: A=[0,0,300,100], B=[0,116,300,200] → vertical spacing = 116-100=16px
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "target2" to mapOf("resourceId" to "btn_b"),
                "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 16, "tolerance" to 0),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingFail() {
        // Two buttons with spacing=16px but expected=20px → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "target2" to mapOf("resourceId" to "btn_b"),
                "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 20, "tolerance" to 0),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: relation = overlap ----

    @Test
    fun testDumpFileModeRelationOverlapNoOverlapPass() {
        // Non-overlapping elements → PASS (expected: no overlap)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[200,0,300,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "view_a"),
                "target2" to mapOf("resourceId" to "view_b"),
                "relation" to mapOf("type" to "overlap"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: clickable assertion ----

    @Test
    fun testDumpFileModeAssertClickableTrue() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,300,100],"clickable":true}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "clickable", "value" to true),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- live query mode: returns PASS from ViewHierarchyClient ----

    @Test
    fun testLiveQueryModePassResultFromClient() {
        val projectDir = createTempDir(prefix = "jugg_verify_live_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(
                VerifyResult(result = "PASS", message = "text = \"OK\" (expected: eq \"OK\")", actual = "OK", expected = "OK")
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "assert" to mapOf("property" to "text", "value" to "OK"),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeFailResultFromClient() {
        val projectDir = createTempDir(prefix = "jugg_verify_live_fail_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(
                VerifyResult(result = "FAIL", message = "text = \"Actual\" (expected: eq \"Expected\")", actual = "Actual", expected = "Expected")
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "assert" to mapOf("property" to "text", "value" to "Expected"),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeReturnsErrorWhenClientReturnsNull() {
        val projectDir = createTempDir(prefix = "jugg_verify_null_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(null)
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "assert" to mapOf("property" to "exists"),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue(
                "Expected 'unavailable' in message: ${result.message}",
                result.message.contains("unavailable", ignoreCase = true),
            )
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeReturnsNoDeviceErrorWhenNoDevice() {
        val projectDir = createTempDir(prefix = "jugg_verify_no_device_")
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())
        val action = LayoutVerifyMcpToolAction()

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "btn_ok"),
                "assert" to mapOf("property" to "exists"),
            ),
            buildRuntimeWithDeployManager(projectDir, deployTargetManager),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.errorCode)
        projectDir.deleteRecursively()
    }

    // ---- Helpers ----

    private fun writeDumpFile(json: String): File {
        val f = File.createTempFile("jugg_verify_dump_", ".json")
        f.writeText(json, StandardCharsets.UTF_8)
        return f
    }

    private fun buildRuntime(packageName: String?): com.sickworm.intellij.jugg.mcp.IMcpRuntime {
        val projectDir = createTempDir(prefix = "jugg_verify_rt_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        if (packageName != null) {
            Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        }
        return buildRuntimeWithDeployManager(projectDir, deployTargetManager)
    }

    private fun setupDevice(projectDir: File, packageName: String): SetupResult {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        return SetupResult(runtime = buildRuntimeWithDeployManager(projectDir, deployTargetManager))
    }

    private fun buildRuntimeWithDeployManager(
        projectDir: File,
        deployTargetManager: IDeployTargetManager,
    ): com.sickworm.intellij.jugg.mcp.IMcpRuntime {
        val project = Mockito.mock(com.intellij.openapi.project.Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(projectDir.absolutePath)
        return object : com.sickworm.intellij.jugg.mcp.IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
            override val project = project
            override val deployTargetManager = deployTargetManager
            override val forceGradleCompileHelper get() = throw UnsupportedOperationException()
            override val juggConfigurationRunner get() = throw UnsupportedOperationException()
            override fun isAppReadyDeploy(): Boolean = true
        }
    }

    private data class SetupResult(val runtime: com.sickworm.intellij.jugg.mcp.IMcpRuntime)

    private class FakeDeviceAdb : IDeviceAdb {
        override val displayName: String? = "fake"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true
        override fun execAdbShellCmd(cmd: String): String = ""
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = false
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class FakePlatformApi(
        private val adbByDevice: Map<IDevice, IDeviceAdb>,
    ) : com.sickworm.intellij.jugg.platform.IPlatformApi {
        override fun showDialog(title: String, content: String, okButtonText: String?, cancelButtonText: String?, isShowCancelButton: Boolean): Boolean = false
        override fun showChangeConfirmDialog(diffResult: com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult?, isRunLater: Boolean, logger: com.intellij.openapi.diagnostic.Logger): com.sickworm.intellij.jugg.ide.bean.ConfirmResult = throw UnsupportedOperationException()
        override fun showUserAndPasswordInputDialog(content: String, subTitle: String?, isPassword: Boolean, defaultInputText: String?, title: String?): String? = null
        override fun allAvailableJavaHomes(): List<String> = emptyList()
        override fun getGradleJdkPath(project: com.intellij.openapi.project.Project, logger: com.intellij.openapi.diagnostic.Logger): String? = null
        override fun getAndroidHomePath(logger: com.intellij.openapi.diagnostic.Logger): String? = null
        override fun getIdeVersion(): String = "test"
        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]
        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: com.intellij.openapi.diagnostic.Logger): Boolean = false
        override fun invokeMcp(request: com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse = throw UnsupportedOperationException()
        override fun getInitializedProjectDirs(): List<File> = emptyList()
        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) = throw UnsupportedOperationException()
    }
}
