package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.*
import com.android.tools.deployer.model.App
import com.android.utils.ILogger
import com.android.tools.deployer.model.DeploymentPlan
import java.nio.file.Path

open class NarwhalAsDeployerFeatureCompat: MeerkatAsDeployerCompat() {

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

        // only deployerOption.maxDeltaInstallPatchSize is read. if maxDeltaInstallPatchSize reach limits
        // then "Falling back to standard full install"
        val deployOptions = DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build()

        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        val deploymentPlan = DeploymentPlan(adb.device, app)
        return apkInstaller.install(deploymentPlan, deployOptions, options, installMode, metrics.deployMetrics)
    }

    fun test(): String {
        return "NarwhalAsDeployerCompat test"
    }
}
