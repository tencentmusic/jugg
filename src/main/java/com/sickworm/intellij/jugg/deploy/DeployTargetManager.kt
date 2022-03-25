package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.activity.DefaultApkActivityLocator
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
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.TimeUnit
import kotlin.jvm.Throws

/**
 * Manage config，deivce，application
 */
class DeployTargetManager(
    private val project: Project,
    private val deviceGetter: IDeviceGetter = DeviceGetter(project) // for mock
) {
    private val logger = JuggLogger.getInstance(project, "#Jugg-DeployTargetManager")

    fun runNormalBuild() {
        val (runConfigAndSettings, _) = getRunConfig()
        ApplicationManager.getApplication().invokeAndWait {
            ProgramRunnerUtil.executeConfiguration(runConfigAndSettings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    fun getApks(): List<ApkInfo> {
        try {
            val apkProvider = getApkProvider()
            val device = getDevice()
            return apkProvider.getApks(device).toList()
        } catch (e: Exception) {
            logger.error("getApks failed", e)
            throw e
        }
    }

    fun getDevice(): IDevice {
        try {
            return deviceGetter.getDevice()
        } catch (e: Exception) {
            logger.error("getDevice failed", e)
            throw e
        }
    }

    fun restartApp() {
        try {
            val packageName = getPackageName()
            stopApp(packageName)
            startApp(packageName)
        } catch (e: Exception) {
            logger.error("restartApp failed", e)
            throw e
        }
    }

    private fun stopApp(packageName: String) {
        Runtime.getRuntime()
            .exec("adb shell am force-stop $packageName")
            .waitFor()
    }

    private fun startApp(packageName: String) {
        val receiver = object : MultiLineReceiver() {
            override fun isCancelled(): Boolean {
                return false
            }

            override fun processNewLines(lines: Array<out String>?) {
                lines?.forEach {
                    logger.debug("[start activity]: $it")
                }
            }
        }

        val launchedActivity = getDefaultActivity()
        val command = "am start -S -n $packageName/$launchedActivity"
        @Suppress("DEPRECATION")
        AdbHelper.executeRemoteCommand(
            AndroidDebugBridge.getSocketAddress(),
            command,
            getDevice(),
            receiver,
            DdmPreferences.getTimeOut().toLong(),
            TimeUnit.MILLISECONDS)
    }

    fun getPackageName(): String {
        val device = getDevice()
        val apks = getApkProvider().getApks(device)
        if (apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(device)
        }
        if (apks.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        return apks.first().applicationId
    }

    @TestOnly
    fun getApkProvider(): ApkProvider {
        val (_, runConfig) = getRunConfig()
        return runConfig.getApkProvider()
    }

    private fun getRunConfig(): Pair<RunnerAndConfigurationSettings, AndroidRunConfiguration> {
        val runConfig = RunManager.getInstance(project).selectedConfiguration!!
        return runConfig to runConfig.configuration as AndroidRunConfiguration
    }

    private fun getDefaultActivity(): String {
        val apkProvider = getApkProvider()
        val locator = DefaultApkActivityLocator(apkProvider)
        val device = getDevice()
        return locator.getQualifiedActivityName(device)
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