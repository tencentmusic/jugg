package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.CachedOverlayId
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService

/**
 * DirectOverlayStateChecker verifies overlay checkpoint consistency for recover and swap paths.
 * Recover except-overlay rules match [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer] optimisticSwap.
 */
class DirectOverlayStateChecker(
    private val adb: IDeviceAdb,
    private val logger: Logger,
    private val deployHistoryManager: IDeployHistoryManager? = null,
    private val deploymentService: IJuggDeploymentService? = null,
) {

    /**
     * Validates deployment cache and device overlay for recover dry deploy.
     * Except-overlay handling mirrors optimisticSwap: compare history to cache unless [isSkipExceptOverlayCheck].
     */
    fun checkRecover(
        deviceSerial: String,
        packageName: String,
        isSkipExceptOverlayCheck: Boolean = false,
    ): DirectOverlayStateCheckResult {
        val historyManager = requireNotNull(deployHistoryManager) { "deployHistoryManager is required for checkRecover" }
        val deploymentCache = requireNotNull(deploymentService) { "deploymentService is required for checkRecover" }

        val cachedOverlayId = deploymentCache.loadCachedOverlayId(deviceSerial, packageName, logger)
        if (cachedOverlayId == null) {
            logger.debug("Direct overlay state check mismatched for missing local deployment cache")
            return DirectOverlayStateCheckResult.MISMATCHED
        }

        if (!isSkipExceptOverlayCheck) {
            val exceptOverlayId = historyManager.lastDeployOverlayIds[packageName]
            if (exceptOverlayId != cachedOverlayId.sha) {
                logger.debug(
                    "Direct overlay state check mismatched for overlay id mismatch with Jugg, " +
                        "cached: ${cachedOverlayId.sha}, except: $exceptOverlayId",
                )
                return DirectOverlayStateCheckResult.MISMATCHED
            }
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
            state.startsWith("ID") -> {
                val actualOverlayId = state.substringAfter("ID").trim()
                if (actualOverlayId == expectedDeviceOverlayId) {
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
