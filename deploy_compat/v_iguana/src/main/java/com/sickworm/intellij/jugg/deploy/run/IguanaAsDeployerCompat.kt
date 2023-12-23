package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.*
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkParser
import com.android.tools.deployer.model.App
import com.android.tools.idea.execution.common.DeployableToDevice
import com.android.utils.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import java.nio.file.Path

/**
 * Android Studio Giraffe
 */
open class IguanaAsDeployerCompat: HedgehogAsDeployerCompat() {

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
        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        return apkInstaller.install(app, options, installMode, metrics.deployMetrics)
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return ApkParser.parsePaths(paths)
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeployableToDevice.KEY, true)
    }
}
