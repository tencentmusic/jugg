package com.sickworm.intellij.jugg.server

import com.android.ddmlib.IDevice
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.rpc.RpcCommand
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResult
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.mcp.*
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import java.io.File

class IdeMcpRuntime(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
) : IMcpRuntime {

    private val gson = Gson()

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
        val runResponse = runByRpc()
        if (runResponse.status != RpcResult.OK) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "compile failed. Reason: ${runResponse.result}",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        val runResultObject = parseRunResult(runResponse.result)
            ?: return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "compile failed. Reason: invalid run result payload.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )

        val isCompileSuccess = runResultObject.get("isCompileSuccess")?.asBoolean ?: false
        val message = if (isCompileSuccess) {
            "compile executed successfully."
        } else {
            "compile failed. Reason: compile stage not successful."
        }

        return McpToolResult(
            status = if (isCompileSuccess) McpToolStatus.OK else McpToolStatus.ERROR,
            message = message,
            data = mapOf(
                "runResult" to runResultObject,
            ),
            artifacts = emptyList(),
            errorCode = if (isCompileSuccess) null else McpErrorCode.MCP_INTERNAL_ERROR,
        )
    }

    override fun deploy(): McpToolResult {
        val runResponse = runByRpc()
        if (runResponse.status != RpcResult.OK) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "deploy failed. Reason: ${runResponse.result}",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        val runResultObject = parseRunResult(runResponse.result)
            ?: return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "deploy failed. Reason: invalid run result payload.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )

        val isDeploySuccess = runResultObject.get("isDeploySuccess")?.asBoolean ?: false
        val message = if (isDeploySuccess) {
            "deploy executed successfully."
        } else {
            "deploy failed. Reason: deploy stage not successful."
        }

        return McpToolResult(
            status = if (isDeploySuccess) McpToolStatus.OK else McpToolStatus.ERROR,
            message = message,
            data = mapOf(
                "runResult" to runResultObject,
            ),
            artifacts = emptyList(),
            errorCode = if (isDeploySuccess) null else McpErrorCode.MCP_INTERNAL_ERROR,
        )
    }

    override fun cleanReinstall(): McpToolResult {
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = true
        val runResponse = runByRpc()
        if (runResponse.status != RpcResult.OK) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "clean_reinstall failed. Reason: ${runResponse.result}",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }

        val runResultObject = parseRunResult(runResponse.result)
            ?: return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "clean_reinstall failed. Reason: invalid run result payload.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )

        val isSuccess = (runResultObject.get("isCompileSuccess")?.asBoolean ?: false) &&
            (runResultObject.get("isDeploySuccess")?.asBoolean ?: false)

        return McpToolResult(
            status = if (isSuccess) McpToolStatus.OK else McpToolStatus.ERROR,
            message = if (isSuccess) "clean_reinstall executed successfully." else "clean_reinstall failed.",
            data = mapOf(
                "runResult" to runResultObject,
                "cleanAndReinstall" to true,
            ),
            artifacts = emptyList(),
            errorCode = if (isSuccess) null else McpErrorCode.MCP_INTERNAL_ERROR,
        )
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

    override fun record(serial: String?, durationSec: Int?): McpToolResult {
        val selected = resolveOnlineDevice(serial)
            ?: return noDeviceResult("record")
        val adb = selected.adb
        val toolDir = ensureToolDir("record")
            ?: return internalErrorResult("record", "failed to prepare artifact directory")

        val clampedDurationSec = (durationSec ?: 10).coerceIn(1, 180)
        val fileName = "record_${safeName(adb.serial)}_${System.currentTimeMillis()}.mp4"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        return synchronized(recordLock) {
            try {
                adb.execAdbShellCmd("mkdir -p $remoteDir")
                adb.execAdbShellCmd("screenrecord --time-limit $clampedDurationSec $remoteFile")
                if (!adb.pull(remoteFile, localFile) || !localFile.exists()) {
                    return@synchronized internalErrorResult("record", "failed to pull record file")
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
                    ),
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

    private data class SelectedAdb(
        val adb: com.sickworm.intellij.jugg.deploy.IDeviceAdb,
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

    private fun runByRpc() = JuggInitializer.getManager(project)
        ?.call(
            RpcRequest(
                cmd = RpcCommand.RUN,
                projectDir = project.basePath,
                args = mapOf("isRpcMode" to true),
            )
        )
        ?: com.sickworm.intellij.jugg.rpc.RpcResponse(
            status = RpcResult.ErrorInvalidProjectDir,
            result = "Project is not initialized with Jugg.",
        )

    private fun parseRunResult(rawResult: Any?): JsonObject? {
        val resultText = rawResult as? String ?: return null
        val root = JsonParser.parseString(resultText).asJsonObject
        return root.getAsJsonObject("runResult")
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
