package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

class CompileAndDeployMcpToolAction : McpToolAction {
    override val toolName: String = "compile_and_deploy"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Compile modified source files then deploy changed artifacts to device with Jugg. This is the default path for normal iteration. Use when code changes must take effect on device. Avoid when incremental state is broken and full reinstall is needed. Side effects: builds and updates app artifacts on device.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return deployAction(runtime, "compile_and_deploy")
    }

    companion object {

        fun deployAction(runtime: IMcpRuntime, toolName: String, isSkipDeploy: Boolean = false): McpToolResult {
            val runResponse = runtime.juggConfigurationRunner.runFirstConfiguration(isRpcMode = true, isSkipDeploy = isSkipDeploy)
            if (!runResponse.isSuccess) {
                return buildRunFailureResult(toolName, runResponse.errorMessage, runResponse.detail)
            }
            return buildRunToolResult(
                toolName = toolName,
                success = true,
                successMessage = "$toolName executed successfully.",
                defaultFailureMessage = "$toolName failed. Reason: deploy stage not successful.",
                runResultObject = JsonParser.parseString(Gson().toJson(runResponse.runResult)) as? JsonObject,
                detail = runResponse.detail,
                extraData = emptyMap(),
            )
        }

        private fun buildRunFailureResult(toolName: String, runErrorMessage: String?, detail: String): McpToolResult {
            val reason = buildString {
                append(runErrorMessage ?: "unknown run error")
                if (detail.isNotBlank()) {
                    append("\n")
                    append(detail)
                }
            }
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: $reason",
                data = mapOf("detail" to detail),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        private fun buildRunToolResult(
            toolName: String,
            success: Boolean,
            successMessage: String,
            defaultFailureMessage: String,
            runResultObject: JsonObject?,
            detail: String,
            extraData: Map<String, Any>,
        ): McpToolResult {
            if (runResultObject == null) {
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "$toolName failed. Reason: invalid run result payload.",
                    data = if (detail.isBlank()) emptyMap() else mapOf("detail" to detail),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            }

            val failureMessage = if (detail.isBlank()) defaultFailureMessage else "$defaultFailureMessage\n$detail"

            val data = mutableMapOf<String, Any>(
                "runResult" to runResultObject,
            )
            if (detail.isNotBlank()) {
                data["detail"] = detail
            }
            data.putAll(extraData)

            return McpToolResult(
                status = if (success) McpToolStatus.OK else McpToolStatus.ERROR,
                message = if (success) successMessage else failureMessage,
                data = data,
                artifacts = emptyList(),
                errorCode = if (success) null else McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
    }
}
