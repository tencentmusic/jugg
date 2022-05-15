package com.android.tools.deployer

import com.android.tools.idea.gradle.util.DynamicAppUtils
import com.android.tools.idea.run.ApkFileUnit
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.tasks.JuggApplyChangesTask
import com.android.tools.idea.run.tasks.JuggApplyCodeChangesTask
import com.android.tools.idea.run.tasks.JuggDeployTask
import com.google.common.collect.ImmutableList
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.android.download.AndroidProfilerDownloader
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.stream.Collectors

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
) {

    @TestOnly
    var installPathProvider: Computable<String> = Computable<String> {
        findEmbeddedInstaller()
    }

    fun runTask(data: JuggDeployData, isInstall: Boolean = false) {
        if (data.apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(data)
        }
        val packages = data.apks.associate {
                // com.android.tools.idea.run.LaunchTaskRunner.run
                // Add packages to the deployment, filtering out any dynamic features that are disabled.
                val disabledFeatures = emptyList<String>()
                it.applicationId to getFilteredFeatures(it, disabledFeatures)
            }
        val task = when {
            isInstall -> {
                // default install argument has: -t -r --full --dont-kill
                JuggDeployTask(project, packages, "", true, installPathProvider, data)
            }
            JuggSettings.restartActivity -> {
                JuggApplyChangesTask(project, packages, true, installPathProvider, data)
            }
            else -> {
                JuggApplyCodeChangesTask(project, packages, true, installPathProvider, data)
            }
        }

        val launchStatus = MockLaunchStatus()

        // TODO ConsolePrinter
        val consolePrinter = MockConsolePrinter(project)
        // TODO try ExecutionManager
        val device = deployTargetManager.getDevice()
        val launchResult = task.run(executor, device, launchStatus, consolePrinter)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        if (data.isNeedRestartApp || isInstall) {
            deployTargetManager.restartApp()
        }
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

