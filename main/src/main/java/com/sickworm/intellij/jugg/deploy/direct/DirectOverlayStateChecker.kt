package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService

/**
 * DirectOverlayStateChecker verifies overlay checkpoint consistency for recover and swap paths.
 * Recover checks require deploy history, deployment cache, and device overlay to agree.
 */
class DirectOverlayStateChecker(
    private val adb: IDeviceAdb,
    private val logger: Logger,
    private val deployHistoryManager: IDeployHistoryManager? = null,
    private val deploymentService: IJuggDeploymentService? = null,
) {

    /**
     * Validates history, deployment cache, and device overlay state for recover dry deploy.
     *
     * @return null when deploy history overlay id is missing and the check should be skipped
     */
    fun checkRecover(deviceSerial: String, packageName: String): DirectOverlayStateCheckResult {
        val historyManager = requireNotNull(deployHistoryManager) { "deployHistoryManager is required for checkRecover" }
        val deploymentCache = requireNotNull(deploymentService) { "deploymentService is required for checkRecover" }

        val historyOverlayId = historyManager.lastDeployOverlayIds[packageName]
        if (historyOverlayId == null) {
            logger.debug("Direct overlay state check skipped for " +
                    "missing deploy history overlay id.")
            return DirectOverlayStateCheckResult.MISMATCHED
        }

        val cachedOverlayId = deploymentCache.loadCachedOverlayId(deviceSerial, packageName, logger)
        if (cachedOverlayId == null) {
            logger.debug("Direct overlay state check mismatched for " +
                    "missing local deployment cache, history: $historyOverlayId")
            return DirectOverlayStateCheckResult.MISMATCHED
        }

        if (cachedOverlayId.sha != historyOverlayId) {
            logger.info("Direct overlay state check mismatched for " +
                    "local overlay id mismatch, cached: ${cachedOverlayId.sha}, history: $historyOverlayId")
            return DirectOverlayStateCheckResult.MISMATCHED
        }

        val expectedDeviceOverlayId = if (cachedOverlayId.isBaseInstall) "" else cachedOverlayId.sha
        return checkDevice(packageName, expectedDeviceOverlayId)
    }

    /**
     * Compares the device overlay checkpoint against a caller-provided expected overlay id.
     */
    fun checkDevice(packageName: String, expectedDeviceOverlayId: String): DirectOverlayStateCheckResult {
        val output = try {
            adb.execAdbShellScript(buildCheckScript(packageName))
        } catch (e: Exception) {
            logger.debug("Direct overlay state check failed.", e)
            return DirectOverlayStateCheckResult.UNKNOWN
        }
        return parseOutput(output.trim(), expectedDeviceOverlayId)
    }

    private fun buildCheckScript(packageName: String): String {
        return "run-as $packageName sh -c '" +
            "if [ -d code_cache/.overlay ]; then " +
            "if [ -f code_cache/.overlay/id ]; then " +
            "printf \"$MARKER ID \"; cat code_cache/.overlay/id; " +
            "else echo \"$MARKER MISSING_ID\"; fi; " +
            "else echo \"$MARKER NO_DIR\"; fi" +
            "'"
    }

    private fun parseOutput(output: String, expectedDeviceOverlayId: String): DirectOverlayStateCheckResult {
        val markerLine = output.lineSequence().firstOrNull { it.contains(MARKER) }
            ?: return DirectOverlayStateCheckResult.UNKNOWN
        val state = markerLine.substringAfter(MARKER).trim()
        return when {
            state == "NO_DIR" -> {
                if (expectedDeviceOverlayId.isEmpty()) {
                    DirectOverlayStateCheckResult.MATCHED
                } else {
                    DirectOverlayStateCheckResult.MISMATCHED
                }
            }
            state == "MISSING_ID" -> DirectOverlayStateCheckResult.MISMATCHED
            state.startsWith("ID ") -> {
                val actualOverlayId = state.removePrefix("ID ").trim()
                if (expectedDeviceOverlayId.isNotEmpty() && actualOverlayId == expectedDeviceOverlayId) {
                    DirectOverlayStateCheckResult.MATCHED
                } else {
                    DirectOverlayStateCheckResult.MISMATCHED
                }
            }
            else -> DirectOverlayStateCheckResult.UNKNOWN
        }
    }

    companion object {
        private const val MARKER = "__JUGG_OVERLAY_STATE__"
    }
}

/**
 * DirectOverlayStateCheckResult is tri-state because adb/run-as failures must fall back to the legacy dry deploy.
 */
enum class DirectOverlayStateCheckResult {
    MATCHED,
    MISMATCHED,
    UNKNOWN,
}
