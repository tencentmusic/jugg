package com.sickworm.intellij.jugg.apk

import java.io.File

class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String
) {

    constructor(
        file: File,
        applicationId: String,
    ) : this(listOf(ApkFileUnit("", file)), applicationId)
}

class ApkFileUnit(val moduleName: String, val apkFile: File)

