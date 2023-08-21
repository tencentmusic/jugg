package com.sickworm.intellij.jugg.apk.database

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.apk.JuggFileInfo
import java.io.File


/**
 * Manage .
 */
interface IParsedApkDatabase {

    val hasDeployedOverlays: Boolean

    fun init(apks: List<ApkInfo>)

    fun getAllOverlays(): List<JuggFileInfo>

    fun getApkInfos(): List<ApkInfo>

    fun isNewClass(className: String): Boolean

}

/**
 * Manage deployment history.
 */
class ParsedApkDatabase(val dbDir: File) {

}


