package com.sickworm.intellij.jugg.apk

import java.io.File

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
}

