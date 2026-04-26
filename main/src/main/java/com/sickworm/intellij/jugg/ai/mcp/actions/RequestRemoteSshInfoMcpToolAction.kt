package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus

class RequestRemoteSshInfoMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.SSH_INFO

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Request remote SSH login info for troubleshooting.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "reason" to McpJsonSchemaProperty(type = "string", description = "Why remote SSH info is needed."),
                "requestedBy" to McpJsonSchemaProperty(type = "string", description = "Requester identity for confirmation display. Default: mcp_agent."),
            ),
            required = listOf("projectDir", "reason"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "user" to McpJsonSchemaProperty(type = "string"),
                        "ip" to McpJsonSchemaProperty(type = "string"),
                        "port" to McpJsonSchemaProperty(type = "number"),
                        "password" to McpJsonSchemaProperty(type = "string"),
                        "sshLoginCommand" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = emptyList(),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val reason = (arguments["reason"] as? String)?.trim().orEmpty()
        if (reason.isBlank()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "request_remote_ssh_info failed. Reason: reason is required.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INVALID_PARAMS,
            )
        }
        val requestedBy = (arguments["requestedBy"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: "mcp_agent"
        val info = runtime.forceGradleCompileHelper.requestRemoteSshInfo(
            requestedBy = requestedBy,
            reason = reason,
        )
        if (!info.approved) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = info.message,
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INTERNAL_ERROR,
            )
        }
        return McpToolResult(
            status = McpToolStatus.OK,
            message = info.message,
            data = mapOf(
                "user" to info.user,
                "ip" to info.ip,
                "port" to info.port,
                "password" to info.password,
                "sshLoginCommand" to info.sshLoginCommand,
            ),
            artifacts = emptyList(),
            errorCode = null,
        )
    }
}
