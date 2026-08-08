package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.ai.mcp.McpBaseInvoker
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpc
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.ai.mcp.McpResultMapper
import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry
import com.sickworm.intellij.jugg.ai.mcp.actions.InitProjectMcpToolAction
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import java.io.File

/** Registers standalone project runtimes and routes MCP calls to their project owner. */
class StandaloneProjectRegistry(
    private val runtimeInfo: RuntimeInfo,
    private val activity: StandaloneDaemonActivity = StandaloneDaemonActivity(),
) : AutoCloseable {
    private val runtimes = linkedMapOf<String, StandaloneProjectRuntime>()
    private val actionRegistry = McpToolActionRegistry(McpToolActionRegistry.defaultActions() + InitProjectMcpToolAction())
    private val toolRegistry = McpToolRegistry(actionRegistry, standaloneCapabilities)
    private val assembler = StandaloneJuggRuntimeAssembler(runtimeInfo, activity, toolRegistry)
    private val baseInvoker = McpBaseInvoker(toolRegistry)

    init {
        PlatformApi.impl = StandalonePlatformApi(this, runtimeInfo)
    }

    @Synchronized
    fun initialize(projectDir: File): StandaloneProjectRuntime {
        val canonicalProjectDir = projectDir.canonicalFile
        require(canonicalProjectDir.isDirectory) { "Project directory does not exist: $canonicalProjectDir" }
        val key = ProjectDirNormalizer.normalizeProjectDir(canonicalProjectDir.path)
        return runtimes.getOrPut(key) { assembler.create(canonicalProjectDir) }
    }

    @Synchronized
    fun getInitializedProjectDirs(): List<File> {
        return runtimes.values.map { File(it.projectDir) }.sortedBy(File::getPath)
    }

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        if (request.method != McpJsonRpc.Method.ToolsCall) {
            return baseInvoker.invokeMcp(request)
        }
        val toolName = (request.params as? Map<*, *>)?.get("name") as? String
        if (toolName in McpToolActionRegistry.noProjectDirTools) {
            return baseInvoker.invokeMcp(request)
        }
        val projectDir = extractProjectDir(request)
            ?: return McpResultMapper().toolError(
                request.id,
                McpErrorCode.INVALID_PARAMS,
                "invoke_mcp failed. Reason: projectDir is required.",
            )
        val normalizedProjectDir = normalizeExistingProjectDir(projectDir)
        val runtime = synchronized(this) { runtimes[normalizedProjectDir] }
            ?: return McpResultMapper().toolError(
                request.id,
                McpErrorCode.PROJECT_NOT_INITIALIZED,
                "invoke_mcp failed. Reason: project is not initialized.",
            )
        return runtime.invokeMcp(withProjectDir(request, normalizedProjectDir))
    }

    @Synchronized
    override fun close() {
        runtimes.values.forEach(StandaloneProjectRuntime::close)
        runtimes.clear()
    }

    private fun extractProjectDir(request: McpJsonRpcRequest): String? {
        val params = request.params as? Map<*, *> ?: return null
        val arguments = params["arguments"] as? Map<*, *> ?: return null
        return arguments["projectDir"] as? String
    }

    private fun normalizeExistingProjectDir(projectDir: String): String {
        val normalizedProjectDir = ProjectDirNormalizer.normalizeProjectDir(projectDir)
        val canonicalPath = runCatching { File(normalizedProjectDir).canonicalPath }.getOrDefault(normalizedProjectDir)
        return ProjectDirNormalizer.normalizeProjectDir(canonicalPath)
    }

    private fun withProjectDir(request: McpJsonRpcRequest, projectDir: String): McpJsonRpcRequest {
        val params = request.params as? Map<*, *> ?: return request
        @Suppress("UNCHECKED_CAST")
        val arguments = (params["arguments"] as? Map<String, Any?>)?.toMutableMap() ?: return request
        arguments["projectDir"] = projectDir
        return request.copy(params = params.toMutableMap().apply { this["arguments"] = arguments })
    }

    private companion object {
        val standaloneCapabilities = listOf(
            McpToolActionRegistry.ToolNames.VERSION,
            McpToolActionRegistry.ToolNames.LIST_PROJECTS,
            McpToolActionRegistry.ToolNames.INIT,
            McpToolActionRegistry.ToolNames.COMPILE,
            McpToolActionRegistry.ToolNames.DEPLOY,
            McpToolActionRegistry.ToolNames.GRADLE_BUILD,
            McpToolActionRegistry.ToolNames.GET_COMPILE_STATUS,
            McpToolActionRegistry.ToolNames.GET_STATUS,
        )
    }
}
