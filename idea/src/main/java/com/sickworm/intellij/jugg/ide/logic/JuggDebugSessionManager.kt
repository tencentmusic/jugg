package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.logger.JuggLogger

/**
 * Coordinates Java debugger attachment after Jugg compile/deploy has completed.
 */
class JuggDebugSessionManager(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val asDeployerCompat: IAsDeployerCompat = AsDeployerCompat,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDebugSessionManager"),
    private val retryController: IDebugAttachRetryController = DebugAttachRetryController(),
) {

    fun attachAfterSuccessfulRun(runResult: RunResult, compileUiHandler: CompileUiHandler) {
        if (!runResult.isCompileSuccess || !runResult.isDeploySuccess || runResult.isCancel) {
            return
        }
        val devices = runCatching { deployTargetManager.getSelectedDevices() }.getOrElse {
            reportAttachFailure("Unable to get selected device: ${it.message}", compileUiHandler, it)
            return
        }
        if (devices.size != 1) {
            reportAttachFailure("Jugg Debug does not support multiple devices.", compileUiHandler)
            return
        }
        val packageName = runCatching { deployTargetManager.getPackageName() }.getOrElse {
            reportAttachFailure("Unable to resolve package name: ${it.message}", compileUiHandler, it)
            return
        }
        val attachError = attachJavaDebuggerWithRetry(devices.first(), packageName)
        if (attachError != null) {
            reportAttachFailure(attachError.message ?: attachError.toString(), compileUiHandler, attachError)
        }
    }

    private fun attachJavaDebuggerWithRetry(device: com.android.ddmlib.IDevice, packageName: String): Throwable? {
        var attempt = 1
        logger.info("")
        while (true) {
            runCatching {
                logger.info("\nStart Debugger attaching.")
                logger.info("Waiting for $packageName to enter debugger WAITING state.")
                asDeployerCompat.attachJavaDebugger(project, device, packageName)
            }.onSuccess {
                logger.info("\nDebugger attached.")
                return null
            }.onFailure {
                if (!retryController.shouldRetry(attempt, it)) {
                    return it
                }
                logger.debug("Debug attach attempt $attempt failed because app process is not ready, retrying.", it)
                retryController.beforeRetry(attempt, it)
                attempt++
            }
        }
    }

    private fun reportAttachFailure(reason: String, compileUiHandler: CompileUiHandler, error: Throwable? = null) {
        val message = "Jugg Debug attach failed: $reason"
        if (error == null) {
            logger.warn(message)
        } else {
            logger.warn(message, error)
        }
        compileUiHandler.onDeployUiMessage(message)
        compileUiHandler.notifyByBalloon(message)
        compileUiHandler.showRunWindow()
    }
}

/**
 * Controls retry timing for attaching the Java debugger while Android Studio discovers the started app process.
 */
interface IDebugAttachRetryController {
    fun shouldRetry(attempt: Int, error: Throwable): Boolean
    fun beforeRetry(attempt: Int, error: Throwable)
}

class DebugAttachRetryController(
    private val maxAttempts: Int = 20,
    private val retryDelayMs: Long = 250,
) : IDebugAttachRetryController {

    override fun shouldRetry(attempt: Int, error: Throwable): Boolean {
        return attempt < maxAttempts && isAppProcessNotReady(error)
    }

    override fun beforeRetry(attempt: Int, error: Throwable) {
        Thread.sleep(retryDelayMs)
    }

    private fun isAppProcessNotReady(error: Throwable): Boolean {
        return error.message?.contains("App process not found") == true
    }
}
