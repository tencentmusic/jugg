package com.sickworm.intellij.jugg.mcp

import org.junit.After
import org.junit.Assert
import org.junit.Before

abstract class McpInvokerTestBase {

    @Before
    fun setUpRuntime() {
        McpRuntimeHolder.runtime = object : IMcpRuntime {
            override fun restartApp(serial: String?): McpToolResult {
                return when (serial) {
                    null -> McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully. Serial not provided; selected device 'emulator-5554' is used.",
                        data = mapOf("device" to McpDeviceInfo(serial = "emulator-5554", name = "Pixel", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )

                    "invalid" -> McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully. Serial 'invalid' is invalid; fallback to selected device 'emulator-5554'.",
                        data = mapOf("device" to McpDeviceInfo(serial = "emulator-5554", name = "Pixel", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )

                    "none" -> McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "restart_app failed. Reason: No connected device is available.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.MCP_NO_DEVICE,
                    )

                    else -> McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully. Device selected by serial: $serial.",
                        data = mapOf("device" to McpDeviceInfo(serial = serial, name = "Specified", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                }
            }

            override fun compile(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "compile_only executed successfully.",
                    data = mapOf("isCompileSuccess" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun deploy(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "compile_and_deploy executed successfully.",
                    data = mapOf("isDeploySuccess" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun cleanReinstall(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "clean_reinstall_apk executed successfully.",
                    data = mapOf("cleanAndReinstall" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun forceGradleCompile(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "force_gradle_compile executed successfully.",
                    data = mapOf("triggered" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun deviceList(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "device_list executed successfully.",
                    data = mapOf("devices" to emptyList<Map<String, Any?>>()),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun screenshot(serial: String?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "screenshot executed successfully.",
                    data = mapOf("serial" to serial),
                    artifacts = listOf(McpArtifact(type = "image", path = "/tmp/a.png")),
                    errorCode = null,
                )
            }

            override fun record(
                serial: String?,
                durationSec: Int?,
                packageName: String?,
                activity: String?,
                tapX: Int?,
                tapY: Int?,
                preTapDelaySec: Double?,
                tapRepeat: Int?,
                tapIntervalSec: Double?,
                recordStartDelaySec: Double?,
            ): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "record executed successfully.",
                    data = mapOf("serial" to serial, "durationSec" to durationSec),
                    artifacts = listOf(McpArtifact(type = "video", path = "/tmp/a.mp4")),
                    errorCode = null,
                )
            }

            override fun layoutDump(serial: String?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "layout_dump executed successfully.",
                    data = mapOf("serial" to serial),
                    artifacts = listOf(McpArtifact(type = "xml", path = "/tmp/a.xml")),
                    errorCode = null,
                )
            }

            override fun appStart(serial: String?, packageName: String?, activity: String?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "app_start executed successfully.",
                    data = mapOf("serial" to serial, "packageName" to packageName, "activity" to activity),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun tap(serial: String?, x: Int?, y: Int?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "tap executed successfully.",
                    data = mapOf("serial" to serial, "x" to x, "y" to y),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }
        }
    }

    @After
    fun tearDownRuntime() {
        McpRuntimeHolder.runtime = null
    }

    protected fun initialize(invoker: McpInvoker) {
        val initResponse = invoker.invokeMcp(
            McpJsonRpcRequest(
                id = 100,
                method = McpJsonRpc.Method.Initialize,
                params = McpInitializeParams(
                    protocolVersion = McpJsonRpc.ProtocolVersion,
                    capabilities = emptyMap(),
                    clientInfo = McpPeerInfo("test-client", "1.0.0"),
                )
            )
        )
        Assert.assertNull(initResponse.error)
    }
}
