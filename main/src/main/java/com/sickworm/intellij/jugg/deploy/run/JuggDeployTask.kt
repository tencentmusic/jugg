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
import com.android.sdklib.AndroidVersion
import com.android.tools.deployer.AdbClient
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.DeployerException
import com.android.tools.deployer.InstallOptions
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.util.DynamicAppUtils
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.run.*
import com.google.common.base.Stopwatch
import com.google.common.collect.ImmutableList
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.ContainerUtil
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
    private val type: JuggDeployType,
    private val data: JuggDeployData,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployTask")
) {

    val packages = data.apks.associate {
        // Add packages to the deployment, filtering out any dynamic features that are disabled.
        val disabledFeatures = emptyList<String>()
        it.applicationId to getFilteredFeatures(it, disabledFeatures)
    }

    fun run(launchContext: LaunchContext): LaunchResult {
        val stopwatch = Stopwatch.createStarted()
        val logger = LogWrapper(logger).also {
            it.alwaysLogAsDebug(true)
            it.allowVerbose(true)
        }
        val device = launchContext.device
        val printer = launchContext.consolePrinter
        val adb = AdbClient(device, logger)
        val service = DeploymentService.getInstance(project)
        val ideService = IdeService(project)
        val adbInstaller = AsDeployerCompat.getInstaller(installPathProvider.compute(), adb, logger)

        val deployer = JuggDeployer(
            adb,
            service.deploymentCacheDatabase,
            service.dexDatabase,
            adbInstaller,
            ideService,
            logger
        )
        val idsSkippedInstall: MutableList<String> = ArrayList()
        for ((applicationId, apkFiles) in packages) {
            try {
                launchContext.launchApp = shouldTaskLaunchApp()
                val result = perform(device, deployer, applicationId, apkFiles)
                if (result.skippedInstall) {
                    idsSkippedInstall.add(applicationId)
                }
                if (result.needsRestart) {
                    // TODO: fall back to using the suggested action, rather than blindly rerun
                    launchContext.killBeforeLaunch = true
                    launchContext.launchApp = true
                }
            } catch (e: DeployerException) {
                logger.error(e, "%s failed: %s %s", type.description, e.message, e.details)
                return LaunchResult(false, e.error.ordinal, e.message)
            }
        }
        stopwatch.stop()
        val duration = stopwatch.elapsed(TimeUnit.MILLISECONDS)
        if (idsSkippedInstall.isEmpty()) {
            val content =
                String.format("%s successfully finished in %s.", type.description, StringUtil.formatDuration(duration))
            printer.stdout(content)
            logger.info("%s", content)
        } else {
            val title =
                String.format("%s successfully finished in %s.", type.description, StringUtil.formatDuration(duration))
            val content = type.createSkippedApkInstallMessage(
                idsSkippedInstall,
                idsSkippedInstall.size == packages.size
            )
            printer.stdout(content)
            logger.info("%s. %s", title, content)
        }
        return LaunchResult(true, 0, null)
    }

    private fun shouldTaskLaunchApp() = when(type) {
        JuggDeployType.INSTALL -> true
        JuggDeployType.HOT_FIX -> true
        JuggDeployType.HOT_RELOAD -> false
    }

    @Throws(DeployerException::class)
    private fun perform(
        device: IDevice, deployer: JuggDeployer, applicationId: String, files: List<File>
    ): JuggDeployer.Result {
        when (type) {
            JuggDeployType.INSTALL -> {
                // default install argument has: -t -r --full --dont-kill
                val options = InstallOptions.builder().setAllowDebuggable()
                // no setInstallOnCurrentUser in giraffe
//                val installOnAllUsers = true
//                if (!installOnAllUsers && device.version.isGreaterOrEqualThan(24)) {
//                    options.setInstallOnCurrentUser()
//                }
                if (device.supportsFeature(IDevice.HardwareFeature.EMBEDDED)) {
                    options.setGrantAllPermissions()
                }
                if (device.version.isGreaterOrEqualThan(28)) {
                    options.setInstallFullApk()
                }
                if (device.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.N)) {
                    options.setDontKill()
                }
                options.setSkipVerification(device, applicationId)

                logger.debug("Installing application $applicationId...")
                var installMode = InstallMode.DELTA
                if (!StudioFlags.DELTA_INSTALL.get()) {
                    installMode = InstallMode.FULL
                }

                return deployer.install(applicationId, getPathsToInstall(files), options.build(), installMode)
            }
            JuggDeployType.HOT_FIX -> {
                logger.debug("Applying changes to application $applicationId...")
                return deployer.fullSwap(getPathsToInstall(files), data)
            }
            JuggDeployType.HOT_RELOAD -> {
                logger.debug("Applying changes to application $applicationId...")
                val fastRerunOnSwapFailure = false
                val debuggerRedefiners = AsDeployerCompat.makeDebuggerRedefiners(
                    project, device, fastRerunOnSwapFailure && deployer.supportsNewPipeline()
                )
                return deployer.codeSwap(getPathsToInstall(files), debuggerRedefiners, data)
            }
        }
    }

    companion object {

        private fun getPathsToInstall(apkFiles: List<File>): List<String> {
            return ContainerUtil.map(apkFiles) { obj: File -> obj.path }
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
                ImmutableList.of(apkInfo.files.first().apkFile)
            }
        }
    }

}

/**
 * @see com.android.tools.idea.run.tasks.DeployTask
 * @see com.android.tools.idea.run.tasks.ApplyChangesTask
 * @see com.android.tools.idea.run.tasks.ApplyCodeChangesTask
 */
enum class JuggDeployType {
    INSTALL,  // install
    HOT_FIX,  // apply changes and restart activity
    HOT_RELOAD, // apply changes
    ;

    val description: String = toString()

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
            HOT_FIX -> {
                if (all) {
                    "Activity restarted. No code or resource changes detected."
                } else {
                    "Activity restarted without re-installing the following APK(s): " +
                            skippedApkList.stream().collect(Collectors.joining(", "))
                }
            }
            HOT_RELOAD -> {
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
    val consolePrinter: ConsolePrinter,
    val device: IDevice,
) {
    var launchApp: Boolean = false
    var killBeforeLaunch: Boolean = false
}

class ConsolePrinter(private val logger: Logger) {

    fun stdout(message: String) {
        logger.info(message)
    }

    fun stderr(message: String) {
        logger.error(message)
    }
}


/**
 * @see [com.android.tools.idea.run.tasks.LaunchResult]
 */
class LaunchResult(
    val success: Boolean,
    val errorId: Int,
    val consoleError: String?,
)