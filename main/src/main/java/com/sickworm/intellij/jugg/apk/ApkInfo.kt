package com.sickworm.intellij.jugg.apk

import java.io.File

class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String
) {

    constructor(
        file: File,
        applicationId: String,
    ) : this(listOf(ApkFileUnit(applicationId, "", file)), applicationId)
}

class ApkFileUnit(val applicationId: String, val moduleName: String, val apkFile: File)

