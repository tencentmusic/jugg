package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.*
import com.android.tools.idea.execution.common.applychanges.BaseAction
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.deployable.Deployable
import com.android.tools.idea.run.deployable.DeployableProvider
import com.android.tools.idea.run.editor.DeployTargetContext
import com.android.tools.idea.run.tasks.AbstractDeployTask
import com.android.utils.ILogger
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project
import java.lang.reflect.Field
import java.util.concurrent.ExecutionException

/**
 * Android Studio Giraffe
 */
class GiraffeAsDeployerCompat : ChipmunkAsDeployerCompat() {
    override fun getDevices(project: Project): List<IDevice>? {
        val deployTargetContext = DeployTargetContext()
        val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)

        // find the first available devices
        val deviceFutures = deployTarget.getDevices(project) ?: return null
        val devices = deviceFutures.ifReady
        if (!devices.isNullOrEmpty()) {
            return devices
        }

        return null
    }

    override fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: Deployer.InstallMode,
    ): Boolean {
        val apkInstaller = ApkInstaller(adb, service, installer, logger)
        return apkInstaller.install(packageName, apks, options, installMode, metrics.deployMetrics)
    }

    override fun toApkProvider(apkInfos: List<ApkInfo>): ApkProvider {
        return ApkProvider { apkInfos.toMutableList() }
    }

    override fun getDisableMessage(project: Project): String? {
        val disableMessage = doGetDisableMessage(project) ?: return null
        return getToolTipField().get(disableMessage) as? String
    }

    private var toolTipField: Field? = null

    private fun getToolTipField(): Field {
        toolTipField?.let { return it }
        val toolTipField = BaseAction.DisableMessage::class.java.getDeclaredField("myTooltip")
        toolTipField.isAccessible = true
        this.toolTipField = toolTipField
        return toolTipField
    }

    /**
     * @see [BaseAction.getDisableMessage]
     */
    private fun doGetDisableMessage(project: Project): BaseAction.DisableMessage? {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return BaseAction.DisableMessage(
            BaseAction.DisableMessage.DisableMode.INVISIBLE, "no available supported configuration",
            "all configuration is not supported"
        )

        val deployableProvider = DeployableProvider.getInstance(project)
            ?: return BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED, "no deployment provider",
                "there is no deployment provider specified"
            )
        val deployable: Deployable?
        try {
            deployable = deployableProvider.getDeployable(selectedRunConfig)
            if (deployable == null) {
                return BaseAction.DisableMessage(
                    BaseAction.DisableMessage.DisableMode.DISABLED,
                    "selected device is invalid",
                    "the selected device is not valid"
                )
            }
            if (!deployable.isOnline) {
                if (deployable.isUnauthorized) {
                    return BaseAction.DisableMessage(
                        BaseAction.DisableMessage.DisableMode.DISABLED, "device not authorized",
                        "the selected device is not authorized"
                    )
                } else {
                    return BaseAction.DisableMessage(
                        BaseAction.DisableMessage.DisableMode.DISABLED,
                        "device not connected",
                        "the selected device is not connected"
                    )
                }
            }
            val versionFuture = deployable.versionAsync
            if (!versionFuture.isDone) {
                // Don't stall the EDT - if the Future isn't ready, just return false.
                return BaseAction.DisableMessage(
                    BaseAction.DisableMessage.DisableMode.DISABLED,
                    "unknown device API level",
                    "its API level is currently unknown"
                )
            }
            if (versionFuture.get().apiLevel < AbstractDeployTask.MIN_API_VERSION) {
                return BaseAction.DisableMessage(
                    BaseAction.DisableMessage.DisableMode.DISABLED, "incompatible device API level",
                    "its API level is lower than 26"
                )
            }
            if (deployable.searchClientsForPackage().isEmpty()) {
                return BaseAction.DisableMessage(
                    BaseAction.DisableMessage.DisableMode.DISABLED, "app not detected",
                    "the app is not yet running or not debuggable"
                )
            }
        } catch (ex: InterruptedException) {
            return BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED,
                "update interrupted",
                "its status update was interrupted"
            )
        } catch (ex: ExecutionException) {
            return BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED, "unknown device API level",
                "its API level could not be determined"
            )
        } catch (ex: Exception) {
            return BaseAction.DisableMessage(
                BaseAction.DisableMessage.DisableMode.DISABLED, "unexpected exception",
                "an unexpected exception was thrown: $ex"
            )
        }
        return null
    }

    private fun isApplyChangesRelevant(runConfiguration: RunConfiguration): Boolean {
        if (runConfiguration is RunConfigurationBase<*>) {
            return runConfiguration.putUserDataIfAbsent(
                BaseAction.SHOW_APPLY_CHANGES_UI,
                false
            ) // This is needed to prevent a NPE if the boolean isn't set.
        }
        return false
    }

}
