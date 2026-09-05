package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.SliceDeployHelper
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.run.IDeployDebugger
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import com.sickworm.intellij.jugg.deploy.run.IApplyChangesExecutor
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployRunTaskRequest
import com.sickworm.intellij.jugg.deploy.run.LaunchResult
import com.sickworm.intellij.jugg.deploy.run.NoDeployDebugger
import com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutor
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeployerResources
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeviceManager
import java.io.File

/** Connects shared deployment to standalone logging and ddmlib devices. */
class StandaloneDeployEnvironment(
    private val deviceManager: StandaloneDeviceManager, private val logger: Logger,
) : IDeployHost {
    override val applyChangesExecutor: IApplyChangesExecutor = StandaloneApplyChangesExecutor()
    override val isDirectOverlayEnabled: Boolean = false
    private val installersRoot = StandaloneDeployerResources.prepare().directory.resolve("installer").path

    override fun installersRoot(): String = installersRoot
    override fun createDeployDebugger(applyChangesExecutor: IApplyChangesExecutor): IDeployDebugger = NoDeployDebugger
    override fun createDeviceAdb(device: IDevice, logger: Logger): IDeviceAdb {
        return StandaloneDeviceAdb(deviceManager.deviceOperations(device), logger)
    }

    override fun confirmDeployPrompt(message: String, uiHandler: CompileUiHandler, logger: Logger): Boolean {
        if (uiHandler.shouldAutoConfirmDeployPrompt(message)) return true
        logger.info("Standalone deploy prompt requires explicit confirmation: $message")
        return false
    }

    override fun onDeployMessage(message: String, uiHandler: CompileUiHandler) {
        uiHandler.onDeployUiMessage(message)
        logger.info(message)
    }

    override fun notify(message: String) = logger.info(message)
    override fun clearAppData(device: IDevice, packageName: String, logger: Logger) {
        val output = createDeviceAdb(device, logger).execAdbShellCmd("pm clear $packageName")
        checkPackageCommand("clear app data", packageName, output)
    }

    override fun hasRelaunchActivityIssues(adb: IDeviceAdb, logger: Logger): Boolean = adb.api >= 35
    override fun launchAndroidTest(
        request: JuggDeployRunTaskRequest, data: JuggDeployData, launchResult: LaunchResult,
    ): LaunchResult? = null
    override fun onDeployTimeout(device: IDevice, logger: Logger) = SliceDeployHelper(logger).onTimeout(createDeviceAdb(device, logger))
    override fun uninstall(device: IDevice, packageName: String, logger: Logger) {
        val output = createDeviceAdb(device, logger).execAdbShellCmd("pm uninstall $packageName")
        checkPackageCommand("uninstall", packageName, output)
    }

    private fun checkPackageCommand(action: String, packageName: String, output: String) {
        check(output.lineSequence().any { it.trim() == "Success" }) {
            "Standalone $action failed for $packageName: ${output.ifBlank { "empty adb response" }}"
        }
    }

    private class StandaloneDeviceAdb(
        private val operations: StandaloneDeviceManager.StandaloneDeviceOperations, private val logger: Logger,
    ) : IDeviceAdb {
        override val displayName: String get() = operations.displayName
        override val api: Int get() = operations.api
        override val serial: String get() = operations.serial
        override val isOnline: Boolean get() = operations.isOnline
        override fun execAdbShellCmd(cmd: String): String = operations.shell(cmd)
        override fun execAdbShellScript(cmd: String): String = operations.shellScript(cmd)

        override fun execAdbShellCmdStreaming(
            cmd: String, lineConsumer: (String) -> Unit, cancelSignal: () -> Boolean,
        ): Int {
            return try {
                operations.shellStreaming(cmd, lineConsumer, cancelSignal)
                0
            } catch (e: Exception) {
                logger.warn("adb streaming failed: $cmd", e)
                -1
            }
        }

        override fun push(from: File, to: String): Boolean = runCatching {
            operations.push(from, to)
            true
        }.onFailure { logger.warn("adb push failed, from: $from, to: $to", it) }.getOrDefault(false)

        override fun pull(from: String, to: File): Boolean = runCatching {
            operations.pull(from, to)
            true
        }.onFailure { logger.warn("adb pull failed, from: $from, to: $to", it) }.getOrDefault(false)

        override fun getDefaultLaunchActivity(apkFile: File): String? = ApkReader(apkFile, logger).getDefaultActivity()
        override fun getArch(packageName: String): String = operations.deployArch(packageName).name
        override fun getDeployArch(packageName: String): Deploy.Arch = operations.deployArch(packageName)
        override fun getProperty(name: String): String? = operations.getProperty(name)
    }
}
