package com.sickworm.intellij.jugg.loader

import com.google.gson.Gson
import com.sickworm.intellij.jugg.project.HotUpdateLoadManifest
import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.Manifest

object JuggHotUpdateManager {

    val hotUpdateDir: File = JuggGlobalPathManager.hotUpdateDir
    val storageDir: File = File(hotUpdateDir, "jars")

    val loadManifestFile = File(hotUpdateDir, "load_manifest.json")

    internal val activeLoadManifest: HotUpdateLoadManifest?
        get() = resolveLoadManifest(loadManifestFile, currentEmbeddedBuildTime)

    internal fun resolveLoadManifest(
        manifestFile: File = loadManifestFile,
        embeddedBuildTime: String = currentEmbeddedBuildTime,
    ): HotUpdateLoadManifest? {
        if (embeddedBuildTime.isEmpty()) {
            return null
        }
        return readLoadManifest(manifestFile)
            ?.takeIf { it.baseEmbeddedBuildTime == embeddedBuildTime && it.jarFileNames.isNotEmpty() }
    }

    internal fun publishLoadManifest(
        manifest: HotUpdateLoadManifest,
        manifestFile: File = loadManifestFile,
    ) {
        manifestFile.parentFile?.mkdirs()
        val tempFile = File("${manifestFile.path}.tmp")
        tempFile.writeText(Gson().toJson(manifest))
        replaceFile(tempFile, manifestFile)
    }

    internal fun publishEmbeddedIfNeeded(
        embeddedLibDir: File,
        hotUpdateDir: File = this.hotUpdateDir,
        embeddedBuildTime: String = currentEmbeddedBuildTime,
    ): Boolean {
        if (!hotUpdateDir.exists() || embeddedBuildTime.isEmpty() || !embeddedLibDir.isDirectory) {
            return false
        }
        val manifestFile = File(hotUpdateDir, loadManifestFile.name)
        if (readLoadManifest(manifestFile)?.baseEmbeddedBuildTime == embeddedBuildTime) {
            return false
        }
        val embeddedJars = embeddedLibDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .sortedBy { it.name }
        if (embeddedJars.isEmpty()) {
            return false
        }
        val storageDir = File(hotUpdateDir, this.storageDir.name)
        storageDir.mkdirs()
        embeddedJars.forEach { source ->
            val target = storageDir.resolve(source.name)
            if (!target.exists()) {
                source.copyTo(target)
            }
        }
        publishLoadManifest(
            HotUpdateLoadManifest(embeddedBuildTime, embeddedJars.map { it.name }),
            manifestFile,
        )
        return true
    }

    internal fun cleanupExpiredJars(
        storageDir: File,
        referencedJarNames: Set<String>,
        nowMillis: Long,
    ): List<File> {
        val expiredBeforeMillis = nowMillis - JAR_RETENTION_MILLIS
        return storageDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .filter { it.name !in referencedJarNames && it.lastModified() < expiredBeforeMillis }
            .filter { it.delete() }
    }

    internal fun replaceFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal val currentEmbeddedBuildTime: String by lazy {
        val juggPluginInfoManifest: Manifest? by lazy {
            val cl = this::class.java.classLoader
            cl.getResourceAsStream("META-INF/JUGG_PLUGIN_INFO.MF")?.let {
                Manifest(it)
            }
        }
        juggPluginInfoManifest?.mainAttributes?.getValue("Compile-Timestamp").orEmpty()
    }

    private fun readLoadManifest(manifestFile: File): HotUpdateLoadManifest? {
        if (!manifestFile.isFile) {
            return null
        }
        return try {
            Gson().fromJson(manifestFile.readText(), HotUpdateLoadManifest::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private const val JAR_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
}
