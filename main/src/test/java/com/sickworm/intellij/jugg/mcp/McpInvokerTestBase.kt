package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.mcp.actions.McpToolAction
import com.sickworm.intellij.jugg.mcp.actions.McpToolActionRegistry
import org.junit.Assert
import org.junit.Before

abstract class McpInvokerTestBase {

    @Before
    fun setUpRuntime() {
        McpRuntimeHolder.runtime = object : IMcpRuntime {
            override val project: Project
                get() = TODO("Not yet implemented")
            override val deployTargetManager: IDeployTargetManager
                get() = TODO("Not yet implemented")
            override val forceGradleCompileHelper: ForceGradleCompileHelper
                get() = TODO("Not yet implemented")
            override val juggConfigurationRunner: IJuggConfigurationRunner
                get() = TODO("Not yet implemented")
        }
    }

    protected fun newBaseInvoker(): IMcpInvoker {
        return McpBaseInvoker()
    }

    protected fun newToolInvoker(currentProjectDir: String = "/tmp/projectA"): IMcpInvoker {
        val definitionByName = McpToolActionRegistry.defaultActions().associateBy { it.toolName }
        fun def(name: String): McpToolDefinition = definitionByName.getValue(name).definition

        val fakeActions = listOf(
            fakeAction("list_projects", def("list_projects")) { _ ->
                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "list_projects executed successfully.",
                    data = mapOf(
                        "projects" to listOf(
                            McpProjectInfo(projectDir = "/tmp/projectA", initialized = true)
                        )
                    ),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            },
            fakeAction("restart_app", def("restart_app")) { arguments ->
                when (arguments["serial"] as? String) {
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
                        message = "restart_app executed successfully. Device selected by serial: ${arguments["serial"]}.",
                        data = mapOf("device" to McpDeviceInfo(serial = arguments["serial"] as String, name = "Specified", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                }
            },
            fakeAction("emulator_list", def("emulator_list")) { _ ->
                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "emulator_list executed successfully.",
                    data = mapOf(
                        "avds" to listOf(
                            mapOf("name" to "Pixel_8_API_35", "isRunning" to false),
                            mapOf("name" to "Pixel_6_API_34", "isRunning" to true, "serial" to "emulator-5554"),
                        )
                    ),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            },
            fakeAction("start_emulator", def("start_emulator")) { arguments ->
                val avdName = arguments["avdName"] as? String
                if (avdName == "missing") {
                    McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "start_emulator failed. Reason: AVD 'missing' not found.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                    )
                } else {
                    McpToolResult(
                        status = McpToolStatus.OK,
                        message = "start_emulator executed successfully.",
                        data = mapOf(
                            "avdName" to (avdName ?: "Pixel_8_API_35"),
                            "emulatorSerial" to "emulator-5554",
                            "started" to true,
                            "waitedSec" to ((arguments["waitForDeviceSec"] as? Number)?.toInt() ?: 0),
                        ),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                }
            },
            fakeAction("compile_only", def("compile_only")) { _ ->
                McpToolResult(McpToolStatus.OK, "compile_only executed successfully.", mapOf("isCompileSuccess" to true), emptyList(), null)
            },
            fakeAction("compile_and_deploy", def("compile_and_deploy")) { _ ->
                McpToolResult(McpToolStatus.OK, "compile_and_deploy executed successfully.", mapOf("isDeploySuccess" to true), emptyList(), null)
            },
            fakeAction("clean_reinstall_apk", def("clean_reinstall_apk")) { _ ->
                McpToolResult(McpToolStatus.OK, "clean_reinstall_apk executed successfully.", mapOf("cleanAndReinstall" to true), emptyList(), null)
            },
            fakeAction("force_gradle_compile", def("force_gradle_compile")) { _ ->
                McpToolResult(McpToolStatus.OK, "force_gradle_compile executed successfully.", mapOf("triggered" to true), emptyList(), null)
            },
            fakeAction("device_list", def("device_list")) { _ ->
                McpToolResult(McpToolStatus.OK, "device_list executed successfully.", mapOf("devices" to emptyList<Map<String, Any?>>()), emptyList(), null)
            },
            fakeAction("screenshot", def("screenshot")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "screenshot executed successfully.",
                    mapOf("serial" to arguments["serial"]),
                    listOf(McpArtifact(type = "image", path = "/tmp/a.png")),
                    null,
                )
            },
            fakeAction("record", def("record")) { arguments ->
                val duration = (arguments["durationSec"] as? Number)?.toInt() ?: 10
                McpToolResult(
                    McpToolStatus.OK,
                    "record executed successfully.",
                    mapOf("serial" to arguments["serial"], "durationSec" to duration),
                    listOf(McpArtifact(type = "video", path = "/tmp/a.mp4")),
                    null,
                )
            },
            fakeAction("layout_dump", def("layout_dump")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "layout_dump executed successfully.",
                    mapOf("serial" to arguments["serial"]),
                    listOf(McpArtifact(type = "xml", path = "/tmp/a.xml")),
                    null,
                )
            },
            fakeAction("start_app", def("start_app")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "start_app executed successfully.",
                    mapOf(
                        "serial" to arguments["serial"],
                        "packageName" to arguments["packageName"],
                        "activity" to ".MainActivity",
                    ),
                    emptyList(),
                    null,
                )
            },
            fakeAction("start_activity", def("start_activity")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "start_activity executed successfully.",
                    mapOf(
                        "serial" to arguments["serial"],
                        "packageName" to arguments["packageName"],
                        "activity" to arguments["activity"],
                        "action" to arguments["action"],
                        "categories" to arguments["categories"],
                        "data" to arguments["data"],
                        "mimeType" to arguments["mimeType"],
                        "flags" to arguments["flags"],
                        "extras" to arguments["extras"],
                        "user" to arguments["user"],
                    ),
                    emptyList(),
                    null,
                )
            },
            fakeAction("tap", def("tap")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "tap executed successfully.",
                    mapOf("serial" to arguments["serial"], "x" to arguments["x"], "y" to arguments["y"]),
                    emptyList(),
                    null,
                )
            },
        )

        return McpToolInvoker(
            currentProjectDir = currentProjectDir,
            runtime = McpRuntimeHolder.runtime,
            toolRegistry = McpToolRegistry(McpToolActionRegistry(fakeActions)),
        )
    }

    protected fun initialize(invoker: IMcpInvoker) {
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

    private fun fakeAction(
        name: String,
        definition: McpToolDefinition,
        executeFn: (Map<String, Any?>) -> McpToolResult,
    ): McpToolAction {
        return object : McpToolAction {
            override val toolName: String = name
            override val definition: McpToolDefinition = definition

            override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
                return executeFn(arguments)
            }
        }
    }
}
