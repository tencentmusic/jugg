package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.diagnostic.Logger

class McpInvoker(
    currentProjectDir: String,
    private val toolRegistry: McpToolRegistry = McpToolRegistry(),
    private val resultMapper: McpResultMapper = McpResultMapper(),
) {
    private val logger = Logger.getInstance("McpInvoker")
    private val requestValidator = McpRequestValidator(currentProjectDir, toolRegistry)

    @Synchronized
    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        logger.debug("[MCP][INVOKER][IN ] method=${request.method}, id=${request.id}")
        if (request.jsonrpc != McpJsonRpc.Version) {
            logger.debug("[MCP][INVOKER] invalid jsonrpc version: ${request.jsonrpc}")
            return resultMapper.jsonRpcError(
                request.id,
                McpErrorCode.MCP_INVALID_JSON_RPC,
                "Invalid jsonrpc version",
                McpJsonRpc.ErrorCode.InvalidRequest,
            )
        }

        when (request.method) {
            McpJsonRpc.Method.Initialize -> {
                logger.debug("[MCP][INVOKER] initialize success")
                return resultMapper.initialize(request.id)
            }
            McpJsonRpc.Method.NotificationsInitialized -> {
                logger.debug("[MCP][INVOKER] notifications/initialized")
                return resultMapper.notificationAck()
            }
            McpJsonRpc.Method.Ping -> {
                logger.debug("[MCP][INVOKER] ping")
                return resultMapper.ping(request.id)
            }
            McpJsonRpc.Method.PromptsList -> {
                logger.debug("[MCP][INVOKER] prompts/list")
                return resultMapper.promptsList(request.id)
            }
            McpJsonRpc.Method.ResourcesList -> {
                logger.debug("[MCP][INVOKER] resources/list")
                return resultMapper.resourcesList(request.id)
            }
            McpJsonRpc.Method.ResourcesTemplatesList -> {
                logger.debug("[MCP][INVOKER] resources/templates/list")
                return resultMapper.resourcesTemplatesList(request.id)
            }
        }

        return when (val validated = requestValidator.validate(request)) {
            is McpValidationResult.ToolsList -> resultMapper.toolsList(request.id, toolRegistry.listTools())
            is McpValidationResult.ToolsCall -> handleToolsCall(request.id, validated)
            is McpValidationResult.Invalid -> {
                logger.debug("[MCP][INVOKER] request invalid: ${validated.message}, code=${validated.errorCode}")
                if (validated.isJsonRpcError) {
                    resultMapper.jsonRpcError(request.id, validated.errorCode, validated.message, validated.jsonRpcCode)
                } else {
                    resultMapper.toolError(request.id, validated.errorCode, validated.message)
                }
            }
        }
    }

    private fun handleToolsCall(id: Any?, request: McpValidationResult.ToolsCall): McpJsonRpcResponse {
        logger.debug("[MCP][INVOKER] tools/call name=${request.toolName}, projectDir=${request.projectDir}")
        val runtime = McpRuntimeHolder.runtime
            ?: return resultMapper.toolError(
                id = id,
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                message = "MCP runtime is not initialized.",
            )

        val toolResult = when (request.toolName) {
            "restart_app" -> {
                logger.debug("[MCP][INVOKER] handling restart_app")
                runtime.restartApp(request.arguments["serial"] as? String)
            }
            "compile" -> {
                logger.debug("[MCP][INVOKER] handling compile")
                runtime.compile()
            }
            "deploy" -> {
                logger.debug("[MCP][INVOKER] handling deploy")
                runtime.deploy()
            }
            "clean_reinstall" -> {
                logger.debug("[MCP][INVOKER] handling clean_reinstall")
                runtime.cleanReinstall()
            }
            "device_list" -> {
                logger.debug("[MCP][INVOKER] handling device_list")
                runtime.deviceList()
            }
            "screenshot" -> {
                logger.debug("[MCP][INVOKER] handling screenshot")
                runtime.screenshot(request.arguments["serial"] as? String)
            }
            "record" -> {
                logger.debug("[MCP][INVOKER] handling record")
                val durationSec = (request.arguments["durationSec"] as? Number)?.toInt()
                runtime.record(request.arguments["serial"] as? String, durationSec)
            }
            "layout_dump" -> {
                logger.debug("[MCP][INVOKER] handling layout_dump")
                runtime.layoutDump(request.arguments["serial"] as? String)
            }
            else -> McpToolResult(
                status = McpToolStatus.OK,
                message = "${request.toolName} executed successfully.",
                data = mapOf(
                    "tool" to request.toolName,
                    "projectDir" to request.projectDir,
                    "arguments" to request.arguments,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        }

        return if (toolResult.status == McpToolStatus.ERROR) {
            resultMapper.toolError(
                id = id,
                errorCode = toolResult.errorCode ?: McpErrorCode.MCP_INTERNAL_ERROR,
                message = toolResult.message,
                data = toolResult.data,
            )
        } else {
            resultMapper.toolSuccess(id = id, toolResult = toolResult)
        }
    }

    companion object {
        val globalMcpInvoker = McpInvoker("")
    }
}
