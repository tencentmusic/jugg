package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.android.tools.deployer.*
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.android.tools.deployer.model.ApkParser
import com.android.tools.deployer.model.App
import com.android.tools.idea.execution.common.DeployableToDevice
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import java.nio.file.Path

/**
 * Android Studio Giraffe
 */
open class IguanaAsDeployerCompat: HedgehogAsDeployerCompat() {

    override fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        val studioLogger = toStudioLogger(logger)
        val adb = createLegacyAdbClient(toStudioDevice(device), studioLogger)
        val apkInstaller = ApkInstaller(adb, session.toLegacyUiService(), session.rawInstaller as Installer, studioLogger)
        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        return apkInstaller.install(app, createInstallOptions(device, packageName), installMode.toLegacyInstallMode(), metrics.deployMetrics)
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return ApkParser.parsePaths(paths).map(::toJuggApk)
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        runConfiguration.putUserData(DeployableToDevice.KEY, true)
    }
}
