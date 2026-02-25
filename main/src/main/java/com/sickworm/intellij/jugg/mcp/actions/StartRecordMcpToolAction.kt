package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import java.io.File

/**
 * StartRecordMcpToolAction starts device screen recording asynchronously and returns a sessionId immediately.
 */
class StartRecordMcpToolAction : McpToolAction {
    override val toolName: String = "start_record"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Start device screen recording asynchronously. Use when: you need video trace capture and will stop manually. Avoid: static end-state checks where screenshot is enough. Side effects: starts long-running screenrecord process and writes a temporary video file on device.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
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
                        "startedAtMs" to McpJsonSchemaProperty(type = "number"),
                    ),
                    required = listOf("sessionId", "serial", "file", "startedAtMs"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val selected = RecordToolSupport.resolveOnlineDevice(runtime)
            ?: return RecordToolSupport.noDeviceResult(toolName)
        val adb = selected.adb
        val serial = adb.serial

        val existing = RecordSessionRegistry.findBySerial(serial)
        if (existing != null) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: active session already exists on serial=$serial, sessionId=${existing.sessionId}.",
                data = mapOf(
                    "sessionId" to existing.sessionId,
                    "serial" to existing.serial,
                    "file" to existing.localFilePath,
                    "startedAtMs" to existing.startedAtMs,
                ),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        val toolDir = RecordToolSupport.ensureToolDir(runtime, "record")
            ?: return McpToolResult.internalErrorResult(toolName, "failed to prepare artifact directory")

        val fileName = "record_${System.currentTimeMillis()}.mp4"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"
        val startedAtMs = System.currentTimeMillis()

        return try {
            adb.execAdbShellCmd("mkdir -p $remoteDir")
            val pidOutput = adb.execAdbShellCmd("sh -c 'screenrecord $remoteFile >/dev/null 2>&1 & echo \$!'")
            val pid = extractPid(pidOutput)
                ?: return McpToolResult.internalErrorResult(toolName, "failed to start screenrecord: $pidOutput")

            val sessionId = buildSessionId(serial, startedAtMs)
            val session = RecordSessionRegistry.RecordSession(
                sessionId = sessionId,
                serial = serial,
                pid = pid,
                remoteFile = remoteFile,
                localFilePath = localFile.absolutePath,
                startedAtMs = startedAtMs,
            )
            if (!RecordSessionRegistry.registerIfAbsent(session)) {
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "$toolName failed. Reason: active session already exists on serial=$serial.",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                )
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = "$toolName executed successfully.",
                data = mapOf(
                    "sessionId" to sessionId,
                    "serial" to serial,
                    "file" to localFile.absolutePath,
                    "startedAtMs" to startedAtMs,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
        }
    }

    private fun extractPid(output: String): String? {
        val candidate = PID_REGEX.findAll(output).lastOrNull()?.value
        return if (candidate.isNullOrBlank()) null else candidate
    }

    private fun buildSessionId(serial: String, startedAtMs: Long): String {
        val serialSafe = serial.replace(SERIAL_SAFE_REGEX, "_")
        return "rec_${startedAtMs}_$serialSafe"
    }

    companion object {
        private val PID_REGEX = Regex("\\d+")
        private val SERIAL_SAFE_REGEX = Regex("[^A-Za-z0-9_]")
    }
}
