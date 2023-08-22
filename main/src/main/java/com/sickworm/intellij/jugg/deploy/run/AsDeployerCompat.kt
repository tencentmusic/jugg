package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeploymentService
import com.android.utils.ILogger
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.min

object AsDeployerCompat : IAsDeployerCompat {

    private val impl : IAsDeployerCompat = Proxy.newProxyInstance(this.javaClass.classLoader,
        arrayOf<Class<*>>(IAsDeployerCompat::class.java), object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<Any?>): Any? {
                try {
                    return method.invoke(priorityImpl.impl.value, *args)
                } catch (e: InvocationTargetException) {
                    if (!e.targetException.isCompatError) {
                        throw e.targetException
                    }

                    logger.debug("try priorityImpl with ${e.targetException::class.simpleName}, try higher version impl")

                    // try other version impl
                    compatImplList
                        .filter { it.ideVersion != priorityImpl.ideVersion }
                        .forEach {
                            try {
                                val result = method.invoke(it.impl.value, *args)
                                logger.debug("try ${it.ideVersion.name} API success, return")
                                return result
                            } catch (e: InvocationTargetException) {
                                if (!e.targetException.isCompatError) {
                                    throw e.targetException
                                }
                                logger.debug("try ${it.ideVersion.name} API with ${e.targetException::class.simpleName}")
                            }
                        }

                    logger.warn("try all impl failed")
                    throw e
                }
            }
        }) as IAsDeployerCompat

    private val Throwable.isCompatError: Boolean get() {
        return this is NoSuchMethodError
                || this is NoSuchFieldError
                || this is NoClassDefFoundError
    }

    private lateinit var priorityImpl : CompatImpl

    /**
     * Must order DESC
     */
    private val compatImplList = listOf(
        CompatImpl(
            IdeVersion("Android Studio Hedgehog", "IA", "231.9225.16"),
            lazy { HedgehogAsDeployerCompat() },
        ),
        CompatImpl(
            IdeVersion("Android Studio Giraffe", "IA", "223.8836.35"),
            lazy { GiraffeAsDeployerCompat() },
        ),
        CompatImpl(
            IdeVersion("Android Studio Chipmunk", "IA", "212.5712.43"),
            lazy { ChipmunkAsDeployerCompat() }
        ),
    )

    private lateinit var logger: Logger

    val ideVersion = IdeVersion(ApplicationInfo.getInstance())

    fun init(logger: Logger) {
        this.logger = logger

        logger.debug("IDE version: $ideVersion")

        var impl: CompatImpl? = compatImplList.firstNotNullOfOrNull { compatImpl ->
            if (compatImpl.ideVersion == ideVersion) {
                logger.debug("Good! Fully matched deploy version of ${compatImpl.ideVersion}")
                return@firstNotNullOfOrNull compatImpl
            } else if (compatImpl.ideVersion < ideVersion) {
                logger.warn("Bad! IDE version higher than ${compatImpl.ideVersion}, use this for compat, good luck.")
                return@firstNotNullOfOrNull compatImpl
            }
            return@firstNotNullOfOrNull null
        }
        if (impl == null) {
            val compatImpl = compatImplList.last()
            impl = compatImpl
            logger.warn("Bad! Deploy version lower than ${compatImpl.ideVersion}, use this for compat, good luck.")
            ChipmunkAsDeployerCompat()
        }
        this.priorityImpl = impl
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

    override fun toApkProvider(apkInfos: List<ApkInfo>): ApkProvider {
        return impl.toApkProvider(apkInfos)
    }

    override fun getDisableMessage(project: Project): String? {
        return impl.getDisableMessage(project)
    }

    override fun getDeploymentService(project: Project): DeploymentService {
        return impl.getDeploymentService(project)
    }
}

private class CompatImpl(
    val ideVersion: IdeVersion,
    val impl: Lazy<IAsDeployerCompat>,
)
class IdeVersion(
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