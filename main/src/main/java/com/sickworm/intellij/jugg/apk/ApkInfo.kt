package com.sickworm.intellij.jugg.apk

import java.io.File

data class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String
) {

    val baseApkFile: File? get() = files.find { it.isBaseApk }?.apkFile

    constructor(
        file: File,
        applicationId: String,
    ) : this(listOf(ApkFileUnit(applicationId, "", file)), applicationId)
}

data class ApkFileUnit(val applicationId: String, val moduleName: String, val apkFile: File) {

    val isBaseApk get() = moduleName.isEmpty()
    val isFeatureApk get() = moduleName.isNotEmpty()
}

