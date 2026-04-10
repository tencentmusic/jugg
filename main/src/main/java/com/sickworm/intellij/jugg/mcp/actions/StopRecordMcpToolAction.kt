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
 * StopRecordMcpToolAction stops a running record-start session and fetches the generated video artifact.
 */
class StopRecordMcpToolAction : McpToolAction {
    override val toolName: String = "record-stop"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Stop a running recording session and fetch mp4 artifact.",
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
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            return preWaitResult.errorResult ?: McpToolResult.internalErrorResult(toolName, "app is not ready")
        }

        val localFile = File(session.localFilePath)

        return try {
            if (session.hostProcess != null) {
                stopHostRecordProcess(session.hostProcess)
            } else {
                adb.execAdbShellScript("kill -2 ${session.pid} >/dev/null 2>&1 || kill ${session.pid} >/dev/null 2>&1 || true")
            }
            waitForRemoteFileReady(adb, session.remoteFile)

            var pulled = false
            for (attempt in 1..5) {
                if (adb.pull(session.remoteFile, localFile) && localFile.exists()) {
                    pulled = true
                    break
                }
                if (attempt < 5) {
                    Thread.sleep(1000)
                }
            }

            if (!pulled) {
                localFile.delete()
                val remoteStatus = adb.execAdbShellCmd("ls -l ${session.remoteFile} 2>/dev/null || echo __MISSING__")
                return McpToolResult.internalErrorResult(
                    toolName,
                    "failed to pull record file after retries. remoteStatus=$remoteStatus, launchMode=${session.launchMode}",
                )
            }

            adb.execAdbShellCmd("rm -f ${session.remoteFile}")

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
        } finally {
            if (session.hostProcess?.isAlive == true) {
                session.hostProcess.destroyForcibly()
            }
            RecordSessionRegistry.remove(sessionId)
        }
    }

    private fun stopHostRecordProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /**
     * Wait for screenrecord output to be materialized and non-empty before pulling.
     */
    private fun waitForRemoteFileReady(adb: com.sickworm.intellij.jugg.deploy.IDeviceAdb, remoteFile: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val sizeOutput = adb.execAdbShellScript(
                "if [ -f $remoteFile ]; then wc -c < $remoteFile; else echo -1; fi",
            )
            val size = NUMBER_REGEX.findAll(sizeOutput).lastOrNull()?.value?.toLongOrNull() ?: -1L
            if (size > 0) {
                return
            }
            Thread.sleep(500)
        }
    }

    companion object {
        private val NUMBER_REGEX = Regex("-?\\d+")
    }
}
