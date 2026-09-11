package com.sickworm.intellij.jugg.apk

import com.android.tools.deployer.model.Apk
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * ApkInfoReader normalized APK metadata from compiled APK outputs.
 * Collaboration: Reads manifest fields via [ApkReader.getManifest] and emits [ApkInfo]/[ApkFileUnit] for compile/deploy pipelines.
 * Data Contract: [createApkInfo] groups files by application id and keeps original input order in its output list.
 */
class ApkInfoReader(
    private val logger: Logger,
) {

    /**
     * see com.android.tools.deploy.proto.Deploy.Arch
     * @return ARCH_UNKNOWN, ARCH_32_BIT, ARCH_64_BIT
     */
    fun getArch(apks: List<Apk>): String {
        var has32Bit = false
        var has64Bit = false
        apks.forEach {
            val apkHas64Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/arm64-v8a/") }
            val apkHas32Bit = it.apkEntries.any { (name, _) ->
                name.startsWith("lib/armeabi-v7a/") || name.startsWith("lib/armeabi/")
            }
            has64Bit = has64Bit || apkHas64Bit
            has32Bit = has32Bit || apkHas32Bit
            logger.debug("Apk getArch: path: ${it.path} has64Bit=$apkHas64Bit, has32Bit=$apkHas32Bit")
        }
        val arch = when {
            has32Bit && !has64Bit -> "ARCH_32_BIT"
            has64Bit && !has32Bit -> "ARCH_64_BIT"
            else -> "ARCH_UNKNOWN"
        }
        logger.debug("Apk getArch: has32Bit=$has32Bit, has64Bit=$has64Bit, arch=$arch")
        return arch
    }

    fun isUse32BitAbi(apks: List<Apk>): Boolean {
        return apks.any { apk ->
            runCatching { ApkReader(File(apk.path), logger).getManifest().use32bitAbi() }
                .onFailure { logger.debug("Read use32bitAbi failed for ${apk.path}", it) }
                .getOrDefault(false)
        }
    }

    fun createApkInfo(apks: List<File>): List<ApkInfo> {
        // Collect per-file manifest data so we can extract instrumentation info later.
        data class ApkManifestData(
            val unit: ApkFileUnit,
            val instrumentationTargetPackage: String?,
            val instrumentationRunner: String?,
        )

        val apkManifestDataList = apks.map { apkFile ->
            val manifestInfo = ApkReader(apkFile, logger).getManifest()
            ApkManifestData(
                unit = ApkFileUnit(
                    applicationId = manifestInfo.packageName(),
                    moduleName = manifestInfo.featureSplit() ?: "",
                    debuggable = manifestInfo.debuggable() == "true",
                    apkFile = apkFile,
                ),
                instrumentationTargetPackage = manifestInfo.instrumentationTargetPackage(),
                instrumentationRunner = manifestInfo.instrumentationRunner(),
            )
        }

        return apkManifestDataList
            .groupBy { it.unit.applicationId }
            .map { (appId, dataList) ->
                // All files in the group share the same applicationId; take instrumentation info
                // from the first entry that has it (there should be at most one per applicationId).
                val instrumentationTarget = dataList.firstNotNullOfOrNull { it.instrumentationTargetPackage }
                val instrumentationRunner = dataList.firstNotNullOfOrNull { it.instrumentationRunner }
                ApkInfo(
                    files = dataList.map { it.unit },
                    applicationId = appId,
                    instrumentationTargetPackage = instrumentationTarget,
                    instrumentationRunner = instrumentationRunner,
                )
            }
            .sortedBy { apks.indexOf(it.files.first().apkFile) }
    }
}
