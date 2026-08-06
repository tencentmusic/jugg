package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.AndroidVersion
import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.google.common.collect.ImmutableMap
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdbClient
import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOffline
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayDeployFailedException
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayDirtyException
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapTransport
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IJuggDeployerDeploymentService
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerException
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession
import com.sickworm.intellij.jugg.deploy.run.JuggClassRedefiner
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper

/**
 * [com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper] -> [JuggDeployTask] -> [JuggDeployer]
 */
class JuggDeployer(
    private val launchContext: LaunchContext,
    private val deploymentService: IJuggDeployerDeploymentService,
    private val logger: AdbLogWrapper,
    private val asDeployerCompat: IAsDeployerCompat = AsDeployerCompat,
) {
    private val device: IDevice = launchContext.device
    private val deviceAdb: IDeviceAdb = launchContext.deviceAdb
    private val installSession: JuggInstallSession = launchContext.installSession

    /**
     * Information related to a swap or install.
     *
     *
     * Failure is indicated by [JuggDeployerException], so this object is created only on successful deployments.
     */
    class Result {
        var skippedInstall = false
        var needsRestart = false
        var overlayId: String? = null
    }

    /**
     * Installs the given apks. This method will register the APKs in the database for subsequent
     * swaps
     */
    @Throws(JuggDeployerException::class)
    fun install(
        packageName: String, apks: List<String>, argInstallMode: JuggInstallSession.Mode
    ): Result {
        val result = Result()
        try {
            var installMode = argInstallMode
            if (installMode == JuggInstallSession.Mode.DELTA) {
                installMode = JuggInstallSession.Mode.DELTA_NO_SKIP
            }
            logger.info("going to install apks: $apks")
            result.skippedInstall = !invokeInstallWithTransientRetry(
                packageName, apks, installMode,
            )
            val apkList = asDeployerCompat.parseApks(apks)
            // Update the database
            val appId = asDeployerCompat.getPackageName(apkList)
            val oid = asDeployerCompat.createBaseOverlayId(apkList)
            logger.info("after install, overlay id: ${oid.sha}, is base install: ${oid.isBaseInstall}")
            logger.info("verifyCache.storeEntry: ${apkList.joinToString(", ") { "${it.name}:${it.checksum}" }}")
            deploymentService.storeEntry(deviceAdb.serial, appId, apkList, oid, logger)
            result.overlayId = oid.sha
            return result
        } catch (e: Exception) {
            val realErrorMessage = logger.realErrorMessage
            logger.info("Install failed, error: \"${realErrorMessage}\".", e)
            if (realErrorMessage != null) {
                throw IllegalStateException("Install failed, error: \"${realErrorMessage}\".", e)
            } else {
                throw asDeployerCompat.wrapDeployerException(e) ?: e
            }
        }
    }

    private fun invokeInstallWithTransientRetry(
        packageName: String,
        apks: List<String>,
        initialMode: JuggInstallSession.Mode,
    ): Boolean {
        var installMode = initialMode
        return try {
            runInstallAttempt(packageName, apks, installMode)
        } catch (first: Exception) {
            if (!isTransientInstallFailure(first, logger)) {
                throw first
            }
            if (shouldEscalateToFullInstall(first, logger, installMode)) {
                installMode = JuggInstallSession.Mode.FULL
                logger.warning("Transient install failure, retry with FULL install mode after transport ready.")
            } else {
                logger.warning("Transient install failure during install, wait for ADB transport.")
            }
            if (!waitAdbTransportReady("install", deviceAdb, logger)) {
                throw AdbTransientOffline.toException("install", first)
            }
            val installed = runInstallAttempt(packageName, apks, installMode)
            logger.logger.info("Install succeeded after transient ADB failure, retried once.")
            return installed
        }
    }

    private fun runInstallAttempt(
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        return asDeployerCompat.install(
            device, installSession, logger,
            packageName, apks, installMode,
        )
    }

    @Throws(JuggDeployerException::class)
    fun codeSwap(classFiles: List<String>, redefiners: Map<Int, JuggClassRedefiner>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, false, redefiners, data)
    }

    @Throws(JuggDeployerException::class)
    fun fullSwap(classFiles: List<String>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, true, ImmutableMap.of(), data)
    }

    @Throws(JuggDeployerException::class)
    private fun optimisticSwap(
        argPaths: List<String>, argRestart: Boolean, redefiners: Map<Int, JuggClassRedefiner>, data: JuggDeployData
    ): Result {
        if (!device.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.O)) {
            throw asDeployerCompat.apiNotSupported()
        }
        val deviceSerial = deviceAdb.serial
        // Get the list of files from the local apks
        val parseApksStartTime = System.currentTimeMillis()
        val newFiles = asDeployerCompat.parseApks(argPaths)
        logger.info("parseApks time: ${System.currentTimeMillis() - parseApksStartTime}ms")

        // Get the App info. Some from the APK, some from DDMLib.
        val packageName = asDeployerCompat.getPackageName(newFiles)
        val adbClient = IdeaDeviceAdbClient(device, logger)
        val pids = try {
            adbClient.getPids(packageName)
        } catch (e: Exception) {
            // on Huawei Android 9: java.lang.IllegalStateException: Device LUGUT19B22001999, do not support REAL_PKG_NAME
            logger.info("getPids exception: $e")
            emptyList()
        }
        var arch = adbClient.getArch(pids)
        logger.info("packageName: $packageName, pids: $pids, arch: $arch")
        if (arch == Deploy.Arch.ARCH_UNKNOWN) {
            // if arch is unknown, installer will use 32-bit agent, which may apply failed.
            try {
                val archInApks = ApkInfoReader(logger.logger).getArch(newFiles)
                arch = Deploy.Arch.valueOf(archInApks)
                logger.info("set arch from unknown to $arch")
            } catch (e: IllegalArgumentException) {
                logger.info("get arch from apks failed, set to ARCH_64_BIT")
                arch = Deploy.Arch.ARCH_64_BIT
            }
        }

        // Get the list of files from the installed app assuming deployment cache is correct.
        val speculativeDump: JuggDeploymentCacheEntry? = deploymentService.loadEntry(deviceSerial, packageName, logger)

        val exceptOverlayId = launchContext.exceptOverlayIds[packageName]
        logger.info("before deploy, overlay id: ${speculativeDump?.overlayId?.sha}" +
                ", base install: ${speculativeDump?.overlayId?.isBaseInstall}" +
                ", except overlay id: $exceptOverlayId" +
                ", isSkipExceptOverlayCheck: ${launchContext.isSkipExceptOverlayCheck}")

        if (!launchContext.isSkipExceptOverlayCheck) {
            if (exceptOverlayId != speculativeDump?.overlayId?.sha) {
                // situation 1: using device running on different projects but same package name.
                // situation 2: using different devices running on one project.
                logger.info("overlay id mismatch with Jugg, skip deploy")
                throw asDeployerCompat.overlayIdMismatch()
            }
        }

        val startTime = System.currentTimeMillis()
        tryDirectOverlaySwap(packageName, data, speculativeDump, arch)?.let { overlayId ->
            val costTime = System.currentTimeMillis() - startTime
            logger.info("after direct overlay deploy, cost: ${costTime}ms, overlay id: ${overlayId.sha}, is base install: ${overlayId.isBaseInstall}, isPushOverlayOnly: ${data.isPushOverlayOnly}")
            deploymentService.storeEntry(deviceSerial, packageName, newFiles, overlayId, logger)
            return Result().also {
                it.overlayId = overlayId.sha
            }
        }

        // On an on-host verification of the dump first.
        val verifyDump = verifyCache(speculativeDump, asDeployerCompat, installSession, logger, deviceAdb)

        // Convert to ADT deploy data.
        val builder = OverlayUpdateBuilder(asDeployerCompat)
        val overlayUpdate = builder.build(verifyDump, data)

        // Perform the swap.
        try {
            val overlayId = runWithOfflineRetry("optimistic swap", deviceAdb, logger) {
                asDeployerCompat.optimisticSwap(
                    installSession, redefiners, packageName,
                    argRestart, pids, arch, overlayUpdate,
                    device, logger,
                    data.isPushOverlayOnly,
                )
            }
            val costTime = System.currentTimeMillis() - startTime
            logger.info("after deploy, cost: ${costTime}ms, overlay id: ${overlayId.sha}, is base install: ${overlayId.isBaseInstall}, isPushOverlayOnly: ${data.isPushOverlayOnly}")
            deploymentService.storeEntry(deviceSerial, packageName, newFiles, overlayId, logger)

            return Result().also {
                it.overlayId = overlayId.sha
            }
        } catch (e: Exception) {
            val realErrorMessage = logger.realErrorMessage
            logger.info("Deploy failed, error: \"${realErrorMessage}\"", e)
            if (realErrorMessage != null) {
                throw IllegalStateException("Deploy failed, error: \"${realErrorMessage}\"", e)
            } else {
                throw asDeployerCompat.wrapDeployerException(e) ?: e
            }
        }
    }

    private fun tryDirectOverlaySwap(
        packageName: String,
        data: JuggDeployData,
        speculativeDump: JuggDeploymentCacheEntry?,
        appArch: Deploy.Arch,
    ): JuggOverlayId? {
        speculativeDump ?: return null
        val transport = DirectOverlaySwapTransport(launchContext, logger.logger)
        if (!transport.canTry(data)) return null
        return try {
            val overlayUpdate = OverlayUpdateBuilder(asDeployerCompat).build(speculativeDump, data)
            transport.trySwap(
                packageName = packageName,
                data = data,
                overlayUpdate = overlayUpdate,
                asDeployerCompat = asDeployerCompat,
                appArch = appArch,
            )
        } catch (e: Exception) {
            if (e is DirectOverlayDirtyException) throw e
            if (e is DirectOverlayDeployFailedException) throw e
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            logger.info("Direct overlay deploy failed before writer, fallback to Apply Changes.", e)
            null
        }
    }

    fun supportsNewPipeline(): Boolean {
        // this.options.useOptimisticSwap && this.adb.getVersion().getApiLevel() >= 30;
        return true
    }

    companion object {

        private fun verifyCache(
            entry: JuggDeploymentCacheEntry?,
            asDeployerCompat: IAsDeployerCompat,
            installSession: JuggInstallSession,
            logger: AdbLogWrapper,
            adb: IDeviceAdb,
        ): JuggDeploymentCacheEntry {
            if (entry == null) {
                throw asDeployerCompat.remoteApkNotFound()
            }
            if (!entry.overlayId.isBaseInstall) {
                // not base install, verify on agent
                logger.info("verifyCache on agent, skip")
                return entry
            }
            // base install, verify apk

            // If we have an install without OID file, we are going to the classic dump to
            // verify that we are actually looking at the same APK cached in the database.
            val cachedResults = entry.apks
            val actualResults = runWithOfflineRetry("verify cache", adb, logger) {
                asDeployerCompat.dumpApks(installSession, entry.apks)
            }
            if (cachedResults.size != actualResults.size) {
                logger.info("throw overlayIdMismatch: cached size: ${cachedResults.size}, actual size: ${actualResults.size}")
                throw asDeployerCompat.overlayIdMismatch()
            }
            val sortedCachedResults = cachedResults.sortedWith(Comparator.comparing { apk: Apk -> apk.name })
            val sortedActualResults = actualResults.sortedWith(Comparator.comparing { apk: Apk -> apk.name })
            var i = 0
            val len = sortedCachedResults.size
            while (i < len) {
                val cached = sortedCachedResults[i]
                val actual = sortedActualResults[i]
                logger.info("verifyCache.verifyEntry: ${cached.name}:${cached.checksum}")
                if (cached.name != actual.name) {
                    logger.info("throw overlayIdMismatch: cached name: ${cached.name}, actual name: ${actual.name}")
                    throw asDeployerCompat.overlayIdMismatch()
                } else if (cached.checksum != actual.checksum) {
                    logger.info("throw overlayIdMismatch: cached checksum: ${cached.checksum}, actual checksum: ${actual.checksum}")
                    throw asDeployerCompat.overlayIdMismatch()
                }
                i++
            }
            logger.info("verifyCache success")
            return entry
        }

        private fun <T> runWithOfflineRetry(
            phase: String,
            adb: IDeviceAdb,
            logger: AdbLogWrapper,
            block: () -> T,
        ): T {
            return try {
                block()
            } catch (e: Exception) {
                if (!AdbTransientOffline.isOffline(e)) {
                    throw e
                }
                if (!waitAdbTransportReady(phase, adb, logger)) {
                    throw AdbTransientOffline.toException(phase, e)
                }
                block()
            }
        }

        private fun waitAdbTransportReady(phase: String, adb: IDeviceAdb, logger: AdbLogWrapper): Boolean {
            return AdbTransientOffline.waitForAdbTransport(
                phase = phase,
                adb = adb,
            ) {
                logger.info(it)
            }
        }

        internal fun isTransientInstallFailure(e: Throwable, logger: AdbLogWrapper): Boolean {
            if (AdbTransientOffline.isOffline(e)) {
                return true
            }
            logger.realErrorMessage?.let { message ->
                if (AdbTransientOffline.isOfflineMessage(message)) {
                    return true
                }
            }
            val message = e.message ?: return false
            if (message.contains("not found", ignoreCase = true)) {
                return true
            }
            return AdbTransientOffline.isOfflineMessage(message)
        }

        internal fun shouldEscalateToFullInstall(
            e: Throwable,
            logger: AdbLogWrapper,
            installMode: JuggInstallSession.Mode,
        ): Boolean {
            if (installMode == JuggInstallSession.Mode.FULL) {
                return false
            }
            if (e.message?.contains("not found", ignoreCase = true) == true) {
                return true
            }
            return logger.realErrorMessage?.contains("not found", ignoreCase = true) == true
        }
    }
}
