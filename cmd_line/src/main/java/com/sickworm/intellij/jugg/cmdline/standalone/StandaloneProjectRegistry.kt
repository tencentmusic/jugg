package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.ai.mcp.McpBaseInvoker
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpc
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.ai.mcp.McpRequestValidator
import com.sickworm.intellij.jugg.ai.mcp.McpResultMapper
import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry
import com.sickworm.intellij.jugg.ai.mcp.McpValidationResult
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry
import com.sickworm.intellij.jugg.ai.mcp.actions.InitProjectMcpToolAction
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Registers standalone project runtimes and routes MCP calls to their project owner. */
class StandaloneProjectRegistry(
    private val runtimeInfo: RuntimeInfo,
    private val activity: StandaloneDaemonActivity = StandaloneDaemonActivity(),
) : AutoCloseable {
    private val runtimes = ConcurrentHashMap<String, StandaloneProjectRuntime>()
    private val initializingRuntimes = ConcurrentHashMap<String, CompletableFuture<StandaloneProjectRuntime>>()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean()
    private val actionRegistry = McpToolActionRegistry(McpToolActionRegistry.defaultActions() + InitProjectMcpToolAction())
    private val toolRegistry = McpToolRegistry(actionRegistry, standaloneCapabilities)
    private val assembler = StandaloneJuggRuntimeAssembler(runtimeInfo, activity, toolRegistry)
    private val resultMapper = McpResultMapper()
    private val baseInvoker = McpBaseInvoker(toolRegistry, resultMapper)

    init {
        PlatformApi.impl = StandalonePlatformApi(this, runtimeInfo)
    }

    fun initialize(projectDir: File): StandaloneProjectRuntime {
        val canonicalProjectDir = projectDir.canonicalFile
        require(canonicalProjectDir.isDirectory) { "Project directory does not exist: $canonicalProjectDir" }
        val key = ProjectDirNormalizer.normalizeProjectDir(canonicalProjectDir.path)
        check(!closed.get()) { "Standalone project registry is closed" }
        runtimes[key]?.let { return it }

        val initialization = CompletableFuture<StandaloneProjectRuntime>()
        val activeInitialization = initializingRuntimes.putIfAbsent(key, initialization)
        if (activeInitialization != null) {
            return awaitInitialization(activeInitialization)
        }
        try {
            val created = assembler.create(canonicalProjectDir)
            val runtime = synchronized(lifecycleLock) {
                if (closed.get()) null else runtimes.putIfAbsent(key, created) ?: created
            }
            if (runtime == null) {
                val error = IllegalStateException("Standalone project registry is closed")
                runCatching(created::close).exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            if (runtime !== created) created.close()
            initialization.complete(runtime)
            return runtime
        } catch (error: Throwable) {
            initialization.completeExceptionally(error)
            throw error
        } finally {
            initializingRuntimes.remove(key, initialization)
        }
    }

    fun getInitializedProjectDirs(): List<File> {
        return runtimes.values.map { File(it.projectDir) }.sortedBy(File::getPath)
    }

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        if (request.jsonrpc != McpJsonRpc.Version) {
            return baseInvoker.invokeMcp(request)
        }
        if (request.method != McpJsonRpc.Method.ToolsCall) {
            return baseInvoker.invokeMcp(request)
        }
        val toolName = (request.params as? Map<*, *>)?.get("name") as? String
        if (toolName in McpToolActionRegistry.noProjectDirTools) {
            return baseInvoker.invokeMcp(request)
        }
        val projectDir = extractProjectDir(request)
            ?: return resultMapper.toolError(
                request.id,
                McpErrorCode.INVALID_PARAMS,
                "invoke_mcp failed. Reason: projectDir is required.",
            )
        val normalizedProjectDir = normalizeExistingProjectDir(projectDir)
        val normalizedRequest = withProjectDir(request, normalizedProjectDir)
        val validation = McpRequestValidator(normalizedProjectDir, toolRegistry).validate(normalizedRequest)
        if (validation is McpValidationResult.Invalid) {
            return invalidResponse(request, validation)
        }
        val runtime = try {
            runtimes[normalizedProjectDir] ?: initialize(File(normalizedProjectDir))
        } catch (error: Exception) {
            return resultMapper.toolError(
                request.id,
                McpErrorCode.PROJECT_NOT_INITIALIZED,
                "invoke_mcp failed. Reason: project initialization failed.",
                mapOf("detail" to (error.message ?: error.javaClass.simpleName)),
            )
        }
        return runtime.invokeMcp(normalizedRequest)
    }

    override fun close() {
        val initializedRuntimes = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            runtimes.values.toList().also { runtimes.clear() }
        }
        val errors = initializedRuntimes.mapNotNull { runtime ->
            runCatching(runtime::close).exceptionOrNull()
        }
        if (errors.isNotEmpty()) {
            throw errors.first().also { first -> errors.drop(1).forEach(first::addSuppressed) }
        }
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

    private fun invalidResponse(
        request: McpJsonRpcRequest,
        validation: McpValidationResult.Invalid,
    ): McpJsonRpcResponse {
        return if (validation.isJsonRpcError) {
            resultMapper.jsonRpcError(request.id, validation.errorCode, validation.message, validation.jsonRpcCode)
        } else {
            resultMapper.toolError(request.id, validation.errorCode, validation.message)
        }
    }

    private fun awaitInitialization(initialization: CompletableFuture<StandaloneProjectRuntime>): StandaloneProjectRuntime {
        return try {
            initialization.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
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
