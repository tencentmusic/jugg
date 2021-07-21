package com.sickworm.intellij.aidp.deploy

import com.android.tools.deployer.AidpDeployerHelper
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import org.jetbrains.android.facet.AndroidFacet
import java.io.File

class DeployTargetManager(
    private val project: Project,
    private val toolWindow: ToolWindow,
) {

    private var apkFile: File? = null

    val hasApk: Boolean = apkFile?.exists() == true

    fun runNormalBuild() {
        val runConfigs = RunManager.getInstance(project).getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
        val runConfig = runConfigs[0].configuration as AndroidRunConfiguration

        // compile


        // get apk
        val module: Module = runConfig.configurationModule.module!!
        val facet: AndroidFacet = AndroidFacet.getInstance(module)!!
        val targetDeviceSpec = null
        val apkProvider = facet.getModuleSystem().getApkProvider(runConfig, targetDeviceSpec)!!
        val device = AidpDeployerHelper.getIDevice(project)
        val apkList = apkProvider.getApks(device)
        println(apkList)

        // launch
//        val executor = DefaultRunExecutor()
//        val builder = ExecutionEnvironmentBuilder.create(executor, runConfig)
//        val env = builder.dataContext(null).activeTarget().build()
//        val runState = runConfig.getState(executor, env)!!
//        runState.execute(executor, env.runner)
    }
}