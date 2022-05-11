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
 * Manage device list，application state
 */
interface IDeployTargetManager {

    fun runFullBuildAndLaunch()

    fun getApks(): List<ApkInfo>

    fun getDevice(): IDevice

    fun restartApp()
}