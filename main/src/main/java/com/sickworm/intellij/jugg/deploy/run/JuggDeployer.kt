package com.sickworm.intellij.jugg.deploy.run

import com.android.sdklib.AndroidVersion
import com.android.tools.deployer.Deployer.InstallMode
import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.*
import com.android.tools.tracer.Trace
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import java.util.Comparator

/**
 * @see com.android.tools.deployer.Deployer
 */
class JuggDeployer(
    private val adb: AdbClient,
    private val deployCache: DeploymentCacheDatabase,
    private val dexDb: SqlApkFileDatabase,
    private val installer: Installer,
    private val service: UIService,
    private val exceptOverlayIds: Map<String, String>,
    private val logger: ILogger
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
        Trace.begin("install").use {
            val splitter = CachedDexSplitter(dexDb, D8DexSplitter())
            var installMode = argInstallMode
            if (installMode == InstallMode.DELTA) {
                installMode = InstallMode.DELTA_NO_SKIP
            }
            result.skippedInstall = !AsDeployerCompat.install(
                adb, service, installer, logger,
                packageName, apks, options, installMode,
            )
            val apkList = ApkParser().parsePaths(apks)
            // Update the database
            splitter.cache(apkList)
            val appId = ApplicationDumper.getPackageName(apkList)
            val oid = OverlayId(apkList)
            deployCache.store(adb.serial, appId, apkList, oid)
            result.overlayId = oid.sha
            return result
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
        val newFiles = ApkParser().parsePaths(argPaths)
        // Get the App info. Some from the APK, some from DDMLib.
        val packageName = ApplicationDumper.getPackageName(newFiles)
        val pids = adb.getPids(packageName)
        val arch = adb.getArch(pids)

        // Get the list of files from the installed app assuming deployment cache is correct.
        val speculativeDump: DeploymentCacheDatabase.Entry? = deployCache[deviceSerial, packageName]
        val exceptOverlayId = exceptOverlayIds[packageName]
        logger.info("before deploy, overlay id: ${speculativeDump?.overlayId?.sha}" +
                ", except overlay id: $exceptOverlayId" +
                ", base install: ${speculativeDump?.overlayId?.isBaseInstall}")
        if (exceptOverlayId != speculativeDump?.overlayId?.sha) {
            // situation 1: using device running on different projects but same package name.
            // situation 2: using different devices running on one project.
            logger.info("overlay id mismatch with Jugg, skip deploy")
            throw DeployerException.overlayIdMismatch()
        }

        // On an on-host verification of the dump first.
        val dumper = ApplicationDumper(installer)
        val verifyDump = verifyCache(speculativeDump, dumper)

        // covert to adt deploy data.
        val builder = OverlayUpdateBuilder()
        val overlayUpdate = builder.build(verifyDump, data)

        // Perform the swap.
        val overlayId = AsDeployerCompat.optimisticSwap(
            installer, redefiners, packageName,
            argRestart, pids, arch, overlayUpdate,
            adb, logger,
        )
        logger.info("after deploy, overlay id: ${overlayId.sha}, is base install: ${overlayId.isBaseInstall}")
        deployCache.store(deviceSerial, packageName, newFiles, overlayId)
        return Result().also {
            it.overlayId = overlayId.sha
        }
    }

    fun supportsNewPipeline(): Boolean {
        // this.options.useOptimisticSwap && this.adb.getVersion().getApiLevel() >= 30;
        return true
    }

    companion object {

        @Throws(DeployerException::class)
        private fun verifyCache(
            entry: DeploymentCacheDatabase.Entry?, dumper: ApplicationDumper
        ): DeploymentCacheDatabase.Entry {
            if (entry == null) {
                throw DeployerException.remoteApkNotFound()
            }
            if (!entry.overlayId.isBaseInstall) {
                return entry
            }

            // If we have an install without OID file, we are going to the classic dump to
            // verify that we are actually looking at the same APK cached in the database.
            val cachedResults = entry.apks
            val actualResults = dumper.dump(entry.apks).apks
            if (cachedResults.size != actualResults.size) {
                throw DeployerException.overlayIdMismatch()
            }
            cachedResults.sortWith(Comparator.comparing { apk: Apk -> apk.name })
            actualResults.sortWith(Comparator.comparing { apk: Apk -> apk.name })
            var i = 0
            val len = cachedResults.size
            while (i < len) {
                val cached = cachedResults[i]
                val actual = actualResults[i]
                if (cached.name != actual.name) {
                    throw DeployerException.overlayIdMismatch()
                } else if (cached.checksum != actual.checksum) {
                    throw DeployerException.overlayIdMismatch()
                }
                i++
            }
            return entry
        }
    }
}