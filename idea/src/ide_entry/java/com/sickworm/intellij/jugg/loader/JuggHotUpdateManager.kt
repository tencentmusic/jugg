package com.sickworm.intellij.jugg.loader

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import java.io.File
import java.util.jar.Manifest

object JuggHotUpdateManager {

    val hotUpdateDir: File = File(PathManager.getSystemPath(), "jugg/hot_update")
    val storageDir: File = File(hotUpdateDir, "jars")

    /** jar file list to be loaded */
    val loadListFile = File(hotUpdateDir, "load_list.txt")

    private val firstUpdateFlag = File(hotUpdateDir, "first_update_flag")

    val isHotUpdateAvailable: Boolean get() = loadListFile.exists()

    val isEmbeddedUpdated: Boolean get() {
        if (cacheEmbeddedBuildTime.isEmpty()) {
            return true
        }
        return cacheEmbeddedBuildTime != embeddedBuildTime
    }

    fun clearHotUpdate() {
        loadListFile.delete()
        firstUpdateFlag.delete()
        cacheEmbeddedBuildTime = embeddedBuildTime ?: ""
    }

    private var cacheEmbeddedBuildTime: String
        get() = PropertiesComponent.getInstance().getValue("jugg.embedded_build_time", "")
        set(value) = PropertiesComponent.getInstance().setValue("jugg.embedded_build_time", value)

    private val embeddedBuildTime: String? by lazy {
        val juggPluginInfoManifest: Manifest? by lazy {
            val cl = this::class.java.classLoader
            cl.getResourceAsStream("META-INF/JUGG_PLUGIN_INFO.MF")?.let {
                Manifest(it)
            }
        }
        juggPluginInfoManifest?.mainAttributes?.getValue("Compile-Timestamp")
    }
}