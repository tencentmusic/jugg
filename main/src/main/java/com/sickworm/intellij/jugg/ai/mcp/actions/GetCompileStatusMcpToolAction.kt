package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus

class GetCompileStatusMcpToolAction : McpToolAction {
    companion object {
        private const val WAIT_TIMEOUT_MIN_MS = 0
        private const val WAIT_TIMEOUT_MAX_MS = 10_000
    }

    override val toolName: String = McpToolActionRegistry.ToolNames.GET_COMPILE_STATUS

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Query compile job status by jobId. Use when: async compile tools return isFinal=false with a jobId.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "jobId" to McpJsonSchemaProperty(type = "string", description = "Compile job ID returned by async compile tools."),
                "waitTimeoutMs" to McpJsonSchemaProperty(
                    type = "integer",
                    description = "Optional blocking wait timeout in milliseconds before returning running status. Range [0, 10000]. Default: 0.",
                    minimum = WAIT_TIMEOUT_MIN_MS.toDouble(),
                    maximum = WAIT_TIMEOUT_MAX_MS.toDouble(),
                ),
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
                        "pollIntervalSuggestedMs" to McpJsonSchemaProperty(type = "number"),
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
                message = "get-compile-status failed. Reason: jobId is required.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INVALID_PARAMS,
            )
        }
        val waitTimeoutMs = parseWaitTimeoutMs(arguments["waitTimeoutMs"]) ?: return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "get-compile-status failed. Reason: waitTimeoutMs must be an integer in [0, 10000].",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
        val state = CompileJobManager.getStatus(jobId, waitTimeoutMs.toLong())
        val data = mutableMapOf<String, Any?>(
            "jobId" to state.jobId,
            "status" to state.status,
            "executionType" to state.executionType,
            "message" to state.message,
        )
        if (state.status == "running") {
            data.putAll(CompileJobManager.buildPollSuggestionData())
        }
        state.finishedAt?.let { data["finishedAt"] = it }

        // Return ERROR when jobId does not exist.
        if (state.status == "unknown") {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "get-compile-status failed. Reason: Compile job not found for jobId=$jobId.",
                data = data,
                artifacts = emptyList(),
                errorCode = McpErrorCode.INVALID_PARAMS,
            )
        }

        // Attach detail when compile job finished with failure.
        val isFailed = state.status == "failed" || state.status == "canceled"
        val artifacts = mutableListOf<com.sickworm.intellij.jugg.ai.mcp.McpArtifact>()
        if (isFailed && state.detail.isNotBlank()) {
            CompileAndDeployMcpToolAction.attachDetailToData(
                toolName = toolName,
                detail = state.detail,
                data = data,
                artifacts = artifacts,
            )
        }

        return McpToolResult(
            status = if (isFailed) McpToolStatus.ERROR else McpToolStatus.OK,
            message = if (isFailed) "get-compile-status: compile job finished with status=${state.status}."
                      else "get-compile-status executed successfully.",
            data = data,
            artifacts = artifacts,
            errorCode = if (isFailed) McpErrorCode.INTERNAL_ERROR else null,
        )
    }

    private fun parseWaitTimeoutMs(raw: Any?): Int? {
        if (raw == null) {
            return WAIT_TIMEOUT_MIN_MS
        }
        val number = raw as? Number ?: return null
        val value = number.toInt()
        if (value.toDouble() != number.toDouble()) {
            return null
        }
        if (value < WAIT_TIMEOUT_MIN_MS || value > WAIT_TIMEOUT_MAX_MS) {
            return null
        }
        return value
    }
}
