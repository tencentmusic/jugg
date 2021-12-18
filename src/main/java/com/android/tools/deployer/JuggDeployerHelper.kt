package com.android.tools.deployer

import com.android.ddmlib.IDevice
import com.android.tools.idea.gradle.util.DynamicAppUtils
import com.android.tools.idea.run.*
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.editor.DeployTargetState
import com.android.tools.idea.run.tasks.JuggApplyChangesTask
import com.android.tools.idea.run.tasks.JuggApplyCodeChangesTask
import com.google.common.collect.ImmutableList
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.ide.JuggSettings
import org.jetbrains.android.download.AndroidProfilerDownloader
import org.jetbrains.android.facet.AndroidFacet
import java.io.File
import java.util.stream.Collectors

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper {

    var installPathProvider: Computable<String> = Computable<String> {
        findEmbeddedInstaller()
    }

    fun runTask(data: JuggDeployData, project: Project) {
        val packages = data.apks.associate {
                // com.android.tools.idea.run.LaunchTaskRunner.run
                // Add packages to the deployment, filtering out any dynamic features that are disabled.
                val disabledFeatures = emptyList<String>()
                it.applicationId to getFilteredFeatures(it, disabledFeatures)
            }
        val task = if (JuggSettings.restartActivity) {
            JuggApplyChangesTask(project, packages, true, installPathProvider, data)
        } else {
            JuggApplyCodeChangesTask(project, packages, true, installPathProvider, data)
        }
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val device = getIDevice(project)
        val launchStatus = MockLaunchStatus()

        // TODO ConsolePrinter
        val consolePrinter = MockConsolePrinter(project)
        // TODO try ExecutionManager
        val launchResult = task.run(executor, device, launchStatus, consolePrinter)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }
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

    private fun getFilteredFeatures(apkInfo: ApkInfo, disabledFeatures: List<String>): List<File> {
        return if (apkInfo.files.size > 1) {
            apkInfo.files.stream()
                .filter { feature: ApkFileUnit? ->
                    DynamicAppUtils.isFeatureEnabled(
                        disabledFeatures,
                        feature!!
                    )
                }
                .map { file: ApkFileUnit -> file.apkFile }
                .collect(Collectors.toList())
        } else {
            ImmutableList.of(apkInfo.file)
        }
    }

    private val isDebugging = true // ??
    private val deployTargetContext = DeployTargetContext()
    private fun supportMultipleDevices() = false

    // private in Android Studio 4.1.2，so I copied it out
    private fun findEmbeddedInstaller(): String? {
        val path = "plugins/android/resources/installer"
        val file = File(PathManager.getHomePath(), path)
        if (file.exists()) {
            return file.absolutePath
        }
        AndroidProfilerDownloader.getInstance().makeSureComponentIsInPlace()
        val dir = AndroidProfilerDownloader.getInstance().getHostDir(path)
        return if (dir.exists()) {
            dir.absolutePath
        } else File(
            PathManager.getHomePath(),
            "../../bazel-genfiles/tools/base/deploy/installer/android-installer"
        ).absolutePath
        // Development mode
    }

}