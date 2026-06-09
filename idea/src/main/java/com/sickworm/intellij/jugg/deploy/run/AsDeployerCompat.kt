package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.ClassRedefiner
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.ApkEntry
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.DeploymentService
import com.android.tools.idea.protobuf.ByteString
import com.android.utils.ILogger
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.lang.ref.WeakReference
import kotlin.math.min

object AsDeployerCompat : IAsDeployerCompat {

    private lateinit var priorityImpl : CompatImpl

    /**
     * Must order DESC
     */
    private val compatImplList = listOf(
        CompatImpl(
            IdeVersion("Android Studio Quail", "AI", "261.23567.138", "261.23567.138"),
            lazy { QuailAsDeployerCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio Panda", "AI", "253.29346.138", "253.29346.138"),
            lazy { PandaAsDeployerFeatureCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio Otter 2 Feature Drop", "IA", "252.27397.103"),
            lazy { OtterAsDeployerFeatureCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio Narwhal Feature Drop", "IA", "251.27812.49"),
            lazy { NarwhalAsDeployerFeatureCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio Narwhal", "IA", "251.23774.16"),
            lazy { NarwhalAsDeployerCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio Meerkat", "IA", "243.22562.218"),
            lazy { MeerkatAsDeployerCompat() }
        ),
        CompatImpl(
            IdeVersion("Android Studio Iguana", "IA", "232.10227.8"),
            lazy { IguanaAsDeployerCompat() },
        ),
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

    private lateinit var logger: WeakReference<Logger>

    val ideVersion = IdeVersion(ApplicationInfo.getInstance())

    fun init(logger: Logger) {
        this.logger = WeakReference(logger)

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
        return invokeCompat { it.getApkProvider(project, config) }
    }

    override fun getSelectedDevices(project: Project): List<IDevice>? {
        return invokeCompat { it.getSelectedDevices(project) }
    }

    override fun getConnectedDevices(project: Project): List<IDevice>? {
        return invokeCompat { it.getConnectedDevices(project) }
    }

    override fun createInstallSession(
        installersFolder: String,
        device: IDevice,
        logger: ILogger,
        onPrompt: (String) -> Boolean,
        onMessage: (String) -> Unit,
    ): JuggInstallSession {
        return invokeCompat { it.createInstallSession(installersFolder, device, logger, onPrompt, onMessage) }
    }

    override fun install(
        device: IDevice,
        session: JuggInstallSession,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        return invokeCompat { it.install(device, session, logger, packageName, apks, installMode) }
    }

    override fun getInstallMode(): JuggInstallSession.Mode {
        return invokeCompat { it.getInstallMode() }
    }

    override fun makeDebuggerRedefiners(
        project: Project,
        device: IDevice,
        fallback: Boolean
    ): Map<Int, ClassRedefiner> {
        return invokeCompat { it.makeDebuggerRedefiners(project, device, fallback) }
    }

    override fun optimisticSwap(
        session: JuggInstallSession,
        redefiners: Map<Int, ClassRedefiner>,
        packageName: String,
        argRestart: Boolean,
        pids: List<Int>,
        arch: Deploy.Arch,
        overlayUpdate: JuggOverlayUpdate,
        device: IDevice,
        logger: ILogger,
        isPushOverlayOnly: Boolean,
    ): JuggOverlayId {
        return invokeCompat {
            it.optimisticSwap(
                session,
                redefiners,
                packageName,
                argRestart,
                pids,
                arch,
                overlayUpdate,
                device,
                logger,
                isPushOverlayOnly,
            )
        }
    }

    override fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState {
        return invokeCompat { it.getIdeDeployStateResult(project, device, packageName) }
    }

    override fun getDeploymentService(project: Project): DeploymentService {
        return invokeCompat { it.getDeploymentService(project) }
    }

    override fun parseApks(paths: List<String>): List<Apk> {
        return invokeCompat { it.parseApks(paths) }
    }

    override fun getPackageName(apks: List<Apk>): String {
        return invokeCompat { it.getPackageName(apks) }
    }

    override fun createBaseOverlayId(apks: List<Apk>): JuggOverlayId {
        return invokeCompat { it.createBaseOverlayId(apks) }
    }

    override fun buildOverlayId(base: JuggOverlayId, addedFiles: List<JuggOverlayFile>): JuggOverlayId {
        return invokeCompat { it.buildOverlayId(base, addedFiles) }
    }

    override fun createOverlayUpdate(
        cachedDump: JuggDeploymentCacheEntry,
        dexOverlays: DexComparator.ChangedClasses,
        fileOverlays: Map<ApkEntry, ByteString>,
    ): JuggOverlayUpdate {
        return invokeCompat { it.createOverlayUpdate(cachedDump, dexOverlays, fileOverlays) }
    }

    override fun dumpApks(session: JuggInstallSession, apks: List<Apk>): List<Apk> {
        return invokeCompat { it.dumpApks(session, apks) }
    }

    override fun remoteApkNotFound(): JuggDeployerException {
        return invokeCompat { it.remoteApkNotFound() }
    }

    override fun overlayIdMismatch(): JuggDeployerException {
        return invokeCompat { it.overlayIdMismatch() }
    }

    override fun apiNotSupported(): JuggDeployerException {
        return invokeCompat { it.apiNotSupported() }
    }

    override fun wrapDeployerException(e: Throwable): JuggDeployerException? {
        return invokeCompat { it.wrapDeployerException(e) }
    }

    override fun createDeploymentCacheEntry(apks: List<Apk>, overlayId: JuggOverlayId): JuggDeploymentCacheEntry {
        return invokeCompat { it.createDeploymentCacheEntry(apks, overlayId) }
    }

    override fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>) {
        // special api, call before project init, we just loop all impl
        // special api, can not hot update
        RunConfigurationDeviceSelectionMarker.mark(runConfiguration)
        compatImplList.forEach {
            try {
                it.impl.value.setAllowSelectDevice(runConfiguration)
            } catch (e: Throwable) {
                // do nothing
            }
        }
    }

    override fun attachJavaDebugger(project: Project, device: IDevice, packageName: String) {
        return invokeCompat { it.attachJavaDebugger(project, device, packageName) }
    }

    override fun getSuggestRunConfigurations(existsRunConfigNames: List<String>, project: Project, logger: Logger, isNeedDefaultRunConfig: Boolean): List<SuggestRunConfiguration> {
        return invokeCompat { it.getSuggestRunConfigurations(existsRunConfigNames, project, logger, isNeedDefaultRunConfig) }
    }

    override fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo? {
        return invokeCompat { it.getIdeModuleInfo(project, module, logger, isSafeMode) }
    }

    private fun <T> invokeCompat(call: (IAsDeployerCompat) -> T): T {
        return AsDeployerCompatDispatcher(
            implementations = compatImplList,
            priorityImplementation = priorityImpl,
            nameOf = { it.ideVersion.name },
            logDebug = { logger.get()?.debug(it) },
            logWarn = { logger.get()?.warn(it) },
        ).invoke { call(it.impl.value) }
    }
}

/**
 * Marks Jugg run configurations as deployable without linking this module to one Android Studio API shape.
 */
internal object RunConfigurationDeviceSelectionMarker {
    private val keyRefs = listOf(
        KeyRef("com.android.tools.idea.execution.common.DeployableToDevice", "KEY"),
        KeyRef("com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxAction", "DEPLOYS_TO_LOCAL_DEVICE"),
    )

    fun mark(runConfiguration: RunConfigurationBase<*>) {
        keyRefs.forEach { keyRef ->
            val key = keyRef.loadKey() ?: return@forEach
            runConfiguration.putUserData(key, true)
        }
    }

    private data class KeyRef(val className: String, val fieldName: String) {
        fun loadKey(): Key<Boolean>? {
            return try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className).getField(fieldName).get(null) as? Key<Boolean>
            } catch (e: Throwable) {
                null
            }
        }
    }
}

private class CompatImpl(
    val ideVersion: IdeVersion,
    val impl: Lazy<IAsDeployerCompat>,
)

/**
 * Dispatches a compat API call without reflecting the whole compat interface during IDE startup.
 */
internal class AsDeployerCompatDispatcher<T>(
    private val implementations: List<T>,
    private val priorityImplementation: T,
    private val nameOf: (T) -> String,
    private val logDebug: (String) -> Unit,
    private val logWarn: (String) -> Unit,
) {
    fun <R> invoke(call: (T) -> R): R {
        try {
            return call(priorityImplementation)
        } catch (e: Throwable) {
            if (!e.isCompatError) {
                throw e
            }

            logDebug("try priorityImpl with $e, try other version impl")

            implementations
                .filter { it != priorityImplementation }
                .forEach {
                    try {
                        val result = call(it)
                        logDebug("try ${nameOf(it)} API success, return")
                        return result
                    } catch (fallbackError: Throwable) {
                        if (!fallbackError.isCompatError) {
                            throw fallbackError
                        }
                        logDebug("try ${nameOf(it)} API with $fallbackError")
                    }
                }

            logWarn("Try all Android Studio API failed.")
            throw e
        }
    }

    private val Throwable.isCompatError: Boolean get() {
        return this is NoSuchMethodError
                || this is NoSuchFieldError
                || this is NoClassDefFoundError
                || this is IncompatibleClassChangeError
    }
}

data class IdeVersion(
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

    override fun hashCode(): Int {
        return mainVersion.hashCode()
    }

}
