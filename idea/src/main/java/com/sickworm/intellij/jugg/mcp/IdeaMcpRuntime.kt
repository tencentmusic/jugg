package com.sickworm.intellij.jugg.mcp

import com.android.ddmlib.IDevice
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.logic.JuggConfigurationRunner
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File
import java.util.Locale
import kotlin.collections.get

class IdeaMcpRuntime(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val forceGradleCompileHelper: ForceGradleCompileHelper,
    private val juggConfigurationRunner: JuggConfigurationRunner,
) : IMcpRuntime {


    override fun restartApp(serial: String?): McpToolResult {
        val targetDevice = deployTargetManager.getConnectedDevices().find { it.serialNumber == serial }
        if (targetDevice == null && !serial.isNullOrEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: No device found for serial: $serial.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_NO_DEVICE,
            )
        }
        val targetDevices = if (targetDevice == null) {
            AsDeployerCompat.getSelectedDevices(project)
        } else {
            listOf(targetDevice)
        }
        if (targetDevices.isNullOrEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: No connected devices.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_NO_DEVICE,
            )
        }

        var isSuccess = true
        targetDevices.forEach { device ->
            val result = deployTargetManager.restartApp(device)
            isSuccess = isSuccess && result
        }
        if (!isSuccess) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart_app failed. Reason: Failed to restart app on some devices. Please check log in \\\$PROJECT_DIR/build/jugg/log/compile_latest.log\"",
                data = mapOf(
                    "devices" to targetDevices.map { it.mcpDeviceInfo }
                ),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "restart_app executed successfully.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    override fun compile(): McpToolResult {
        return deploy("compile")
    }

    override fun deploy(): McpToolResult {
        return deploy("deploy")
    }

    override fun cleanReinstall(): McpToolResult {
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = true
        return deploy("clean_reinstall")
    }

    private fun deploy(toolName: String): McpToolResult {
        val runResponse = juggConfigurationRunner.runFirstConfiguration(isRpcMode = true)
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

    override fun forceGradleCompile(): McpToolResult {
        return try {
            forceGradleCompileHelper.executeGradleCompile(autoConfirm = true)
            McpToolResult(
                status = McpToolStatus.OK,
                message = "force_gradle_compile executed successfully.",
                data = mapOf("triggered" to true),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            internalErrorResult("force_gradle_compile", e.message ?: "unknown error")
        }
    }
    override fun deviceList(): McpToolResult {
        val selectedSerials = deployTargetManager.getSelectedDevices()
            .mapNotNull { PlatformApi.toDeviceAdb(it)?.serial }
            .toSet()
        val connectedDevices = deployTargetManager.getConnectedDevices()
            .mapNotNull { PlatformApi.toDeviceAdb(it) }

        val devices = connectedDevices.map { adb ->
            mapOf(
                "serial" to adb.serial,
                "name" to adb.displayName,
                "isOnline" to adb.isOnline,
                "api" to adb.api,
                "isSelected" to selectedSerials.contains(adb.serial),
            )
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "device_list executed successfully.",
            data = mapOf("devices" to devices),
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    override fun screenshot(serial: String?): McpToolResult {
        val selected = resolveOnlineDevice(serial)
            ?: return noDeviceResult("screenshot")
        val adb = selected.adb
        val toolDir = ensureToolDir("screenshot")
            ?: return internalErrorResult("screenshot", "failed to prepare artifact directory")

        val fileName = "screenshot_${safeName(adb.serial)}_${System.currentTimeMillis()}.png"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        return try {
            adb.execAdbShellCmd("mkdir -p $remoteDir")
            adb.execAdbShellCmd("screencap -p $remoteFile")
            if (!adb.pull(remoteFile, localFile) || !localFile.exists()) {
                return internalErrorResult("screenshot", "failed to pull screenshot file")
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = "screenshot executed successfully. ${selected.messageDetail}",
                data = mapOf(
                    "device" to mapOf(
                        "serial" to adb.serial,
                        "name" to adb.displayName,
                        "isOnline" to adb.isOnline,
                    ),
                    "file" to localFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "image", path = localFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            internalErrorResult("screenshot", e.message ?: "unknown error")
        }
    }
    override fun record(
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
        val selected = resolveOnlineDevice(serial)
            ?: return noDeviceResult("record")
        val adb = selected.adb
        val toolDir = ensureToolDir("record")
            ?: return internalErrorResult("record", "failed to prepare artifact directory")

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
                    val resolvedPackageName = packageName ?: deployTargetManager.getPackageNameOrNull()
                        ?: return@synchronized internalErrorResult("record", "packageName is required when deploy target is unavailable")
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
                    return@synchronized internalErrorResult("record", reason)
                }
                val extraData = if (hasFlowActions) {
                    mapOf(
                        "packageName" to (packageName ?: deployTargetManager.getPackageNameOrNull().orEmpty()),
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
                internalErrorResult("record", e.message ?: "unknown error")
            }
        }
    }

    override fun layoutDump(serial: String?): McpToolResult {
        val selected = resolveOnlineDevice(serial)
            ?: return noDeviceResult("layout_dump")
        val adb = selected.adb
        val toolDir = ensureToolDir("layout_dump")
            ?: return internalErrorResult("layout_dump", "failed to prepare artifact directory")

        val fileName = "layout_${safeName(adb.serial)}_${System.currentTimeMillis()}.xml"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        return try {
            adb.execAdbShellCmd("mkdir -p $remoteDir")
            adb.execAdbShellCmd("uiautomator dump $remoteFile")
            if (!adb.pull(remoteFile, localFile) || !localFile.exists()) {
                return internalErrorResult("layout_dump", "failed to pull layout dump file")
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = "layout_dump executed successfully. ${selected.messageDetail}",
                data = mapOf(
                    "device" to mapOf(
                        "serial" to adb.serial,
                        "name" to adb.displayName,
                        "isOnline" to adb.isOnline,
                    ),
                    "file" to localFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "xml", path = localFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            internalErrorResult("layout_dump", e.message ?: "unknown error")
        }
    }

    override fun appStart(serial: String?, packageName: String?, activity: String?): McpToolResult {
        val selected = resolveOnlineDevice(serial)
            ?: return noDeviceResult("app_start")
        val adb = selected.adb

        return try {
            val resolvedPackageName = packageName ?: deployTargetManager.getPackageNameOrNull()
                ?: return internalErrorResult("app_start", "packageName is required when deploy target is unavailable")
            val activityPart = normalizeActivity(activity, resolvedPackageName)
            val component = "$resolvedPackageName/$activityPart"
            adb.execAdbShellCmd("am start -n $component")

            McpToolResult(
                status = McpToolStatus.OK,
                message = "app_start executed successfully. ${selected.messageDetail}",
                data = mapOf(
                    "device" to mapOf(
                        "serial" to adb.serial,
                        "name" to adb.displayName,
                        "isOnline" to adb.isOnline,
                    ),
                    "packageName" to resolvedPackageName,
                    "activity" to activityPart,
                    "component" to component,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            internalErrorResult("app_start", e.message ?: "unknown error")
        }
    }

    override fun tap(serial: String?, x: Int?, y: Int?): McpToolResult {
        val selected = resolveOnlineDevice(serial)
            ?: return noDeviceResult("tap")
        val adb = selected.adb

        if (x == null || y == null) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "tap failed. Reason: x and y are required.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        return try {
            adb.execAdbShellCmd("input tap $x $y")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully. ${selected.messageDetail}",
                data = mapOf(
                    "device" to mapOf(
                        "serial" to adb.serial,
                        "name" to adb.displayName,
                        "isOnline" to adb.isOnline,
                    ),
                    "x" to x,
                    "y" to y,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private data class SelectedAdb(
        val adb: IDeviceAdb,
        val messageDetail: String,
    )

    private fun resolveOnlineDevice(serial: String?): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(serial, deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb, messageDetail = selectionResult.messageDetail)
    }

    private fun ensureToolDir(toolName: String): File? {
        val projectDir = project.basePath ?: return null
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
            activity.startsWith(resolvedPackageName) -> activity.removePrefix(resolvedPackageName)
            else -> ".${activity.substringAfterLast('.')}"
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

    private fun internalErrorResult(toolName: String, reason: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: $reason.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
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

        val failureMessage = if (detail.isBlank()) {
            defaultFailureMessage
        } else {
            "$defaultFailureMessage\n$detail"
        }

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

    private val IDevice.mcpDeviceInfo: McpDeviceInfo
       get() {
            return McpDeviceInfo(
                serial = this.serialNumber,
                name = this.name,
                isOnline = this.isOnline,
            )
        }

    companion object {
        private val recordLock = Any()

        fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
            if (request.method != McpJsonRpc.Method.ToolsCall) {
                val response = McpInvoker.globalMcpInvoker.invokeMcp(request)
                return response
            }

            val toolName = (request.params as? Map<*, *>)?.get("name") as? String
            if (toolName == "list_projects") {
                val response = McpResultMapper().toolSuccess(
                    id = request.id,
                    toolResult = McpToolResult(
                        status = McpToolStatus.OK,
                        message = "list_projects executed successfully.",
                        data = mapOf(
                            "projects" to JuggInitializer.getInitializedProjectDirs().map {
                                McpProjectInfo(projectDir = it, initialized = true)
                            }
                        ),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                )
                return response
            }

            val projectDir = (request.params as? Map<*, *>)
                ?.let { params ->
                    @Suppress("UNCHECKED_CAST")
                    val args = params["arguments"] as? Map<String, Any?>
                    args?.get("projectDir") as? String
                }
                ?: run {
                    return McpResultMapper().toolError(
                        id = request.id,
                        errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                        message = "invoke_mcp failed. Reason: projectDir is required.")
                }

            val juggManager = JuggInitializer.getManager(projectDir)
                ?: run {
                    return McpResultMapper().toolError(
                        id = request.id,
                        errorCode = McpErrorCode.MCP_PROJECT_NOT_INITIALIZED,
                        message = "invoke_mcp failed. Reason: project is not initialized.")
                }

            val response = juggManager.invokeMcp(request)
            return response
        }
    }
}