package com.sickworm.intellij.jugg.ide.logic

import com.google.gson.Gson
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.sickworm.intellij.jugg.project.runtime.StandaloneHotUpdateManifest
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Installs the embedded standalone Bundle without adding its JARs to the IDEA classpath. */
object StandaloneBundleInstallService {
    private const val BUNDLE_MANIFEST = "standalone_bundle_manifest.json"

    fun install() {
        val embeddedBundle = resolveBundle()
        install(embeddedBundle.bundle, embeddedBundle.pluginLibDir)
    }

    /** Refreshes an IDEA-managed standalone runtime when the embedded tooling build changes. */
    @Synchronized
    fun installIfNeeded(): Boolean {
        val juggRootDir = File(System.getProperty("user.home"), ".jugg")
        val isCliInstalled = juggRootDir.resolve("bin").isDirectory
        if (!isCliInstalled) return false
        val embeddedBundle = resolveBundle()
        val bundledManifest = readBundleManifest(embeddedBundle.bundle)
        val activeManifest = readManifest(juggRootDir.resolve("hot_update/standalone_load_manifest.json"))
        if (!shouldInstallRuntime(isCliInstalled, activeManifest, bundledManifest.releaseBuildId)) {
            return false
        }
        install(embeddedBundle.bundle, embeddedBundle.pluginLibDir)
        return true
    }

    internal fun shouldInstallRuntime(
        isCliInstalled: Boolean,
        activeManifest: StandaloneHotUpdateManifest?,
        bundledReleaseBuildId: String,
    ): Boolean {
        if (!isCliInstalled) return false
        if (activeManifest == null) return true
        if (activeManifest.managedBy == "external") return false
        return activeManifest.toolingReleaseBuildId != bundledReleaseBuildId
    }

    private fun resolveBundle(): EmbeddedBundleLocation {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(JuggPluginIdentity.ID))
            ?: error("Jugg plugin is not installed")
        val standaloneDir = plugin.pluginPath.resolve("standalone").toFile()
        val bundle = standaloneDir.listFiles().orEmpty().singleOrNull {
            it.isFile && it.name.startsWith("jugg-standalone-") && it.extension == "zip"
        } ?: error("Jugg standalone Bundle is missing")
        return EmbeddedBundleLocation(bundle, plugin.pluginPath.resolve("lib").toFile())
    }

    private fun readBundleManifest(bundle: File): StandaloneHotUpdateManifest {
        return ZipFile(bundle).use { zip ->
            val entry = zip.getEntry(BUNDLE_MANIFEST) ?: error("Jugg standalone Bundle manifest is missing")
            zip.getInputStream(entry).reader().use {
                Gson().fromJson(it, StandaloneHotUpdateManifest::class.java)
            }
        }.also { check(it.releaseBuildId.isNotBlank()) { "Jugg standalone Bundle build ID is missing" } }
    }

    private fun readManifest(file: File): StandaloneHotUpdateManifest? {
        if (!file.isFile) return null
        return runCatching { Gson().fromJson(file.readText(), StandaloneHotUpdateManifest::class.java) }.getOrNull()
    }

    private fun install(bundle: File, pluginLibDir: File) {
        val stageDir = Files.createTempDirectory("jugg-standalone-install").toFile()
        try {
            extract(bundle, stageDir)
            StandaloneEmbeddedBundle.restoreSharedJars(stageDir, pluginLibDir)
            val command = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd", "/c", stageDir.resolve("install.cmd").absolutePath, "--managed-by=idea")
            } else {
                listOf("sh", stageDir.resolve("install.sh").absolutePath, "--managed-by=idea")
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            check(exitCode == 0) { installerFailureMessage(exitCode, output) }
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun extract(bundle: File, targetDir: File) {
        val rootPath = targetDir.canonicalFile.toPath()
        ZipInputStream(bundle.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = targetDir.resolve(entry.name).canonicalFile
                check(target.toPath().startsWith(rootPath)) { "Invalid standalone Bundle entry: ${entry.name}" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use(zip::copyTo)
                }
                zip.closeEntry()
            }
        }
    }

    internal fun installerFailureMessage(exitCode: Int, output: String): String {
        val details = output.trim()
        return if (details.isEmpty()) {
            "Standalone Bundle installer exited with $exitCode"
        } else {
            "Standalone Bundle installer exited with $exitCode:\n$details"
        }
    }

    private data class EmbeddedBundleLocation(val bundle: File, val pluginLibDir: File)
}
