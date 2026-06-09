/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.ClassRedefiner
import com.android.tools.idea.run.IdeService
import com.google.common.base.Stopwatch
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.ContainerUtil
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/**
 * @see com.android.tools.idea.run.tasks.AbstractDeployTask
 * @see com.android.tools.idea.run.tasks.DeployTask
 * @see com.android.tools.idea.run.tasks.ApplyChangesTask
 * @see com.android.tools.idea.run.tasks.ApplyCodeChangesTask
 */
class JuggDeployTask(
    private val project: Project,
    private val installPathProvider: Computable<String>,
    private val type: AndroidDeployType,
    private val data: JuggDeployData,
    private val deploymentService: IJuggDeployerDeploymentService = JuggDeploymentService,
    private val asDeployerCompat: IAsDeployerCompat = AsDeployerCompat,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployTask"),
) {

    fun run(launchContext: LaunchContext): LaunchResult {
        val stopwatch = Stopwatch.createStarted()
        val device = launchContext.device
        val logger = AdbLogWrapper(logger)
        val ideService = IdeService(project)
        val installSession = asDeployerCompat.createInstallSession(
            installPathProvider.compute(),
            device,
            logger,
            onPrompt = { message -> ideService.prompt(message) },
            onMessage = { message -> ideService.message(message) },
        )

        val deployType = if (type == AndroidDeployType.INSTALL) "Install" else "Apply Changes"
        val deployer = JuggDeployer(
            device = device,
            deviceAdb = launchContext.deviceAdb,
            deploymentService = deploymentService,
            installSession = installSession,
            exceptOverlayIds = launchContext.exceptOverlayIds,
            isSkipExceptOverlayCheck = launchContext.isSkipExceptOverlayCheck,
            logger = logger,
            asDeployerCompat = asDeployerCompat,
        )
        val idsSkippedInstall: MutableList<String> = ArrayList()
        val overlayIds = mutableMapOf<String, String>()

        val packages: Map<String, List<ApkInfo>> = data.apks.groupBy { it.applicationId }

        for ((applicationId, apkInfos) in packages) {
            try {
                launchContext.launchApp = shouldTaskLaunchApp()
                val apkFiles = apkInfos.flatMap { it.files }.map { it.apkFile }
                val result = perform(device, deployer, applicationId, apkFiles)
                if (result.skippedInstall) {
                    idsSkippedInstall.add(applicationId)
                }
                if (result.needsRestart) {
                    launchContext.killBeforeLaunch = true
                    launchContext.launchApp = true
                }
                overlayIds[applicationId] = result.overlayId ?: ""
            } catch (e: JuggDeployerException) {
                logger.error(e, "%s failed: %s %s", deployType, e.message, e.details)
                return LaunchResult(false, e.errorOrdinal, e.message + " " + e.details, emptyMap())
            }
        }
        stopwatch.stop()
        val duration = stopwatch.elapsed(TimeUnit.MILLISECONDS)
        if (idsSkippedInstall.isEmpty()) {
            val content =
                String.format("%s successfully finished in %s.", deployType, StringUtil.formatDuration(duration))
            logger.info("%s", content)
        } else {
            val title =
                String.format("%s successfully finished in %s.", deployType, StringUtil.formatDuration(duration))
            val content = type.createSkippedApkInstallMessage(
                idsSkippedInstall,
                idsSkippedInstall.size == packages.size
            )
            logger.info("%s. %s", title, content)
        }
        return LaunchResult(true, 0, null, overlayIds)
    }

    private fun shouldTaskLaunchApp() = when (type) {
        AndroidDeployType.INSTALL -> true
        AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY -> true
        AndroidDeployType.APPLY_CHANGES -> false
    }

    @Throws(JuggDeployerException::class)
    private fun perform(
        device: IDevice, deployer: JuggDeployer, applicationId: String, files: List<File>
    ): JuggDeployer.Result {
        when (type) {
            AndroidDeployType.INSTALL -> {
                logger.debug("Installing application $applicationId...")
                val installMode = asDeployerCompat.getInstallMode()
                return deployer.install(applicationId, getPathsToInstall(files), installMode)
            }
            AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY -> {
                logger.debug("Applying changes to application $applicationId...")
                return deployer.fullSwap(getPathsToInstall(files), data)
            }
            AndroidDeployType.APPLY_CHANGES -> {
                logger.debug("Applying changes to application $applicationId...")
                val fastRerunOnSwapFailure = false

                var debuggerRedefiners = emptyMap<Int, ClassRedefiner>()
                if (!data.isNeedRestartApp) {
                    // Reduce chance of "R+ Device should have FULL debugger swap support" on some devices.
                    debuggerRedefiners = asDeployerCompat.makeDebuggerRedefiners(
                        project, device, fastRerunOnSwapFailure && deployer.supportsNewPipeline()
                    )
                }
                return deployer.codeSwap(getPathsToInstall(files), debuggerRedefiners, data)
            }
        }
    }

    companion object {

        private fun getPathsToInstall(apkFiles: List<File>): List<String> {
            return ContainerUtil.map(apkFiles) { obj: File -> obj.path }
        }
    }

}

/**
 * @see com.android.tools.idea.run.tasks.DeployTask
 * @see com.android.tools.idea.run.tasks.ApplyChangesTask
 * @see com.android.tools.idea.run.tasks.ApplyCodeChangesTask
 */
enum class AndroidDeployType {
    INSTALL,  // install
    APPLY_CHANGES_AND_RESTART_ACTIVITY,  // apply changes and restart activity
    APPLY_CHANGES, // apply changes
    ;

    fun createSkippedApkInstallMessage(skippedApkList: List<String>, all: Boolean): String {
        return when (this) {
            INSTALL -> {
                if (all) {
                    "App restart successful without requiring a re-install."
                } else {
                    "App restart successful without re-installing the following APK(s): " +
                            skippedApkList.stream().collect(Collectors.joining(", "))
                }
            }
            APPLY_CHANGES_AND_RESTART_ACTIVITY -> {
                if (all) {
                    "Activity restarted. No code or resource changes detected."
                } else {
                    "Activity restarted without re-installing the following APK(s): " +
                            skippedApkList.stream().collect(Collectors.joining(", "))
                }
            }
            APPLY_CHANGES -> {
                if (all) {
                    "No code changes detected."
                } else {
                    "No code changes detected. The ollowing APK(s) are not installed: " +
                            skippedApkList.stream().collect(Collectors.joining(", "))
                }
            }
        }
    }
}

/**
 * @see [com.android.tools.idea.run.tasks.LaunchContext]
 */
class LaunchContext(
    val device: IDevice,
    val deviceAdb: IDeviceAdb,
    val exceptOverlayIds: Map<String, String>,
    val isSkipExceptOverlayCheck: Boolean,
) {
    var launchApp: Boolean = false
    var killBeforeLaunch: Boolean = false
}
