package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.utils.ILogger
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import kotlin.math.min

object AsDeployerCompat : IAsDeployerCompat {

    private lateinit var impl : IAsDeployerCompat

    /**
     * Must order DESC
     */
    private val compatImplList = listOf(
        CompatImpl(
            IdeVersion("Android Studio Chipmunk", "IA", "212.5712.43"),
            lazy { ChipmunkAsDeployerCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio 4.1.2", "IA", "211.7442.40"),
            lazy { V41AsDeployerCompat() },
        ),
    )

    fun init(logger: Logger) {
        val ideVersion = IdeVersion(ApplicationInfo.getInstance())
        logger.info("IDE version: $ideVersion")

        var impl: IAsDeployerCompat? = compatImplList.firstNotNullResult { compatImpl ->
            if (compatImpl.ideVersion == ideVersion) {
                logger.debug("Good! Fully matched deploy version of ${compatImpl.ideVersion}")
                return@firstNotNullResult compatImpl.impl.value
            } else if (compatImpl.ideVersion < ideVersion) {
                logger.warn("Bad! IDE version higher than ${compatImpl.ideVersion}, use this for compat, good luck.")
                return@firstNotNullResult compatImpl.impl.value
            }
            return@firstNotNullResult null
        }
        if (impl == null) {
            val compatImpl = compatImplList.last()
            impl = compatImpl.impl.value
            logger.warn("Bad! Deploy version lower than ${compatImpl.ideVersion}, use this for compat, good luck.")
            V41AsDeployerCompat()
        }
        this.impl = impl
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


private class CompatImpl(
    val ideVersion: IdeVersion,
    val impl: Lazy<IAsDeployerCompat>,
)

private class IdeVersion(
    /**
     * e.g.
     * Android Studio Chipmunk | 2021.2.1 Patch 1
     */
    val name: String,
    /**
     * e.g.
     * Intellij Idea Community -> IC
     * Android Studio -> IA
     */
    val type: String,
    /**
     * e.g.
     * 212.5712.43
     */
    val mainVersion: String,
    /**
     * e.g.
     * 212.5712.43.2112.8609683
     */
    val fullVersion: String? = null,
) {

    constructor(applicationInfo: ApplicationInfo) : this (
        applicationInfo.fullApplicationName,
        applicationInfo.build.productCode,
        applicationInfo.apiVersion.substringAfter('-'),
        applicationInfo.build.asStringWithoutProductCodeAndSnapshot()
    )

    operator fun compareTo(version: IdeVersion): Int {
        val a = mainVersion.split('.')
        val b = version.mainVersion.split('.')
        val length = min(a.size, b.size)
        for (i in 0 until length) {
            val aI = a[i].toInt()
            val bI = b[i].toInt()
            if (aI != bI) return aI - bI
        }

        if (a.size == b.size) {
            return 0
        }
        return a.size - b.size
    }

    override fun equals(other: Any?): Boolean {
        if (other is IdeVersion) {
            return compareTo(other) == 0
        }
        return super.equals(other)
    }

    override fun toString(): String {
        return "$name($mainVersion)"
    }
}