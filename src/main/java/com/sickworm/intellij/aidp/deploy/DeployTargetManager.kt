package com.sickworm.intellij.aidp.deploy

import com.android.tools.deployer.AidpDeployerHelper
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.run.ApkInfo
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.sickworm.intellij.aidp.AidpLogger
import org.jetbrains.android.facet.AndroidFacet
import java.io.File

class DeployTargetManager(
    private val project: Project,
    private val toolWindow: ToolWindow,
) {
    private val logger = AidpLogger.getInstance(project, "#AIDP-DeployTargetManager")

    private var apkFiles: List<ApkInfo> = emptyList()

    fun canDeploy(): Boolean {
        if (apkFiles.isNotEmpty()) {
            return true
        }

        val (_, runConfig) = getRunConfig()
        apkFiles = getApks(runConfig)
        return apkFiles.isNotEmpty()
    }

    fun getRunConfig(): Pair<RunnerAndConfigurationSettings, AndroidRunConfiguration> {
        val runConfigs = RunManager.getInstance(project).getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
        val runConfig = runConfigs[0]
        return runConfig to runConfigs[0].configuration as AndroidRunConfiguration
    }

    fun runNormalBuild() {
        val (runConfigAndSettings, _) = getRunConfig()
        ProgramRunnerUtil.executeConfiguration(runConfigAndSettings, DefaultRunExecutor.getRunExecutorInstance())
    }

    fun getApks(runConfig: AndroidRunConfiguration): List<ApkInfo> {
        // get apk
        val module: Module = runConfig.configurationModule.module!!
        val facet: AndroidFacet = AndroidFacet.getInstance(module)!!
        val targetDeviceSpec = null
        val apkProvider = facet.getModuleSystem().getApkProvider(runConfig, targetDeviceSpec)!!
        val device = AidpDeployerHelper.getIDevice(project)
        return try {
            apkProvider.getApks(device).toList()
        } catch (e: Exception) {
            logger.debug("getApks failed", e)
            emptyList()
        }
    }
}