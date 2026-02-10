package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File
import java.util.Locale

class RecordMcpToolAction : McpToolAction {
    override val toolName: String = "record"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Record device screen video, with optional app_start and tap actions during recording. Use when you need reproducible visual traces. Avoid for a single-frame check where screenshot is enough. Side effects: may launch app and inject taps if configured.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "serial" to McpToolSchemas.serialProperty,
                "durationSec" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional recording duration in seconds.",
                    default = 10,
                    minimum = 1.0,
                    maximum = 180.0,
                ),
                "packageName" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional package for app_start action during recording.",
                    pattern = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                    examples = listOf("com.example.app"),
                ),
                "activity" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional activity for app_start action. Supports short form (.MainActivity) or full class name.",
                    pattern = "^\\.?[A-Za-z_][A-Za-z0-9_$.]*$",
                    examples = listOf(".MainActivity", "com.example.app.MainActivity"),
                ),
                "tapX" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional tap x coordinate. Must be provided with tapY.",
                    minimum = 0.0,
                ),
                "tapY" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional tap y coordinate. Must be provided with tapX.",
                    minimum = 0.0,
                ),
                "preTapDelaySec" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional delay after app_start before first tap.",
                    default = 0,
                    minimum = 0.0,
                    maximum = 30.0,
                ),
                "tapRepeat" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional number of repeated taps.",
                    default = 1,
                    minimum = 1.0,
                    maximum = 20.0,
                ),
                "tapIntervalSec" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional delay between repeated taps.",
                    default = 0,
                    minimum = 0.0,
                    maximum = 30.0,
                ),
                "recordStartDelaySec" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Optional delay after recording starts before app_start.",
                    default = 0,
                    minimum = 0.0,
                    maximum = 30.0,
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "device" to McpToolSchemas.deviceProperty,
                        "durationSec" to McpJsonSchemaProperty(type = "number", minimum = 1.0, maximum = 180.0),
                        "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.mp4$"),
                        "packageName" to McpJsonSchemaProperty(type = "string"),
                        "activity" to McpJsonSchemaProperty(type = "string"),
                        "tap" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "x" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                                "y" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                            ),
                            required = listOf("x", "y"),
                            additionalProperties = false,
                        ),
                    ),
                    required = listOf("device", "durationSec", "file"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return recordAction(
            runtime,
            serial = arguments["serial"] as? String,
            durationSec = (arguments["durationSec"] as? Number)?.toInt(),
            packageName = arguments["packageName"] as? String,
            activity = arguments["activity"] as? String,
            tapX = (arguments["tapX"] as? Number)?.toInt(),
            tapY = (arguments["tapY"] as? Number)?.toInt(),
            preTapDelaySec = (arguments["preTapDelaySec"] as? Number)?.toDouble(),
            tapRepeat = (arguments["tapRepeat"] as? Number)?.toInt(),
            tapIntervalSec = (arguments["tapIntervalSec"] as? Number)?.toDouble(),
            recordStartDelaySec = (arguments["recordStartDelaySec"] as? Number)?.toDouble(),
        )
    }

    private fun recordAction(
        runtime: IMcpRuntime,
        serial: String?,
        durationSec: Int?,
        packageName: String?,
        activity: String?,
        tapX: Int?,
        tapY: Int?,
        preTapDelaySec: Double?,
        tapRepeat: Int?,
        tapIntervalSec: Double?,
        recordStartDelaySec: Double?,
    ): McpToolResult {
        val selected = resolveOnlineDevice(runtime, serial)
            ?: return noDeviceResult("record")
        val adb = selected.adb
        val toolDir = ensureToolDir(runtime, "record")
            ?: return McpToolResult.internalErrorResult("record", "failed to prepare artifact directory")

        if ((tapX == null) xor (tapY == null)) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "record failed. Reason: tapX and tapY must be provided together.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        val clampedDurationSec = (durationSec ?: 10).coerceIn(1, 180)
        val recordDelay = (recordStartDelaySec ?: 0.8).coerceIn(0.0, 8.0)
        val preTapDelay = (preTapDelaySec ?: 1.0).coerceIn(0.0, 15.0)
        val clampedTapRepeat = (tapRepeat ?: 1).coerceIn(1, 8)
        val clampedTapInterval = (tapIntervalSec ?: 1.0).coerceIn(0.0, 8.0)

        val fileName = "record_${safeName(adb.serial)}_${System.currentTimeMillis()}.mp4"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        val hasFlowActions = !activity.isNullOrBlank() || (tapX != null && tapY != null)

        return synchronized(recordLock) {
            try {
                adb.execAdbShellCmd("mkdir -p $remoteDir")

                var executedCommand = ""
                var shellOutput = ""
                if (hasFlowActions) {
                    val resolvedPackageName = packageName ?: runtime.deployTargetManager.getPackageNameOrNull()
                        ?: return@synchronized McpToolResult.internalErrorResult("record", "packageName is required when deploy target is unavailable")
                    val activityPart = normalizeActivity(activity, resolvedPackageName)
                    val component = "$resolvedPackageName/$activityPart"
                    val flowCommand = buildRecordFlowCommand(
                        remoteFile = remoteFile,
                        durationSec = clampedDurationSec,
                        component = component,
                        recordStartDelaySec = recordDelay,
                        preTapDelaySec = preTapDelay,
                        tapX = tapX,
                        tapY = tapY,
                        tapRepeat = clampedTapRepeat,
                        tapIntervalSec = clampedTapInterval,
                    )
                    executedCommand = flowCommand
                    shellOutput = adb.execAdbShellScript(flowCommand)
                } else {
                    val screenRecordCommand = "screenrecord --time-limit $clampedDurationSec $remoteFile"
                    executedCommand = screenRecordCommand
                    shellOutput = adb.execAdbShellCmd(screenRecordCommand)
                }

                var pulled = false
                for (attempt in 1..3) {
                    if (adb.pull(remoteFile, localFile) && localFile.exists()) {
                        pulled = true
                        break
                    }
                    if (attempt < 3) {
                        Thread.sleep(800)
                    }
                }
                if (!pulled) {
                    val reason = "failed to pull record file after retries. cmd=$executedCommand, shellOut=$shellOutput"
                    return@synchronized McpToolResult.internalErrorResult("record", reason)
                }
                val extraData = if (hasFlowActions) {
                    mapOf(
                        "packageName" to (packageName ?: runtime.deployTargetManager.getPackageNameOrNull().orEmpty()),
                        "activity" to activity,
                        "tapX" to tapX,
                        "tapY" to tapY,
                        "tapRepeat" to clampedTapRepeat,
                    )
                } else {
                    emptyMap()
                }

                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "record executed successfully. ${selected.messageDetail}",
                    data = mapOf(
                        "device" to mapOf(
                            "serial" to adb.serial,
                            "name" to adb.displayName,
                            "isOnline" to adb.isOnline,
                        ),
                        "durationSec" to clampedDurationSec,
                        "file" to localFile.absolutePath,
                    ) + extraData,
                    artifacts = listOf(McpArtifact(type = "video", path = localFile.absolutePath)),
                    errorCode = null,
                )
            } catch (e: Exception) {
                McpToolResult.internalErrorResult("record", e.message ?: "unknown error")
            }
        }
    }

    private data class SelectedAdb(
        val adb: IDeviceAdb,
        val messageDetail: String,
    )

    private fun resolveOnlineDevice(runtime: IMcpRuntime, serial: String?): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(serial, runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb, messageDetail = selectionResult.messageDetail)
    }

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(projectDir, "build/jugg/mcp_fetch/$toolName")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun normalizeActivity(activity: String?, resolvedPackageName: String): String {
        return when {
            activity.isNullOrBlank() -> ".MainActivity"
            activity.startsWith(".") -> activity
            activity.startsWith("$resolvedPackageName.") -> activity.removePrefix(resolvedPackageName)
            activity.contains(".") -> activity
            else -> ".$activity"
        }
    }

    private fun buildRecordFlowCommand(
        remoteFile: String,
        durationSec: Int,
        component: String,
        recordStartDelaySec: Double,
        preTapDelaySec: Double,
        tapX: Int?,
        tapY: Int?,
        tapRepeat: Int,
        tapIntervalSec: Double,
    ): String {
        val commands = mutableListOf<String>()
        commands += "screenrecord --time-limit $durationSec $remoteFile >/dev/null 2>&1 & REC_PID=\$!"
        if (recordStartDelaySec > 0.0) {
            commands += "sleep ${formatShellSec(recordStartDelaySec)}"
        }
        commands += "am start -n $component >/dev/null 2>&1"

        if (tapX != null && tapY != null) {
            if (preTapDelaySec > 0.0) {
                commands += "sleep ${formatShellSec(preTapDelaySec)}"
            }
            repeat(tapRepeat) { index ->
                commands += "input tap $tapX $tapY"
                if (index < tapRepeat - 1 && tapIntervalSec > 0.0) {
                    commands += "sleep ${formatShellSec(tapIntervalSec)}"
                }
            }
        }

        commands += "wait \$REC_PID"
        return commands.joinToString(" ; ")
    }

    private fun formatShellSec(value: Double): String {
        return String.Companion.format(Locale.US, "%.2f", value.coerceAtLeast(0.0))
    }

    private fun safeName(value: String): String {
        return value.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    private fun noDeviceResult(toolName: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }

    companion object {
        private val recordLock = Any()
    }
}
