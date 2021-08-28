package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.JuggDeployerHelper
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.run.ApkInfo
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.toolWindow.JuggLogger
import org.jetbrains.android.facet.AndroidFacet

class DeployTargetManager(
    private val project: Project
) {
    private val logger = JuggLogger.getInstance(project, "#Jugg-DeployTargetManager")

    fun runNormalBuild() {
        val (runConfigAndSettings, _) = getRunConfig()
        ApplicationManager.getApplication().invokeAndWait {
            ProgramRunnerUtil.executeConfiguration(runConfigAndSettings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    fun getApks(): List<ApkInfo> {
        val (_, runConfig) = getRunConfig()
        return getApks(runConfig)
    }

    private fun getRunConfig(): Pair<RunnerAndConfigurationSettings, AndroidRunConfiguration> {
        val runConfigs = RunManager.getInstance(project).getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
        val runConfig = runConfigs[0]
        return runConfig to runConfigs[0].configuration as AndroidRunConfiguration
    }

    private fun getApks(runConfig: AndroidRunConfiguration): List<ApkInfo> {
        return try {
            val module: Module = runConfig.configurationModule.module!!
            val facet: AndroidFacet = AndroidFacet.getInstance(module)!!
            val targetDeviceSpec = null
            val apkProvider = facet.getModuleSystem().getApkProvider(runConfig, targetDeviceSpec)!!
            val device = JuggDeployerHelper.getIDevice(project)
            apkProvider.getApks(device).toList()
        } catch (e: Exception) {
            logger.warn("getApks failed", e)
            emptyList()
        }
    }
}