package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.IdeService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdbClient
import com.sickworm.intellij.jugg.deploy.SliceDeployHelper
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestApkSelector
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModel
import com.sickworm.intellij.jugg.deploy.run.instrument.TestLauncher
import com.sickworm.intellij.jugg.deploy.run.utils.CopyEmbeddedDistributionPaths
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

/** Connects shared deployment to Android Studio services and UI. */
class IdeaDeployEnvironment(
    private val project: Project, override val applyChangesExecutor: IAsDeployerCompat,
    private val compileContextManager: CompileContextManager,
) : IDeployHost {
    private val logger = JuggLogger.getInstance(project, "IdeaDeployEnvironment")
    override val isDirectOverlayEnabled: Boolean get() = JuggSettings.isEnableDirectOverlayDeploy

    override fun installersRoot(): String = CopyEmbeddedDistributionPaths().get()

    override fun createDeployDebugger(applyChangesExecutor: IApplyChangesExecutor): IDeployDebugger {
        val compat = applyChangesExecutor as? IAsDeployerCompat
            ?: throw IllegalArgumentException("IDEA deploy runtime must use an Android Studio compatibility implementation")
        return IdeaDeployDebugger(project, compat)
    }

    override fun createDeviceAdb(device: IDevice, logger: Logger): IDeviceAdb = IdeaDeviceAdb(device, logger)

    override fun confirmDeployPrompt(message: String, uiHandler: CompileUiHandler, logger: Logger): Boolean {
        if (uiHandler.shouldAutoConfirmDeployPrompt(message)) {
            logger.debug("Deploy prompt auto-confirmed by compile ui handler: $message")
            return true
        }
        return IdeService(project).prompt(message)
    }

    override fun onDeployMessage(message: String, uiHandler: CompileUiHandler) {
        uiHandler.onDeployUiMessage(message)
        IdeService(project).message(message)
    }

    override fun notify(message: String) = JuggRunningTask.notifyByBalloon(project, message)

    override fun clearAppData(device: IDevice, packageName: String, logger: Logger) {
        createDeviceAdb(device, logger).execAdbShellCmd("pm clear $packageName")
    }

    override fun hasRelaunchActivityIssues(adb: IDeviceAdb, logger: Logger): Boolean {
        return PlatformApi.isHasRelaunchActivityIssues(adb, logger)
    }

    override fun launchAndroidTest(
        request: JuggDeployRunTaskRequest, data: JuggDeployData, launchResult: LaunchResult,
    ): LaunchResult? {
        val spec = request.androidTestRunSpec ?: return null
        val projectInfo = compileContextManager.getProjectInfo()
        val testApk = AndroidTestApkSelector.select(
            spec = spec,
            apks = data.apks,
            projectDir = projectInfo.modules.values.firstOrNull()?.projectRootDir
                ?: File(spec.sourcePath.orEmpty()).parentFile
                ?: File("."),
            modules = projectInfo.modules.values,
        )
        if (testApk == null) {
            logger.warn("androidTestRunSpec provided but no test APK found in deploy data; skipping test launch.")
            return null
        }
        val launcher = TestLauncher(
            devices = listOf(request.device), spec = spec, testApk = testApk,
            consoleOutput = { line -> request.compileUiHandler.onDeployUiMessage(line) }, showDeviceSuite = request.isMultipleDevices,
            testEventSinkFactory = { deviceName, isShowDeviceSuite ->
                request.compileUiHandler.testEventSinkFactory?.invoke(deviceName, isShowDeviceSuite)
            },
            cancelSignal = { request.compileUiHandler.isCanceled }, logger = logger,
            resultModel = request.androidTestResultModel ?: AndroidTestResultModel(),
            printAggregatedResult = request.isLastDevice && request.isMultipleDevices,
        )
        if (launcher.run()) return null
        logger.warn("Instrumentation test run reported failures.")
        return LaunchResult(false, 1, "Instrumentation test run reported failures.", launchResult.overlayIds)
    }

    override fun onDeployTimeout(device: IDevice, logger: Logger) {
        SliceDeployHelper(logger).onTimeout(createDeviceAdb(device, logger))
    }

    override fun uninstall(device: IDevice, packageName: String, logger: Logger) {
        val adbLogger = LogWrapper(logger).also {
            it.alwaysLogAsDebug(true)
            it.allowVerbose(true)
        }
        IdeaDeviceAdbClient(device, adbLogger).uninstall(packageName)
    }
}
