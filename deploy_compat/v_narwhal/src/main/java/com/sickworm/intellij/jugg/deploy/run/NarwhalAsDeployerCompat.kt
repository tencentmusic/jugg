package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.android.tools.deployer.*
import com.android.tools.deployer.model.App
import com.sickworm.intellij.jugg.deploy.api.ILogger
import java.nio.file.Path

open class NarwhalAsDeployerCompat: MeerkatAsDeployerCompat() {

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

        // only deployerOption.maxDeltaInstallPatchSize is read. if maxDeltaInstallPatchSize reach limits
        // then "Falling back to standard full install"
        val deployOptions = DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build()

        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        return apkInstaller.install(
            app,
            deployOptions,
            createInstallOptions(device, packageName),
            installMode.toLegacyInstallMode(),
            metrics.deployMetrics,
        )
    }

    fun test(): String {
        return "NarwhalAsDeployerCompat test"
    }
}
