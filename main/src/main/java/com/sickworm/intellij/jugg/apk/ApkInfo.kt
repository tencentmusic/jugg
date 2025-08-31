package com.sickworm.intellij.jugg.apk

import java.io.File
import java.security.MessageDigest

data class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String
) {

    val baseApk: ApkFileUnit? get() = files.find { it.isBaseApk }

    constructor(
        file: File,
        applicationId: String,
    ) : this(listOf(ApkFileUnit(applicationId, "", file)), applicationId)
}

data class ApkFileUnit(val applicationId: String, val moduleName: String, val apkFile: File) {

    val isBaseApk get() = moduleName.isEmpty()
    val isFeatureApk get() = moduleName.isNotEmpty()

    val resourcePackage get() = if (isBaseApk) applicationId else "$applicationId.$moduleName"

    val uniqueKey: String get() = getUniqueKey(apkFile.path)

    companion object {
        fun getUniqueKey(apkPath: String): String {
            return File(apkPath).name + "_" + apkPath.md5.substring(0, 8)
        }

        private val String.md5: String get() = MessageDigest.getInstance("MD5").digest(this.toByteArray()).toHex()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

