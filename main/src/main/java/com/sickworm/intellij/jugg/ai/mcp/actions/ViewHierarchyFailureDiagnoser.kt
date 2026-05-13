package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.deploy.IDeviceAdb

/**
 * Diagnoses ViewHierarchy transport failures after the first request attempt.
 */
internal object ViewHierarchyFailureDiagnoser {

    fun unavailableResult(
        toolName: String,
        adb: IDeviceAdb,
        packageName: String,
        fallbackMessage: String,
        data: Map<String, Any> = emptyMap(),
    ): McpToolResult {
        val deviceState = queryDeviceState(adb)
        if (deviceState.isSleeping()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: device screen is off or not interactive; " +
                    "ViewHierarchy server cannot be accessed while the device is sleeping. " +
                    "Wake/unlock the device and retry.",
                data = data + deviceState.toData(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.DEVICE_NOT_INTERACTIVE,
            )
        }
        val foregroundState = queryForegroundState(adb, packageName)
        if (foregroundState.isBackground()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: target app is not in foreground; " +
                    "ViewHierarchy server cannot be accessed while another app is on top. " +
                    "Bring the target app to foreground and retry.",
                data = data + foregroundState.toData(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.APP_NOT_FOREGROUND,
            )
        }
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: $fallbackMessage.",
            data = data,
            artifacts = emptyList(),
            errorCode = McpErrorCode.INTERNAL_ERROR,
        )
    }

    private fun queryForegroundState(adb: IDeviceAdb, packageName: String): ForegroundState {
        val output = runCatching { adb.execAdbShellCmd(ACTIVITY_DUMP_COMMAND) }.getOrDefault("")
        val topActivity = parseTopActivity(output)
        return ForegroundState(
            packageName = packageName,
            topActivity = topActivity,
        )
    }

    private fun parseTopActivity(output: String): String? {
        for (keyword in TOP_ACTIVITY_KEYWORDS) {
            val line = output.lineSequence().firstOrNull { it.contains(keyword) } ?: continue
            val component = COMPONENT_PATTERN.find(line)?.groupValues?.getOrNull(1)
            if (!component.isNullOrBlank()) {
                return component
            }
        }
        return COMPONENT_PATTERN.find(output)?.groupValues?.getOrNull(1)
    }

    private fun queryDeviceState(adb: IDeviceAdb): DeviceState {
        val powerOutput = runCatching { adb.execAdbShellCmd("dumpsys power") }.getOrDefault("")
        val policyOutput = runCatching { adb.execAdbShellCmd("dumpsys window policy") }.getOrDefault("")
        return DeviceState(
            wakefulness = WAKEFULNESS_PATTERN.find(powerOutput)?.groupValues?.getOrNull(1),
            screenState = SCREEN_STATE_PATTERN.find(policyOutput)?.groupValues?.getOrNull(1),
            interactiveState = INTERACTIVE_STATE_PATTERN.find(policyOutput)?.groupValues?.getOrNull(1),
            rawPowerOutput = powerOutput,
            rawPolicyOutput = policyOutput,
        )
    }

    private data class DeviceState(
        val wakefulness: String?,
        val screenState: String?,
        val interactiveState: String?,
        val rawPowerOutput: String,
        val rawPolicyOutput: String,
    ) {
        fun isSleeping(): Boolean {
            return wakefulness in SLEEPING_WAKEFULNESS ||
                screenState == "SCREEN_STATE_OFF" ||
                interactiveState == "INTERACTIVE_STATE_SLEEP" ||
                rawPowerOutput.contains("mInteractive=false") ||
                rawPolicyOutput.contains("interactiveState=INTERACTIVE_STATE_SLEEP")
        }

        fun toData(): Map<String, Any> {
            val result = mutableMapOf<String, Any>("deviceInteractive" to false)
            wakefulness?.let { result["wakefulness"] = it }
            screenState?.let { result["screenState"] = it }
            interactiveState?.let { result["interactiveState"] = it }
            return result
        }
    }

    private data class ForegroundState(
        val packageName: String,
        val topActivity: String?,
    ) {
        fun isBackground(): Boolean {
            if (topActivity.isNullOrBlank()) {
                return false
            }
            return !topActivity.startsWith("$packageName/")
        }

        fun toData(): Map<String, Any> {
            val result = mutableMapOf<String, Any>(
                "appForeground" to false,
                "packageName" to packageName,
            )
            topActivity?.let { result["topActivity"] = it }
            return result
        }
    }

    private const val ACTIVITY_DUMP_COMMAND = "dumpsys activity activities"
    private val WAKEFULNESS_PATTERN = Regex("""mWakefulness=([A-Za-z]+)""")
    private val SCREEN_STATE_PATTERN = Regex("""screenState=([A-Z_]+)""")
    private val INTERACTIVE_STATE_PATTERN = Regex("""interactiveState=([A-Z_]+)""")
    private val COMPONENT_PATTERN = Regex("""([A-Za-z][A-Za-z0-9_.$]*(?:\.[A-Za-z0-9_.$]+)+/[A-Za-z0-9_.$]+)""")
    private val TOP_ACTIVITY_KEYWORDS = listOf("topResumedActivity", "mResumedActivity", "mFocusedActivity")
    private val SLEEPING_WAKEFULNESS = setOf("Asleep", "Dozing")
}
