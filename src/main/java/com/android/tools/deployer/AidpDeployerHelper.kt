package com.android.tools.deployer

import com.android.ddmlib.IDevice
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.util.EmbeddedDistributionPaths
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.*
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.tools.idea.run.tasks.AidpApplyChangesTask
import com.android.tools.idea.run.tasks.AidpApplyCodeChangesTask
import com.google.common.base.Stopwatch
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.wm.ToolWindow
import com.sickworm.intellij.aidp.AidpLogger
import org.jetbrains.android.facet.AndroidFacet
import java.io.File
import java.util.*

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
object AidpDeployerHelper {

    var installPathProvider: Computable<String> = Computable<String> {
        EmbeddedDistributionPaths.getInstance().findEmbeddedInstaller()
    }

    fun runTask(data: AidpDeployData, project: Project, toolWindow: ToolWindow) {
        // TODO read apk
        val packages = mapOf("com.example.myapplication" to listOf(File("F:\\StudioProjects\\MyApplicationIntellij\\app\\build\\outputs\\apk\\debug\\app-debug.apk")))
        val task = AidpApplyChangesTask(project, packages, true, installPathProvider, data)
        val executor = MockExecutor(toolWindow)
        val device = getIDevice(project)
        val launchStatus = MockLaunchStatus()
        val consolePrinter = MockConsolePrinter(project)
        task.run(executor, device, launchStatus, consolePrinter)
    }

    fun getIDevice(project: Project): IDevice {
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
        val deployTargetState: DeployTargetState = deployTargetContext.currentDeployTargetState
//        val module = JavaRunConfigurationModule(project, false)
//        module.setModuleName("app") // TODO read from project
        val module = ModuleManager.getInstance(project).modules.first { it.name.contains("app") }
        val facet = AndroidFacet.getInstance(module!!)!!

        val deviceFutures =
            deployTarget.getDevices(deployTargetState, facet, getDeviceCount(isDebugging), isDebugging, hashCode())
                ?: throw IllegalStateException("no device futures")

        // got ClassCastException if use DeviceFutures.get() for different ClassLoader
        val devices = deviceFutures.ifReady
        if (devices.isNullOrEmpty()) {
            throw IllegalStateException("no devices")
        }

        return devices[0]
    }

    fun getConfiguration(project: Project): AndroidRunConfiguration {
        val factory = AndroidRunConfigurationType.getInstance().factory
        return factory.createTemplateConfiguration(project) as AndroidRunConfiguration
    }

    private fun getDeviceCount(debug: Boolean): DeviceCount {
        return DeviceCount.fromBoolean(supportMultipleDevices() && !debug)
    }

    private val isDebugging = true // ??
    private val deployTargetContext = DeployTargetContext()
    private fun supportMultipleDevices() = false
}