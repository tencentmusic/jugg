package com.sickworm.intellij.jugg.loader

import com.google.gson.Gson
import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import java.io.File
import java.util.jar.Manifest

/** Reads the hot-update manifest before the hot-update classloader and runtime task domain exist. */
object JuggHotUpdateBootstrap {
    val hotUpdateDir: File = JuggGlobalPathManager.hotUpdateDir
    val storageDir: File = File(hotUpdateDir, "jars")
    private val loadManifestFile = File(hotUpdateDir, "load_manifest.json")

    internal val activeLoadManifest: HotUpdateLoadManifest?
        get() = resolveLoadManifest(loadManifestFile, currentEmbeddedBuildTime)

    internal fun resolveLoadManifest(manifestFile: File, embeddedBuildTime: String): HotUpdateLoadManifest? {
        if (embeddedBuildTime.isEmpty() || !manifestFile.isFile) return null
        return runCatching { Gson().fromJson(manifestFile.readText(), HotUpdateLoadManifest::class.java) }.getOrNull()
            ?.takeIf { it.baseEmbeddedBuildTime == embeddedBuildTime && it.jarFileNames.isNotEmpty() }
    }

    internal val currentEmbeddedBuildTime: String by lazy {
        val manifest = this::class.java.classLoader.getResourceAsStream("META-INF/JUGG_PLUGIN_INFO.MF")?.let(::Manifest)
        manifest?.mainAttributes?.getValue("Compile-Timestamp").orEmpty()
    }
}
