package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.api.IDevice

/** Defines the active host operations used by the shared deploy lifecycle. */
interface IDeployHost {
    val applyChangesExecutor: IApplyChangesExecutor
    val isDirectOverlayEnabled: Boolean

    fun installersRoot(): String
    fun createDeployDebugger(applyChangesExecutor: IApplyChangesExecutor): IDeployDebugger
    fun createDeviceAdb(device: IDevice, logger: Logger): IDeviceAdb
    fun confirmDeployPrompt(message: String, uiHandler: CompileUiHandler, logger: Logger): Boolean
    fun onDeployMessage(message: String, uiHandler: CompileUiHandler)
    fun notify(message: String)
    fun clearAppData(device: IDevice, packageName: String, logger: Logger)
    fun hasRelaunchActivityIssues(adb: IDeviceAdb, logger: Logger): Boolean
    fun launchAndroidTest(
        request: JuggDeployRunTaskRequest, data: JuggDeployData, launchResult: LaunchResult,
    ): LaunchResult?
    fun onDeployTimeout(device: IDevice, logger: Logger)
    fun uninstall(device: IDevice, packageName: String, logger: Logger)
}
