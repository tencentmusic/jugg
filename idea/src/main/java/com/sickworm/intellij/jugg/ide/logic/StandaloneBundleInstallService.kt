package com.sickworm.intellij.jugg.ide.logic

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Installs the embedded standalone Bundle without adding its JARs to the IDEA classpath. */
object StandaloneBundleInstallService {
    private const val PLUGIN_ID = "com.sickworm.intellij.jugg"

    fun install() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: error("Jugg plugin is not installed")
        val standaloneDir = plugin.pluginPath.resolve("standalone").toFile()
        val bundle = standaloneDir.listFiles().orEmpty().singleOrNull {
            it.isFile && it.name.startsWith("jugg-standalone-") && it.extension == "zip"
        } ?: error("Jugg standalone Bundle is missing")
        val stageDir = Files.createTempDirectory("jugg-standalone-install").toFile()
        try {
            extract(bundle, stageDir)
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
}
