package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import java.nio.file.Files

/**
 * CompileAndDeployMcpToolAction implements MCP tool `deploy` and converts request arguments into tool execution and MCP result payloads.
 */
class CompileAndDeployMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.DEPLOY

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Compile modified sources and deploy updated artifacts to device.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "alwaysRestartApp" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true (default), always restart the app after deployment (HOT_FIX behavior). " +
                        "When false, only restart when class structure changes require it (HOT RELOAD is allowed).",
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val isAlwaysRestartApp = arguments["alwaysRestartApp"] as? Boolean ?: true
        return deployAction(runtime, "deploy", isAlwaysRestartApp = isAlwaysRestartApp)
    }

    companion object {
        private const val DETAIL_PREVIEW_MAX_CHARS = 1024

        fun deployAction(runtime: IMcpRuntime, toolName: String, isSkipDeploy: Boolean = false, isAlwaysRestartApp: Boolean = true): McpToolResult {
            val trigger = CompileJobManager.triggerJuggCompile(
                runtime = runtime,
                isSkipDeploy = isSkipDeploy,
                isAlwaysRestartApp = isAlwaysRestartApp,
            )
            val jobMetaData = buildJobMetaData(trigger)
            if (!trigger.isFinal) {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = trigger.message,
                    data = jobMetaData,
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            val runResponse = trigger.finalResult?.runInvocationResult
            if (runResponse == null) {
                return buildRunFailureResult(
                    toolName = toolName,
                    runErrorMessage = "missing final run result",
                    detail = "",
                    extraData = jobMetaData,
                )
            }
            if (!runResponse.isSuccess) {
                return buildRunFailureResult(
                    toolName = toolName,
                    runErrorMessage = runResponse.errorMessage,
                    detail = runResponse.detail,
                    extraData = jobMetaData,
                )
            }
            return buildRunToolResult(
                toolName = toolName,
                successMessage = if (trigger.status == "success") {
                    "$toolName executed successfully."
                } else {
                    "$toolName finished with status=${trigger.status}."
                },
                runResultObject = JsonParser.parseString(Gson().toJson(runResponse.runResult)) as? JsonObject,
                detail = runResponse.detail,
                extraData = jobMetaData,
            )
        }

        private fun buildJobMetaData(trigger: CompileJobTriggerResult): Map<String, Any> {
            return mapOf(
                "accepted" to trigger.accepted,
                "jobId" to trigger.jobId,
                "executionType" to trigger.executionType,
                "logPath" to trigger.logPath,
                "isFinal" to trigger.isFinal,
                "status" to trigger.status,
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

        private fun buildRunFailureResult(
            toolName: String,
            runErrorMessage: String?,
            detail: String,
            extraData: Map<String, Any> = emptyMap(),
        ): McpToolResult {
            val detailResult = resolveDetailResult(toolName, detail)
            val reason = runErrorMessage ?: "unknown run error"
            val data = mutableMapOf<String, Any>()
            attachDetailData(data, detailResult)
            data.putAll(extraData)
            val message = "$toolName failed. Reason: $reason."
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = message,
                data = data,
                artifacts = detailResult.artifacts,
                errorCode = McpErrorCode.INTERNAL_ERROR,
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
                data.putAll(extraData)
                val message = "$toolName failed. Reason: invalid run result payload."
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = message,
                    data = data,
                    artifacts = detailResult.artifacts,
                    errorCode = McpErrorCode.INTERNAL_ERROR,
                )
            }

            val jobStatus = extraData["status"] as? String
            val isRealSuccess = (jobStatus == "success")

            // Compilation failed: returns ERROR with details
            if (!isRealSuccess) {
                val detailResult = resolveDetailResult(toolName, detail)
                val data = mutableMapOf<String, Any>("runResult" to runResultObject)
                attachDetailData(data, detailResult)
                data.putAll(extraData)
                val message = "$toolName finished with status=$jobStatus."
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = message,
                    data = data,
                    artifacts = detailResult.artifacts,
                    errorCode = McpErrorCode.INTERNAL_ERROR,
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
