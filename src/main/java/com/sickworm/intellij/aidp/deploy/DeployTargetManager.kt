package com.sickworm.intellij.aidp.deploy

import com.android.tools.deployer.MockExecutor
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import java.io.File

class DeployTargetManager(
    private val project: Project,
    private val toolWindow: ToolWindow,
) {

    private var apkFile: File? = null

    val hasApk: Boolean = apkFile?.exists() == true

    fun runNormalBuild() {
        val runConfigs = RunManager.getInstance(project).getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
        val runConfig = runConfigs[0]
        ProgramRunnerUtil.executeConfiguration(runConfig, DefaultRunExecutor())
    }
}