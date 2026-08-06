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
package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.run.IApplyChangesExecutor
import com.sickworm.intellij.jugg.deploy.run.IDeployDebugger
import com.sickworm.intellij.jugg.deploy.run.IJuggDeployerDeploymentService
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggClassRedefiner
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerException
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.deploy.run.LaunchResult
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import java.io.File

/**
 *
 * [com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper] -> [JuggDeployTask] -> [JuggDeployer]
 *
 * @see com.android.tools.idea.run.tasks.AbstractDeployTask
 * @see com.android.tools.idea.run.tasks.DeployTask
 * @see com.android.tools.idea.run.tasks.ApplyChangesTask
 * @see com.android.tools.idea.run.tasks.ApplyCodeChangesTask
 */
class JuggDeployTask(
    private val type: AndroidDeployType,
    private val data: JuggDeployData,
    private val deploymentService: IJuggDeployerDeploymentService,
    private val logger: Logger = Logger.getInstance(JuggDeployTask::class.java),
) {

    fun run(launchContext: LaunchContext): LaunchResult {
        val startTime = System.currentTimeMillis()
        val device = launchContext.device
        val logger = AdbLogWrapper(logger)
        val applyChangesExecutor = launchContext.applyChangesExecutor
        val deployDebugger = launchContext.deployDebugger

        val deployType = if (type == AndroidDeployType.INSTALL) "Install" else "Apply Changes"
        val deployer = JuggDeployer(
            launchContext = launchContext,
            deploymentService = deploymentService,
            logger = logger,
            applyChangesExecutor = applyChangesExecutor,
        )
        val idsSkippedInstall: MutableList<String> = ArrayList()
        val overlayIds = mutableMapOf<String, String>()

        // Only the deployer transport receives APK-scoped data. Lifecycle state is still committed
        // by JuggDeployerHelper with the original full JuggDeployData after the whole deploy succeeds.
        val packages = data.groupByApplicationId()

        for ((applicationId, apkInfos, scopedData) in packages) {
            try {
                launchContext.launchApp = shouldTaskLaunchApp()
                val apkFiles = apkInfos.flatMap { it.files }.map { it.apkFile }
                val decision = AndroidTestPackageDeployPolicy.decide(apkInfos, scopedData, type)
                decision.warningMessage?.let { logger.warning("%s", it) }
                if (decision.skip) {
                    logPackageScope(applicationId, apkInfos, scopedData, decision.effectiveType, logger)
                    continue
                }
                val effectiveType = decision.effectiveType
                logPackageScope(applicationId, apkInfos, scopedData, effectiveType, logger)
                val result = perform(
                    device, deployer, applicationId, apkFiles, scopedData,
                    applyChangesExecutor, deployDebugger, effectiveType,
                )
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
        val duration = System.currentTimeMillis() - startTime
        if (idsSkippedInstall.isEmpty()) {
            val content = "$deployType successfully finished in ${duration}ms."
            logger.info("%s", content)
        } else {
            val title = "$deployType successfully finished in ${duration}ms."
            val content = type.createSkippedApkInstallMessage(
                idsSkippedInstall,
                    idsSkippedInstall.size == packages.size
            )
            logger.info("%s. %s", title, content)
        }
        return LaunchResult(true, 0, null, overlayIds)
    }

    private fun shouldTaskLaunchApp() = when(type) {
        AndroidDeployType.INSTALL -> true
        AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY -> true
        AndroidDeployType.APPLY_CHANGES -> false
    }

    @Throws(JuggDeployerException::class)
    private fun perform(
        device: IDevice, deployer: JuggDeployer, applicationId: String, files: List<File>,
        scopedData: JuggDeployData, applyChangesExecutor: IApplyChangesExecutor,
        deployDebugger: IDeployDebugger, effectiveType: AndroidDeployType = type,
    ): JuggDeployer.Result {
        when (effectiveType) {
            AndroidDeployType.INSTALL -> {
                logger.debug("Installing application $applicationId...")
                val installMode = applyChangesExecutor.getInstallMode()
                return deployer.install(applicationId, getPathsToInstall(files), installMode)
            }
            AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY -> {
                logger.debug("Applying changes to application $applicationId...")
                return deployer.fullSwap(getPathsToInstall(files), scopedData)
            }
            AndroidDeployType.APPLY_CHANGES -> {
                logger.debug("Applying changes to application $applicationId...")
                val fastRerunOnSwapFailure = false

                var debuggerRedefiners = emptyMap<Int, JuggClassRedefiner>()
                if (!scopedData.isNeedRestartApp && scopedData.hasClassChanges) {
                    // reduce chance of error "R+ Device should have FULL debugger swap support" on some devices
                    // which is occurred in: com.android.tools.deployer.OptimisticApkSwapper.optimisticSwap.
                    // because we don't need debuggerRedefiners on restart case
                    debuggerRedefiners = deployDebugger.makeDebuggerRedefiners(
                        device, fastRerunOnSwapFailure && deployer.supportsNewPipeline())
                }
                return deployer.codeSwap(getPathsToInstall(files), debuggerRedefiners, scopedData)
            }
        }
    }

    private fun logPackageScope(
        applicationId: String,
        apkInfos: List<ApkInfo>,
        scopedData: JuggDeployData,
        effectiveType: AndroidDeployType,
        logger: AdbLogWrapper,
    ) {
        val selectedApkNames = apkInfos.flatMap { it.files }.map { it.apkFile.name }
        val selectedApkPaths = apkInfos.flatMap { it.files }.map { it.apkFile.path }
        val scopedClassCount = scopedData.newClasses.size +
            scopedData.hotFixModifiedClasses.size +
            scopedData.hotReloadModifiedClasses.size
        val originalClassCount = data.newClasses.size +
            data.hotFixModifiedClasses.size +
            data.hotReloadModifiedClasses.size
        val targetSample = data.targetApkPathSample().map { File(it).name }
        logger.info(
            "Deploy package scope: applicationId=$applicationId, apkFiles=$selectedApkNames, " +
                "effectiveType=$effectiveType, scopedClasses=$scopedClassCount, " +
                "scopedOverlays=${scopedData.overlays.size}, scopedUpdateApks=${scopedData.updateApkFiles.size}, " +
                "scopedIsEmpty=${scopedData.isEmpty}, originalClasses=$originalClassCount, " +
                "originalOverlays=${data.overlays.size}, originalTargetApkSample=$targetSample",
        )
        if (scopedData.isEmpty && !data.isEmpty) {
            logger.info(
                "Deploy payload scoped out: applicationId=$applicationId, " +
                    "selectedApkPaths=$selectedApkPaths, originalTargetApkSample=${data.targetApkPathSample()}",
            )
        }
    }

    companion object {

        private fun getPathsToInstall(apkFiles: List<File>): List<String> {
            return apkFiles.map(File::getPath)
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
                            skippedApkList.joinToString(", ")
                }
            }
            APPLY_CHANGES_AND_RESTART_ACTIVITY -> {
                if (all) {
                    "Activity restarted. No code or resource changes detected."
                } else {
                    "Activity restarted without re-installing the following APK(s): " +
                            skippedApkList.joinToString(", ")
                }
            }
            APPLY_CHANGES -> {
                if (all) {
                    "No code changes detected."
                } else {
                    "No code changes detected. The ollowing APK(s) are not installed: " +
                            skippedApkList.joinToString(", ")
                }
            }
        }
    }
}
