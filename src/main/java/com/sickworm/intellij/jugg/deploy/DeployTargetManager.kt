package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggLogger
import org.jetbrains.android.facet.AndroidFacet
import kotlin.jvm.Throws

class DeployTargetManager(
    private val project: Project,
): IDeployTargetManager {
    private val logger = JuggLogger.getInstance(project, "#Jugg-DeployTargetManager")

    private val deviceGetter: DeviceGetter = DeviceGetter(project)

    override fun runFullBuildAndLaunch() {
        val (runConfigAndSettings, _) = getRunConfig()
        ApplicationManager.getApplication().invokeAndWait {
            ProgramRunnerUtil.executeConfiguration(runConfigAndSettings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    override fun getApks(): List<ApkInfo> {
        try {
            val apkProvider = getApkProvider()
            val device = getDevice()
            return apkProvider.getApks(device).toList()
        } catch (e: Exception) {
            logger.error("getApks failed", e)
            throw e
        }
    }

    override fun getDevice(): IDevice {
        try {
            return deviceGetter.getDevice()
        } catch (e: Exception) {
            logger.error("getDevice failed", e)
            throw e
        }
    }

    override fun restartApp() {
        try {
            AdbCmdHelper.startDefaultApp(getPackageName(), getApkProvider(), getDevice())
        } catch (e: Exception) {
            logger.error("restartApp failed", e)
            throw e
        }
    }

    private fun getPackageName(): String {
        val apks = getApks()
        if (apks.isEmpty()) {
            throw JuggInternalException.getPackageNameFailedApkNotFound()
        }
        if (apks.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        return apks.first().applicationId
    }

    private fun getApkProvider(): ApkProvider {
        val (_, runConfig) = getRunConfig()
        return runConfig.getApkProvider()
    }

    private fun getRunConfig(): Pair<RunnerAndConfigurationSettings, AndroidRunConfiguration> {
        val runConfig = RunManager.getInstance(project).selectedConfiguration!!
        return runConfig to runConfig.configuration as AndroidRunConfiguration
    }

    @Throws(Exception::class)
    private fun AndroidRunConfiguration.getApkProvider(): ApkProvider {
        val targetDeviceSpec = null
        return getFacet().getModuleSystem().getApkProvider(this, targetDeviceSpec)!!
    }

    @Throws(Exception::class)
    private fun AndroidRunConfiguration.getFacet(): AndroidFacet {
        val module: Module = configurationModule.module!!
        return AndroidFacet.getInstance(module)!!
    }
}