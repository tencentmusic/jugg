package com.sickworm.intellij.jugg.deploy.run

import com.android.sdklib.AndroidVersion
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.*
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.model.Apk
import com.google.common.collect.ImmutableMap
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.deploy.AdbTransientOffline
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayDirtyException
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapOptions
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapTransport

/**
 * @see com.android.tools.deployer.Deployer
 *
 * [JuggDeployerHelper] -> [JuggDeployTask] -> [JuggDeployer]
 */
class JuggDeployer(
    private val adb: AdbClient,
    private val deploymentService: JuggDeploymentService,
    private val installer: Installer,
    private val service: UIService,
    private val exceptOverlayIds: Map<String, String>,
    private val isSkipExceptOverlayCheck: Boolean,
    private val logger: AdbLogWrapper,
    private val directOverlaySwapOptions: DirectOverlaySwapOptions = DirectOverlaySwapOptions.disabled(),
) {

    /**
     * Information related to a swap or install.
     *
     *
     * Note that there is indication to success or failure of the operation. Failure is indicated
     * by [DeployerException] thus this object is created only on successful deployments.
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
    @Throws(DeployerException::class)
    fun install(
        packageName: String, apks: List<String>, options: InstallOptions, argInstallMode: InstallMode
    ): Result {
        val result = Result()
        try {
            var installMode = argInstallMode
            if (installMode == InstallMode.DELTA) {
                installMode = InstallMode.DELTA_NO_SKIP
            }
            logger.info("going to install apks: $apks")
            try {
                result.skippedInstall = !AsDeployerCompat.install(
                    adb, service, installer, logger,
                    packageName, apks, options, installMode,
                )
            } catch (e: Exception) {
                if (e.message?.contains("not found") == true) {
                    logger.info("got device not found, argInstallMode: $installMode")
                    if (installMode != InstallMode.FULL) {
                        installMode = InstallMode.FULL
                        logger.warning("Got device not found error, retry with FULL install mode after 2s.")
                        Thread.sleep(2000)
                        result.skippedInstall = !AsDeployerCompat.install(
                            adb, service, installer, logger,
                            packageName, apks, options, installMode,
                        )
                    }
                } else {
                    throw e
                }
            }
            val apkList = AsDeployerCompat.parseApks(apks)
            // Update the database
            val appId = ApplicationDumper.getPackageName(apkList)
            val oid = OverlayId(apkList)
            logger.info("after install, overlay id: ${oid.sha}, is base install: ${oid.isBaseInstall}")
            logger.info("verifyCache.storeEntry: ${apkList.joinToString(", ") { "${it.name}:${it.checksum}" }}")
            deploymentService.storeEntry(adb.serial, appId, apkList, oid, logger)
            result.overlayId = oid.sha
            return result
        } catch (e: Exception) {
            val realErrorMessage = logger.realErrorMessage
            logger.info("Install failed, error: \"${realErrorMessage}\".", e)
            if (realErrorMessage != null) {
                throw IllegalStateException("Install failed, error: \"${realErrorMessage}\".", e)
            } else {
                throw e
            }
        }
    }

    @Throws(DeployerException::class)
    fun codeSwap(classFiles: List<String>, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, false, redefiners, data)
    }

    @Throws(DeployerException::class)
    fun fullSwap(classFiles: List<String>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, true, ImmutableMap.of(), data)
    }

    @Throws(DeployerException::class)
    private fun optimisticSwap(
        argPaths: List<String>, argRestart: Boolean, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData
    ): Result {
        if (!adb.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.O)) {
            throw DeployerException.apiNotSupported()
        }
        val deviceSerial = adb.serial
        // Get the list of files from the local apks
        val parseApksStartTime = System.currentTimeMillis()
        val newFiles = AsDeployerCompat.parseApks(argPaths)
        logger.info("parseApks time: ${System.currentTimeMillis() - parseApksStartTime}ms")

        // Get the App info. Some from the APK, some from DDMLib.
        val packageName = ApplicationDumper.getPackageName(newFiles)
        val pids = try {
            adb.getPids(packageName)
        } catch (e: Exception) {
            // on Huawei Android 9: java.lang.IllegalStateException: Device LUGUT19B22001999, do not support REAL_PKG_NAME
            logger.info("getPids exception: $e")
            emptyList()
        }
        var arch = adb.getArch(pids)
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
        val speculativeDump: DeploymentCacheDatabase.Entry? = deploymentService.loadEntry(deviceSerial, packageName, logger)

        val exceptOverlayId = exceptOverlayIds[packageName]
        logger.info("before deploy, overlay id: ${speculativeDump?.overlayId?.sha}" +
                ", base install: ${speculativeDump?.overlayId?.isBaseInstall}" +
                ", except overlay id: $exceptOverlayId" +
                ", isSkipExceptOverlayCheck: $isSkipExceptOverlayCheck")

        if (!isSkipExceptOverlayCheck) {
            if (exceptOverlayId != speculativeDump?.overlayId?.sha) {
                // situation 1: using device running on different projects but same package name.
                // situation 2: using different devices running on one project.
                logger.info("overlay id mismatch with Jugg, skip deploy")
                throw DeployerException.overlayIdMismatch()
            }
        }

        val startTime = System.currentTimeMillis()
        tryDirectOverlaySwap(packageName, data, speculativeDump)?.let { overlayId ->
            val costTime = System.currentTimeMillis() - startTime
            logger.info("after direct overlay deploy, cost: ${costTime}ms, overlay id: ${overlayId.sha}, is base install: ${overlayId.isBaseInstall}, isPushOverlayOnly: ${data.isPushOverlayOnly}")
            deploymentService.storeEntry(deviceSerial, packageName, newFiles, overlayId, logger)
            return Result().also {
                it.overlayId = overlayId.sha
            }
        }

        // On an on-host verification of the dump first.
        val dumper = ApplicationDumper(installer)
        val verifyDump = verifyCache(speculativeDump, dumper, logger, adb)

        // Convert to ADT deploy data.
        val builder = OverlayUpdateBuilder()
        val overlayUpdate = builder.build(verifyDump, data)

        // Perform the swap.
        try {
            val overlayId = runWithOfflineRetry("optimistic swap", adb, logger) {
                AsDeployerCompat.optimisticSwap(
                    installer, redefiners, packageName,
                    argRestart, pids, arch, overlayUpdate,
                    adb, logger,
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
                throw e
            }
        }
    }

    private fun tryDirectOverlaySwap(
        packageName: String,
        data: JuggDeployData,
        speculativeDump: DeploymentCacheDatabase.Entry?,
    ): OverlayId? {
        speculativeDump ?: return null
        // Base-install cache still needs the legacy APK dump verification before any direct overlay write.
        if (speculativeDump.overlayId.isBaseInstall) return null
        val transport = DirectOverlaySwapTransport(directOverlaySwapOptions, logger.logger)
        if (!transport.canTry(data)) return null
        return try {
            val overlayUpdate = OverlayUpdateBuilder().build(speculativeDump, data)
            transport.trySwap(
                packageName = packageName,
                data = data,
                overlayUpdate = overlayUpdate,
            )
        } catch (e: Exception) {
            if (e is DirectOverlayDirtyException) throw e
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

        @Throws(DeployerException::class)
        private fun verifyCache(
            entry: DeploymentCacheDatabase.Entry?, dumper: ApplicationDumper, logger: AdbLogWrapper, adb: AdbClient
        ): DeploymentCacheDatabase.Entry {
            if (entry == null) {
                throw DeployerException.remoteApkNotFound()
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
                dumper.dump(entry.apks).apks
            }
            if (cachedResults.size != actualResults.size) {
                logger.info("throw overlayIdMismatch: cached size: ${cachedResults.size}, actual size: ${actualResults.size}")
                throw DeployerException.overlayIdMismatch()
            }
            cachedResults.sortWith(Comparator.comparing { apk: Apk -> apk.name })
            actualResults.sortWith(Comparator.comparing { apk: Apk -> apk.name })
            var i = 0
            val len = cachedResults.size
            while (i < len) {
                val cached = cachedResults[i]
                val actual = actualResults[i]
                logger.info("verifyCache.verifyEntry: ${cached.name}:${cached.checksum}")
                if (cached.name != actual.name) {
                    logger.info("throw overlayIdMismatch: cached name: ${cached.name}, actual name: ${actual.name}")
                    throw DeployerException.overlayIdMismatch()
                } else if (cached.checksum != actual.checksum) {
                    logger.info("throw overlayIdMismatch: cached checksum: ${cached.checksum}, actual checksum: ${actual.checksum}")
                    throw DeployerException.overlayIdMismatch()
                }
                i++
            }
            logger.info("verifyCache success")
            return entry
        }

        private fun <T> runWithOfflineRetry(
            phase: String,
            adb: AdbClient,
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

        private fun waitAdbTransportReady(phase: String, adb: AdbClient, logger: AdbLogWrapper): Boolean {
            return AdbTransientOffline.waitForAdbTransport(
                serial = adb.serial,
                phase = phase,
                adb = adb,
            ) {
                logger.info(it)
            }
        }
    }
}
