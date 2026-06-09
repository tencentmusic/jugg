package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.ClassRedefiner
import com.android.tools.deployer.model.Apk
import com.google.common.collect.ImmutableMap
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdbClient

/**
 * Runs install and apply-changes operations through Android Studio deployer compat wrappers.
 */
class JuggDeployer(
    private val device: IDevice,
    private val deviceAdb: IDeviceAdb,
    private val deploymentService: IJuggDeployerDeploymentService,
    private val installSession: JuggInstallSession,
    private val exceptOverlayIds: Map<String, String>,
    private val isSkipExceptOverlayCheck: Boolean,
    private val logger: AdbLogWrapper,
    private val asDeployerCompat: IAsDeployerCompat = AsDeployerCompat,
) {

    /**
     * Information related to a swap or install.
     *
     * Failure is indicated by [JuggDeployerException], so this object is created only on successful deployments.
     */
    class Result {
        var skippedInstall = false
        var needsRestart = false
        var overlayId: String? = null
    }

    /**
     * Installs the given APKs. This method registers the APKs in the database for subsequent swaps.
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
            result.skippedInstall = !invokeInstallWithTransientRetry(packageName, apks, installMode)
            val apkList = asDeployerCompat.parseApks(apks)
            val appId = asDeployerCompat.getPackageName(apkList)
            val oid = asDeployerCompat.createBaseOverlayId(apkList)
            logger.info("after install, overlay id: ${oid.sha}, is base install: ${oid.isBaseInstall}")
            logger.info("verifyCache.storeEntry: ${apkList.joinToString(", ") { "${it.name}:${it.checksum}" }}")
            deploymentService.storeEntry(deviceAdb.serial, appId, apkList, oid, logger)
            result.overlayId = oid.sha
            return result
        } catch (e: Exception) {
            val realErrorMessage = logger.realErrorMessage
            logger.info("Install failed, error: \"${realErrorMessage}\"", e)
            if (realErrorMessage != null) {
                throw IllegalStateException("Install failed, error: \"${realErrorMessage}\"", e)
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
            if (!shouldEscalateToFullInstall(first, logger, installMode)) {
                throw first
            }
            installMode = JuggInstallSession.Mode.FULL
            logger.warning("Got device not found error, retry with FULL install mode after 2s.")
            Thread.sleep(2000)
            runInstallAttempt(packageName, apks, installMode)
        }
    }

    private fun runInstallAttempt(
        packageName: String,
        apks: List<String>,
        installMode: JuggInstallSession.Mode,
    ): Boolean {
        return asDeployerCompat.install(
            device,
            installSession,
            logger,
            packageName,
            apks,
            installMode,
        )
    }

    @Throws(JuggDeployerException::class)
    fun codeSwap(classFiles: List<String>, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, false, redefiners, data)
    }

    @Throws(JuggDeployerException::class)
    fun fullSwap(classFiles: List<String>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, true, ImmutableMap.of(), data)
    }

    @Throws(JuggDeployerException::class)
    private fun optimisticSwap(
        argPaths: List<String>, argRestart: Boolean, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData
    ): Result {
        if (!device.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.O)) {
            throw asDeployerCompat.apiNotSupported()
        }
        val deviceSerial = deviceAdb.serial
        val parseApksStartTime = System.currentTimeMillis()
        val newFiles = asDeployerCompat.parseApks(argPaths)
        logger.info("parseApks time: ${System.currentTimeMillis() - parseApksStartTime}ms")

        val packageName = asDeployerCompat.getPackageName(newFiles)
        val adbClient = IdeaDeviceAdbClient(device, logger)
        val pids = try {
            adbClient.getPids(packageName)
        } catch (e: Exception) {
            // On some devices REAL_PKG_NAME is not supported.
            logger.info("getPids exception: $e")
            emptyList()
        }
        var arch = adbClient.getArch(pids)
        logger.info("packageName: $packageName, pids: $pids, arch: $arch")
        if (arch == Deploy.Arch.ARCH_UNKNOWN) {
            try {
                val archInApks = ApkInfoReader(logger.logger).getArch(newFiles)
                arch = Deploy.Arch.valueOf(archInApks)
                logger.info("set arch from unknown to $arch")
            } catch (e: IllegalArgumentException) {
                logger.info("get arch from apks failed, set to ARCH_64_BIT")
                arch = Deploy.Arch.ARCH_64_BIT
            }
        }

        val speculativeDump: JuggDeploymentCacheEntry? = deploymentService.loadEntry(deviceSerial, packageName, logger)

        val exceptOverlayId = exceptOverlayIds[packageName]
        logger.info("before deploy, overlay id: ${speculativeDump?.overlayId?.sha}" +
                ", base install: ${speculativeDump?.overlayId?.isBaseInstall}" +
                ", except overlay id: $exceptOverlayId" +
                ", isSkipExceptOverlayCheck: $isSkipExceptOverlayCheck")

        if (!isSkipExceptOverlayCheck) {
            if (exceptOverlayId != speculativeDump?.overlayId?.sha) {
                logger.info("overlay id mismatch with Jugg, skip deploy")
                throw asDeployerCompat.overlayIdMismatch()
            }
        }

        val verifyDump = verifyCache(speculativeDump, asDeployerCompat, installSession, logger)
        val builder = OverlayUpdateBuilder(asDeployerCompat)
        val overlayUpdate = builder.build(verifyDump, data)

        val startTime = System.currentTimeMillis()
        try {
            val overlayId = asDeployerCompat.optimisticSwap(
                installSession,
                redefiners,
                packageName,
                argRestart,
                pids,
                arch,
                overlayUpdate,
                device,
                logger,
                data.isPushOverlayOnly,
            )
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

    fun supportsNewPipeline(): Boolean {
        return true
    }

    companion object {

        private fun verifyCache(
            entry: JuggDeploymentCacheEntry?,
            asDeployerCompat: IAsDeployerCompat,
            installSession: JuggInstallSession,
            logger: AdbLogWrapper,
        ): JuggDeploymentCacheEntry {
            if (entry == null) {
                throw asDeployerCompat.remoteApkNotFound()
            }
            if (!entry.overlayId.isBaseInstall) {
                logger.info("verifyCache on agent, skip")
                return entry
            }

            val cachedResults = entry.apks
            val actualResults = asDeployerCompat.dumpApks(installSession, entry.apks)
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
