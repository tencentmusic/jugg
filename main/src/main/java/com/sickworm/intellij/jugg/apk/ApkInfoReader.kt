package com.sickworm.intellij.jugg.apk

import com.android.tools.deployer.model.Apk
import com.intellij.openapi.diagnostic.Logger
import java.io.File

class ApkInfoReader(
    private val logger: Logger,
) {

    /**
     * see com.android.tools.deploy.proto.Deploy.Arch
     * @return ARCH_UNKNOWN, ARCH_32_BIT, ARCH_64_BIT
     */
    fun getArch(apks: List<Apk>): String {
        var is32Bit = true
        apks.forEach {
            val has64Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/arm64-v8a") }
            val has32Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/armeabi-v7a") }
            if (is32Bit) {
                is32Bit = !has64Bit && has32Bit
            }
            logger.debug("Apk getArch: path: ${it.path} has64Bit=$has64Bit, has32Bit=$has32Bit")
        }
        logger.debug("Apk getArch: is32Bit=$is32Bit")
        return if (is32Bit) "ARCH_32_BIT" else "ARCH_64_BIT"
    }

    fun createApkInfo(apks: List<File>): List<ApkInfo> {
        val apkFileUnits = mutableListOf<ApkFileUnit>()
        apks.forEach { apkFile ->
            val manifestInfo = ApkReader(apkFile, logger).getManifest()
            val apkFileUnit = ApkFileUnit(
                applicationId = manifestInfo.packageName(),
                moduleName = manifestInfo.featureSplit() ?: "",
                debuggable = manifestInfo.debuggable() == "true",
                apkFile,
            )
            apkFileUnits.add(apkFileUnit)
        }
        return apkFileUnits
            .groupBy { it.applicationId }
            .map { ApkInfo(it.value, it.key) }
            .sortedBy { apks.indexOf(it.files.first().apkFile) }
    }
}