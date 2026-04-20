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
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
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
            fakeAction("list-projects", def("list-projects")) { _ ->
                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "list-projects executed successfully.",
                    data = mapOf(
                        "projects" to listOf(
                            McpProjectInfo(projectDir = "/tmp/projectA", initialized = true)
                        )
                    ),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            },
            fakeAction("version", def("version")) { _ ->
                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "version executed successfully.",
                    data = mapOf("pluginVersion" to "1.0.0"),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            },
            fakeAction("restart", def("restart")) { arguments ->
                val projectDir = arguments["projectDir"] as? String
                if (projectDir == "/tmp/projectNoDevice") {
                    McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "restart failed. Reason: No connected device is available.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.NO_DEVICE,
                    )
                } else {
                    McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart executed successfully.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                }
            },
            fakeAction("compile", def("compile")) { _ ->
                McpToolResult(McpToolStatus.OK, "compile executed successfully.", mapOf("isCompileSuccess" to true), emptyList(), null)
            },
            fakeAction("deploy", def("deploy")) { _ ->
                McpToolResult(McpToolStatus.OK, "deploy executed successfully.", mapOf("isDeploySuccess" to true), emptyList(), null)
            },
            fakeAction("clean-reinstall", def("clean-reinstall")) { _ ->
                McpToolResult(McpToolStatus.OK, "clean-reinstall executed successfully.", mapOf("cleanAndReinstall" to true), emptyList(), null)
            },
            fakeAction("gradle-build", def("gradle-build")) { _ ->
                McpToolResult(McpToolStatus.OK, "gradle-build executed successfully.", mapOf("triggered" to true), emptyList(), null)
            },
            fakeAction("get-compile-status", def("get-compile-status")) { arguments ->
                val jobId = arguments["jobId"] as? String ?: "job-unknown"
                McpToolResult(
                    McpToolStatus.OK,
                    "get-compile-status executed successfully.",
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
            fakeAction("ssh-info", def("ssh-info")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "ssh-info executed successfully.",
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
            fakeAction("devices", def("devices")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "devices executed successfully.",
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
            fakeAction("layout-dump", def("layout-dump")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "layout-dump executed successfully.",
                    mapOf("file" to "/tmp/a.xml"),
                    listOf(McpArtifact(type = "xml", path = "/tmp/a.xml")),
                    null,
                )
            },
            fakeAction("activity-stack", def("activity-stack")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "activity-stack executed successfully.",
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
            fakeAction("tap", def("tap")) { arguments ->
                McpToolResult(
                    McpToolStatus.OK,
                    "tap executed successfully.",
                    mapOf("x" to arguments["x"], "y" to arguments["y"]),
                    emptyList(),
                    null,
                )
            },
            fakeAction("status", def("status")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "status executed successfully.",
                    mapOf("isReady" to true),
                    emptyList(),
                    null,
                )
            },
            fakeAction("view-locate", def("view-locate")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "view-locate executed successfully.",
                    emptyMap<String, Any>(),
                    emptyList(),
                    null,
                )
            },
            fakeAction("view-inspect", def("view-inspect")) { _ ->
                McpToolResult(
                    McpToolStatus.OK,
                    "view-inspect executed successfully.",
                    emptyMap<String, Any>(),
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
