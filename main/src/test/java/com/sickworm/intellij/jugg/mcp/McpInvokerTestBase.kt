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
                val projectDir = arguments["projectDir"] as? String
                if (projectDir == "/tmp/projectNoDevice") {
                    McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "restart_app failed. Reason: No connected device is available.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.MCP_NO_DEVICE,
                    )
                } else {
                    McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully.",
                        data = emptyMap<String, Any>(),
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
            fakeAction("get_compile_status", def("get_compile_status")) { arguments ->
                val jobId = arguments["jobId"] as? String ?: "job-unknown"
                McpToolResult(
                    McpToolStatus.OK,
                    "get_compile_status executed successfully.",
                    mapOf(
                        "jobId" to jobId,
                        "status" to "success",
                        "executionType" to "local",
                        "message" to "done",
                    ),
                    emptyList(),
                    null,
                )
            },
            fakeAction("request_remote_ssh_info", def("request_remote_ssh_info")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "request_remote_ssh_info executed successfully.",
                    mapOf(
                        "user" to "root",
                        "ip" to "127.0.0.1",
                        "port" to 22,
                        "password" to "",
                        "sshLoginCommand" to "ssh root@127.0.0.1 -p 22",
                    ),
                    emptyList(),
                    null,
                )
            },
            fakeAction("device_list", def("device_list")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "device_list executed successfully.",
                    mapOf(
                        "devices" to listOf(
                            mapOf(
                                "serial" to "emulator-5554",
                                "name" to "Pixel_6_API_34",
                                "isOnline" to true,
                                "api" to 34,
                                "isSelected" to true,
                            )
                        )
                    ),
                    emptyList(),
                    null,
                )
            },
            fakeAction("screenshot", def("screenshot")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "screenshot executed successfully.",
                    mapOf("file" to "/tmp/a.png"),
                    listOf(McpArtifact(type = "image", path = "/tmp/a.png")),
                    null,
                )
            },
            fakeAction("start_record", def("start_record")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "start_record executed successfully.",
                    mapOf(
                        "sessionId" to "rec_123",
                        "serial" to "emulator-5554",
                        "file" to "/tmp/a.mp4",
                        "startedAtMs" to 123456789L,
                    ),
                    emptyList(),
                    null,
                )
            },
            fakeAction("stop_record", def("stop_record")) { arguments ->
                val sessionId = arguments["sessionId"] as? String ?: "rec_123"
                McpToolResult(
                    McpToolStatus.OK,
                    "stop_record executed successfully.",
                    mapOf(
                        "sessionId" to sessionId,
                        "serial" to "emulator-5554",
                        "file" to "/tmp/a.mp4",
                    ),
                    listOf(McpArtifact(type = "video", path = "/tmp/a.mp4")),
                    null,
                )
            },
            fakeAction("layout_dump", def("layout_dump")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "layout_dump executed successfully.",
                    mapOf("file" to "/tmp/a.xml"),
                    listOf(McpArtifact(type = "xml", path = "/tmp/a.xml")),
                    null,
                )
            },
            fakeAction("activity_stack", def("activity_stack")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "activity_stack executed successfully.",
                    mapOf(
                        "topActivity" to "com.example.app/.MainActivity",
                        "activities" to listOf("com.example.app/.MainActivity", "com.example.app/.DetailActivity"),
                        "dumpFile" to "/tmp/activity_stack.txt",
                        "sourceCommand" to "dumpsys activity activities",
                    ),
                    listOf(McpArtifact(type = "text", path = "/tmp/activity_stack.txt")),
                    null,
                )
            },
            fakeAction("crash_report", def("crash_report")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "crash_report executed successfully.",
                    mapOf(
                        "isProcessAlive" to false,
                        "hasCrash" to true,
                        "crashLogs" to listOf("FATAL EXCEPTION: main", "java.lang.IllegalStateException: mock"),
                        "relatedActivity" to "com.example.app/.MainActivity",
                        "allErrorLogPath" to "/tmp/crash_report.log",
                    ),
                    listOf(McpArtifact(type = "log", path = "/tmp/crash_report.log")),
                    null,
                )
            },
            fakeAction("tap", def("tap")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "tap executed successfully.",
                    mapOf("x" to arguments["x"], "y" to arguments["y"]),
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
