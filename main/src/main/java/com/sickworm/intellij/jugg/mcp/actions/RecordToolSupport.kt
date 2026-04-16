package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * RecordToolSupport provides shared helpers for start_record / stop_record actions.
 */
object RecordToolSupport {
    data class SelectedAdb(
        val adb: IDeviceAdb,
    )

    /**
     * Resolve one online adb target; when preferredSerial is set, enforce serial match.
     */
    fun resolveOnlineDevice(runtime: IMcpRuntime, preferredSerial: String? = null): SelectedAdb? {
        if (!preferredSerial.isNullOrBlank()) {
            val matched = runtime.deployTargetManager.getConnectedDevices().firstOrNull {
                it.serialNumber == preferredSerial
            } ?: return null
            val adb = PlatformApi.toDeviceAdb(matched) ?: return null
            if (!adb.isOnline) {
                return null
            }
            return SelectedAdb(adb = adb)
        }

        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb)
    }

    fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, toolName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun noDeviceResult(toolName: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }
}
