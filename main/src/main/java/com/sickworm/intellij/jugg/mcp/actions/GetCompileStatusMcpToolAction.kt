package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

class GetCompileStatusMcpToolAction : McpToolAction {
    override val toolName: String = "get_compile_status"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Query compile job status by jobId. Use when: async compile tools return isFinal=false with a jobId.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "jobId" to McpJsonSchemaProperty(type = "string", description = "Compile job ID returned by async compile tools."),
            ),
            required = listOf("projectDir", "jobId"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "jobId" to McpJsonSchemaProperty(type = "string"),
                        "status" to McpJsonSchemaProperty(type = "string", `enum` = listOf("running", "success", "failed", "canceled", "unknown")),
                        "executionType" to McpJsonSchemaProperty(type = "string", `enum` = listOf("local", "remote")),
                        "finishedAt" to McpJsonSchemaProperty(type = "string"),
                        "message" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = listOf("jobId", "status", "executionType", "message"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val jobId = arguments["jobId"] as? String
        if (jobId.isNullOrBlank()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "get_compile_status failed. Reason: jobId is required.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
        val state = CompileJobManager.getStatus(jobId)
        val data = mutableMapOf<String, Any?>(
            "jobId" to state.jobId,
            "status" to state.status,
            "executionType" to state.executionType,
            "message" to state.message,
        )
        state.finishedAt?.let { data["finishedAt"] = it }

        // Return ERROR when jobId does not exist.
        if (state.status == "unknown") {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "get_compile_status failed. Reason: Compile job not found for jobId=$jobId.",
                data = data,
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "get_compile_status executed successfully.",
            data = data,
            artifacts = emptyList(),
            errorCode = null,
        )
    }
}
