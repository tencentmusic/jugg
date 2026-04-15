package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import java.io.File

/**
 * StartRecordMcpToolAction starts device screen recording asynchronously and returns a sessionId immediately.
 */
class StartRecordMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.RECORD_START

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Start device screen recording asynchronously. Returns sessionId for stop_record.",
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
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            return preWaitResult.errorResult ?: McpToolResult.internalErrorResult(toolName, "app is not ready")
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
            val remoteResult = if (ENABLE_REMOTE_BG_SCREENRECORD) {
                tryStartViaRemoteBackground(adb, remoteFile)
            } else {
                RemoteStartResult(
                    alivePid = null,
                    failureReason = "disabledByFlag",
                )
            }

            val hostFallback = if (remoteResult.alivePid == null) {
                tryStartViaHostAdb(serial, remoteFile)
            } else {
                null
            }

            val finalPid = remoteResult.alivePid ?: hostFallback?.hostPid
            if (finalPid == null) {
                val hostFallbackFailure = hostFallback?.failureReason ?: "hostFallbackSkipped"
                return McpToolResult.internalErrorResult(
                    toolName,
                    "screenrecord start failed. remoteStart=${remoteResult.failureReason}, hostFallback=$hostFallbackFailure",
                )
            }

            val sessionId = buildSessionId(serial, startedAtMs)
            val session = RecordSessionRegistry.RecordSession(
                sessionId = sessionId,
                serial = serial,
                pid = finalPid,
                remoteFile = remoteFile,
                localFilePath = localFile.absolutePath,
                startedAtMs = startedAtMs,
                launchMode = if (remoteResult.alivePid != null) "REMOTE_BG" else "HOST_ADB",
                hostProcess = hostFallback?.process,
            )
            if (!RecordSessionRegistry.registerIfAbsent(session)) {
                hostFallback?.process?.destroyForcibly()
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

    /**
     * Try detached screenrecord on device side.
     */
    private fun tryStartViaRemoteBackground(
        adb: com.sickworm.intellij.jugg.deploy.IDeviceAdb,
        remoteFile: String,
    ): RemoteStartResult {
        val pidOutput = adb.execAdbShellScript("nohup screenrecord $remoteFile >/dev/null 2>&1 < /dev/null & echo \$!")
        val pid = extractPid(pidOutput)
            ?: return RemoteStartResult(
                alivePid = null,
                failureReason = "invalidPidOutput($pidOutput)",
            )

        val alivePid = resolveAliveRecordPid(adb, pid, remoteFile)
        if (alivePid == null) {
            adb.execAdbShellCmd("rm -f $remoteFile")
            return RemoteStartResult(
                alivePid = null,
                failureReason = "exitedImmediately(pid=$pid)",
            )
        }
        return RemoteStartResult(
            alivePid = alivePid,
            failureReason = null,
        )
    }

    /**
     * On some devices, detached screenrecord started inside device shell exits immediately.
     * This fallback keeps `adb shell screenrecord` alive from host side and tracks that process in session registry.
     */
    private fun tryStartViaHostAdb(serial: String, remoteFile: String): HostFallbackStartResult {
        return try {
            val process = ProcessBuilder(
                AdbCmdHelper.findAdbExecutablePath(),
                "-s",
                serial,
                "shell",
                "screenrecord",
                remoteFile,
            ).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

            Thread.sleep(500)
            if (!process.isAlive) {
                return HostFallbackStartResult(
                    process = null,
                    hostPid = null,
                    failureReason = "adbProcessExited",
                )
            }

            HostFallbackStartResult(
                process = process,
                hostPid = "host:${process.pid()}",
                failureReason = null,
            )
        } catch (e: Exception) {
            HostFallbackStartResult(
                process = null,
                hostPid = null,
                failureReason = e.message ?: "unknown",
            )
        }
    }

    /**
     * Resolve the real long-lived screenrecord pid.
     * The first background pid returned by shell may be transient on some devices.
     */
    private fun resolveAliveRecordPid(
        adb: com.sickworm.intellij.jugg.deploy.IDeviceAdb,
        initialPid: String,
        remoteFile: String,
    ): String? {
        if (isProcessAlive(adb, initialPid)) {
            return initialPid
        }

        val discoveredOutput = adb.execAdbShellScript("pgrep -f $remoteFile | head -n 1")
        val discoveredPid = extractPid(discoveredOutput) ?: return null
        return if (isProcessAlive(adb, discoveredPid)) discoveredPid else null
    }

    private fun isProcessAlive(adb: com.sickworm.intellij.jugg.deploy.IDeviceAdb, pid: String): Boolean {
        Thread.sleep(300)
        val probe = adb.execAdbShellScript("if [ -d /proc/$pid ]; then echo __ALIVE__; else echo __DEAD__; fi")
        return probe.contains("__ALIVE__")
    }

    companion object {
        private val PID_REGEX = Regex("\\d+")
        private val SERIAL_SAFE_REGEX = Regex("[^A-Za-z0-9_]")
        /**
         * Device-side detached screenrecord has compatibility issues on some OEM ROMs.
         * Keep this switch OFF by default and use host-managed adb screenrecord path.
         */
        private const val ENABLE_REMOTE_BG_SCREENRECORD = false
    }

    private data class RemoteStartResult(
        val alivePid: String?,
        val failureReason: String?,
    )

    private data class HostFallbackStartResult(
        val process: Process?,
        val hostPid: String?,
        val failureReason: String?,
    )
}
