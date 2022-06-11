package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.utils.ILogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.android.download.AndroidComponentDownloader
import java.lang.IllegalStateException

object AsDeployerCompat : IAsDeployerCompat {

    private lateinit var impl : IAsDeployerCompat

    fun init(logger: Logger) {
        val deployVersion = getIdeDeployVersion()
        logger.info("Get deploy sdk version in IDE: $deployVersion")
        val v412 = V41AsDeployerCompat.deployVersion
        val chipmunk = ChipmunkAsDeployerCompat.deployVersion

        impl = when {
            deployVersion == v412 -> {
                logger.debug("Good! Fully matched deploy version of Android Studio 4.1.2")
                V41AsDeployerCompat()
            }
            deployVersion == chipmunk -> {
                logger.debug("Good! Fully matched deploy version of Fully match Android Studio Chipmunk")
                ChipmunkAsDeployerCompat()
            }
            deployVersion < chipmunk -> {
                logger.warn("Bad! Deploy version lower than Android Studio Chipmunk, use 4.1.2 API for compat, good luck.")
                V41AsDeployerCompat()
            }
            deployVersion > chipmunk -> {
                logger.warn("Bad! Deploy version higher than Android Studio Chipmunk, use Chipmunk API for compat, good luck.")
                ChipmunkAsDeployerCompat()
            }
            else -> {
                throw IllegalStateException("Won't reach here in logic.")
            }
        }
    }

    /** get version of android deployer, e.g. "27.2.0.0" . */
    private fun getIdeDeployVersion(): ComparableVersion {
        val obj = object : AndroidComponentDownloader() {

            override fun getArtifactName(): String {
                return ""
            }

            fun versionPublic(): String {
                return super.getVersion()
            }
        }
        return ComparableVersion(obj.versionPublic())
    }

    override fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider {
        return impl.getApkProvider(project, config)
    }

    override fun getDevices(project: Project): List<IDevice>? {
        return impl.getDevices(project)
    }

    override fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller {
        return impl.getInstaller(installersFolder, adb, logger)
    }

    override fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: Deployer.InstallMode
    ): Boolean {
        return impl.install(adb, service, installer, logger, packageName, apks, options, installMode)
    }

    override fun makeDebuggerRedefiners(
        project: Project,
        device: IDevice,
        fallback: Boolean
    ): Map<Int, ClassRedefiner> {
        return impl.makeDebuggerRedefiners(project, device, fallback)
    }

    override fun optimisticSwap(
        installer: Installer,
        redefiners: Map<Int, ClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: OptimisticApkSwapper.OverlayUpdate,
        adb: AdbClient,
        logger: ILogger
    ): OverlayId {
        return impl.optimisticSwap(installer, redefiners, packageName, argRestart, pids, arch, overlayUpdate, adb, logger)
    }
}
