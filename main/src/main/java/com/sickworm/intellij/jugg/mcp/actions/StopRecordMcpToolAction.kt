package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import java.io.File

/**
 * StopRecordMcpToolAction stops a running start_record session and fetches the generated video artifact.
 */
class StopRecordMcpToolAction : McpToolAction {
    override val toolName: String = "stop_record"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Stop a running recording session and fetch mp4 artifact. Use when: start_record has returned a sessionId and you want to finalize video evidence. Avoid: calls with unknown/expired sessionId. Side effects: stops screenrecord process, pulls video to local project artifacts.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "sessionId" to McpJsonSchemaProperty(type = "string"),
            ),
            required = listOf("projectDir", "sessionId"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "sessionId" to McpJsonSchemaProperty(type = "string"),
                        "serial" to McpJsonSchemaProperty(type = "string"),
                        "file" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = listOf("sessionId", "serial", "file"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val sessionId = arguments["sessionId"] as? String
        if (sessionId.isNullOrBlank()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: sessionId is required.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        val session = RecordSessionRegistry.findById(sessionId)
            ?: return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: sessionId not found: $sessionId.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )

        val selected = RecordToolSupport.resolveOnlineDevice(runtime, preferredSerial = session.serial)
            ?: return RecordToolSupport.noDeviceResult(toolName)
        val adb = selected.adb

        if (adb.serial != session.serial) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: session serial mismatch. expected=${session.serial}, actual=${adb.serial}.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        val localFile = File(session.localFilePath)

        return try {
            adb.execAdbShellCmd("sh -c 'kill -2 ${session.pid} >/dev/null 2>&1 || kill ${session.pid} >/dev/null 2>&1 || true'")
            Thread.sleep(500)

            var pulled = false
            for (attempt in 1..3) {
                if (adb.pull(session.remoteFile, localFile) && localFile.exists()) {
                    pulled = true
                    break
                }
                if (attempt < 3) {
                    Thread.sleep(800)
                }
            }

            if (!pulled) {
                return McpToolResult.internalErrorResult(toolName, "failed to pull record file after retries")
            }

            adb.execAdbShellCmd("rm -f ${session.remoteFile}")
            RecordSessionRegistry.remove(sessionId)

            McpToolResult(
                status = McpToolStatus.OK,
                message = "$toolName executed successfully.",
                data = mapOf(
                    "sessionId" to sessionId,
                    "serial" to session.serial,
                    "file" to localFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "video", path = localFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
        }
    }
}
