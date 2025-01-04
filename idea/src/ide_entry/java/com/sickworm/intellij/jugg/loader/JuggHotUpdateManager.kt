package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.application.PathManager
import java.io.File

object JuggHotUpdateManager {

    val hotUpdateDir: File = File(PathManager.getSystemPath(), "jugg/hot_update")
    val storageDir: File = File(hotUpdateDir, "jars")

    /** jar file list to be loaded */
    val loadListFile = File(hotUpdateDir, "load_list.txt")

    val isHotUpdateAvailable: Boolean get() = loadListFile.exists()
}