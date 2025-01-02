package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.application.PathManager
import java.io.File

object JuggHotUpdateManager {

    val storageDir: File = File(PathManager.getSystemPath(), "jugg/hot_update")

    val loadListFile = File(storageDir, "load_list.txt")

    val isHotUpdateAvailable: Boolean get() = loadListFile.exists()
}