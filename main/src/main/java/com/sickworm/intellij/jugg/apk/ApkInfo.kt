package com.sickworm.intellij.jugg.apk

import java.io.File
import java.security.MessageDigest

/**
 * ApkInfo split APK artifacts under one application id for downstream deploy and analysis steps.
 *
 * [instrumentationTargetPackage] and [instrumentationRunner] are only non-null for test APKs
 * (read from the `<instrumentation>` element in AndroidManifest.xml).
 * Both fields are null for regular app APKs.
 */
data class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String,
    val instrumentationTargetPackage: String? = null,
    val instrumentationRunner: String? = null,
) {

    val baseApk: ApkFileUnit? get() = files.find { it.isBaseApk }

    /** Returns true when this APK is an instrumentation test APK. */
    val isTestApk: Boolean get() = instrumentationTargetPackage != null

    /**
     * True when this test APK targets a different package (app androidTest pattern).
     * These run in the target app's process and don't need their own JVMTI agent or
     * incremental deploy. False for self-targeting test APKs (library androidTest).
     */
    val isOtherTargetingTestApk: Boolean get() = isTestApk && instrumentationTargetPackage != applicationId

    constructor(
        file: File,
        applicationId: String,
    ) : this(listOf(ApkFileUnit(applicationId, "", true, file)), applicationId)
}

/**
 * ApkFileUnit one physical base/feature APK file with helpers for split-aware path and package derivation.
 * Data Contract: Empty [moduleName] means base APK, non-empty [moduleName] means feature APK.
 */
data class ApkFileUnit(val applicationId: String, val moduleName: String, val debuggable: Boolean, val apkFile: File) {

    val isBaseApk get() = moduleName.isEmpty()
    val isFeatureApk get() = moduleName.isNotEmpty()

    val resourcePackage get() = if (isBaseApk) applicationId else "$applicationId.$moduleName"

    fun getUniquePath(basePath: String): String {
        return if (isBaseApk) {
            basePath
        } else {
            "${basePath}_${getUniqueKey(apkFile.path)}"
        }
    }

    companion object {
        fun getUniqueKey(apkPath: String): String {
            return File(apkPath).name + "_" + apkPath.md5.substring(0, 8)
        }

        private val String.md5: String get() = MessageDigest.getInstance("MD5").digest(this.toByteArray()).toHex()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
