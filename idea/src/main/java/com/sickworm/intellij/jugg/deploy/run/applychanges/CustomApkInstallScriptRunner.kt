package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOffline
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import java.io.File

/**
 * Runs a user-configured app APK install script, then verifies the package is installed.
 */
class CustomApkInstallScriptRunner(
    private val project: Project,
    private val script: String,
    private val launchContext: LaunchContext,
    private val logger: Logger,
) {

    private val cmdExecutor = CmdExecutor(logger, object : IGradleCompileClient.TerminalOutputListener {
        override fun onOutput(line: String, isNeedPrint: Boolean) {
            launchContext.compileUiHandler.onDeployUiMessage(line)
        }

        override fun onOutputErr(line: String) {
            launchContext.compileUiHandler.onDeployUiMessage(line)
        }
    })

    fun run(applicationId: String) {
        val projectDir = project.basePath?.let(::File)
            ?: throw IllegalStateException("Project directory is unavailable for custom APK install script.")
        val env = withAndroidSdkPlatformTools(LocalGradleCompileClient.buildCompileEnv(project, logger))
        launchContext.compileUiHandler.updateIndicatorText("Running custom APK install script...")
        cmdExecutor.prepareForInvoke()
        launchContext.compileUiHandler.listenCancelAction(cmdExecutor::release)
        if (launchContext.compileUiHandler.isCanceled) {
            cmdExecutor.release()
            launchContext.compileUiHandler.listenCancelAction(null)
            throw IllegalStateException("Custom APK install script canceled.")
        }
        logger.info("Custom APK install script started for $applicationId.")
        val result = try {
            cmdExecutor.invoke(SimpleSshCommand(script, isSecureCommand = true), env, null, projectDir)
        } finally {
            launchContext.compileUiHandler.listenCancelAction(null)
        }
        if (launchContext.compileUiHandler.isCanceled) {
            throw IllegalStateException("Custom APK install script canceled.")
        }
        if (result != 0) {
            throw IllegalStateException("Custom APK install script failed with exit code $result.")
        }
        val isDeviceReady = launchContext.deviceAdb.isAdbTransportReady() ||
            AdbTransientOffline.waitForAdbTransport("custom APK install script", launchContext.deviceAdb) {
                logger.info(it)
            }
        if (!isDeviceReady) {
            throw IllegalStateException("Device ${launchContext.deviceAdb.serial} is unavailable after custom APK install script.")
        }
        if (!AdbCmdHelper(launchContext.deviceAdb, logger).isAppInstalled(applicationId)) {
            throw IllegalStateException("Custom APK install script finished but $applicationId is not installed.")
        }
        logger.info("Custom APK install script finished for $applicationId.")
    }

    companion object {
        internal fun withAndroidSdkPlatformTools(environment: List<String>): List<String> {
            val values = environment.associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
            val androidHome = values["ANDROID_HOME"] ?: values["ANDROID_SDK_ROOT"] ?: return environment
            val platformTools = File(androidHome, "platform-tools").takeIf(File::isDirectory) ?: return environment
            val path = values["PATH"].orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
            if (platformTools.path in path) return environment
            values["PATH"] = (listOf(platformTools.path) + path).joinToString(File.pathSeparator)
            return values.map { (key, value) -> "$key=$value" }
        }
    }
}

internal class CustomApkInstallScriptException(message: String, cause: Throwable) : RuntimeException(message, cause)
