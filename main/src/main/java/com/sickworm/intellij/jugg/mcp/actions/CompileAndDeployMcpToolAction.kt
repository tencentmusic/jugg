package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import java.nio.file.Files

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
        private const val DETAIL_PREVIEW_MAX_CHARS = 1024

        fun deployAction(runtime: IMcpRuntime, toolName: String, isSkipDeploy: Boolean = false): McpToolResult {
            val runResponse = runtime.juggConfigurationRunner.runFirstConfiguration(isRpcMode = true, isSkipDeploy = isSkipDeploy)
            if (!runResponse.isSuccess) {
                return buildRunFailureResult(toolName, runResponse.errorMessage, runResponse.detail)
            }
            return buildRunToolResult(
                toolName = toolName,
                successMessage = "$toolName executed successfully.",
                runResultObject = JsonParser.parseString(Gson().toJson(runResponse.runResult)) as? JsonObject,
                detail = runResponse.detail,
                extraData = emptyMap(),
            )
        }

        private data class DetailResult(
            val detailPreview: String? = null,
            val detailLength: Int = 0,
            val isTruncated: Boolean = false,
            val artifacts: List<McpArtifact> = emptyList(),
        ) {
            val hasDetail: Boolean
                get() = detailLength > 0
        }

        private fun resolveDetailResult(toolName: String, detail: String): DetailResult {
            if (detail.isBlank()) {
                return DetailResult()
            }

            val detailLength = detail.length
            if (detailLength <= DETAIL_PREVIEW_MAX_CHARS) {
                return DetailResult(
                    detailPreview = detail,
                    detailLength = detailLength,
                    isTruncated = false,
                    artifacts = emptyList(),
                )
            }

            val preview = buildString {
                append("...[truncated ")
                append(detailLength - DETAIL_PREVIEW_MAX_CHARS)
                append(" chars from beginning; showing tail]")
                append("\n")
                append(detail.takeLast(DETAIL_PREVIEW_MAX_CHARS))
            }

            val artifacts = listOfNotNull(writeFullLogArtifact(toolName, detail))
            return DetailResult(
                detailPreview = preview,
                detailLength = detailLength,
                isTruncated = true,
                artifacts = artifacts,
            )
        }

        private fun writeFullLogArtifact(toolName: String, detail: String): McpArtifact? {
            return try {
                val safeToolName = toolName.replace("[^A-Za-z0-9._-]".toRegex(), "_")
                val filePath = Files.createTempFile("jugg_mcp_${safeToolName}_", ".log")
                filePath.toFile().deleteOnExit()
                Files.writeString(filePath, detail)
                McpArtifact(type = "log", path = filePath.toAbsolutePath().toString())
            } catch (_: Exception) {
                null
            }
        }

        private fun attachDetailData(data: MutableMap<String, Any>, detailResult: DetailResult) {
            val preview = detailResult.detailPreview
            if (preview.isNullOrBlank()) {
                return
            }
            data["detail"] = preview
            data["detailLength"] = detailResult.detailLength
            if (detailResult.isTruncated) {
                data["detailTruncated"] = true
            }
        }

        private fun buildRunFailureResult(toolName: String, runErrorMessage: String?, detail: String): McpToolResult {
            val detailResult = resolveDetailResult(toolName, detail)
            val reason = runErrorMessage ?: "unknown run error"
            val data = mutableMapOf<String, Any>()
            attachDetailData(data, detailResult)
            val message = if (detailResult.hasDetail) {
                "$toolName failed. Reason: $reason. See data.detail and artifacts for logs."
            } else {
                "$toolName failed. Reason: $reason"
            }
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = message,
                data = data,
                artifacts = detailResult.artifacts,
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        private fun buildRunToolResult(
            toolName: String,
            successMessage: String,
            runResultObject: JsonObject?,
            detail: String,
            extraData: Map<String, Any>,
        ): McpToolResult {
            if (runResultObject == null) {
                val detailResult = resolveDetailResult(toolName, detail)
                val data = mutableMapOf<String, Any>()
                attachDetailData(data, detailResult)
                val message = if (detailResult.hasDetail) {
                    "$toolName failed. Reason: invalid run result payload. See data.detail and artifacts for logs."
                } else {
                    "$toolName failed. Reason: invalid run result payload."
                }
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = message,
                    data = data,
                    artifacts = detailResult.artifacts,
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            }

            val data = mutableMapOf<String, Any>(
                "runResult" to runResultObject,
            )
            data.putAll(extraData)

            return McpToolResult(
                status = McpToolStatus.OK,
                message = successMessage,
                data = data,
                artifacts = emptyList(),
                errorCode = null,
            )
        }
    }
}
