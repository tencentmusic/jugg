package com.sickworm.intellij.jugg.ide.logic

import com.google.gson.Gson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Builds and restores the plugin-embedded standalone Bundle using exact JAR content matches. */
internal object StandaloneEmbeddedBundle {
    private const val MANIFEST_FILE = "standalone_bundle_manifest.json"

    fun createDeltaBundle(sourceBundle: File, pluginLibDir: File, targetBundle: File) {
        check(pluginLibDir.isDirectory) { "Jugg plugin lib directory is missing: $pluginLibDir" }
        createDeltaBundle(sourceBundle, pluginLibDir.listFiles().orEmpty().asList(), targetBundle)
    }

    fun createDeltaBundle(sourceBundle: File, sharedJarFiles: Collection<File>, targetBundle: File) {
        val sharedJars = sharedJarFiles.jarFilesByHash()
        val manifest = readBundleManifest(sourceBundle)
        val omittedPaths = manifest.declaredFiles()
            .filter { (_, hash) -> sharedJars.containsKey(hash) }
            .mapTo(mutableSetOf()) { (path) -> path }
        targetBundle.parentFile?.mkdirs()
        val tempBundle = targetBundle.parentFile.resolve("${targetBundle.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipFile(sourceBundle).use { source ->
                ZipOutputStream(tempBundle.outputStream()).use { target ->
                    source.entries().asSequence().filter { it.name !in omittedPaths }.forEach { entry ->
                        target.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
                }
            }
            moveAtomically(tempBundle, targetBundle)
        } finally {
            tempBundle.delete()
        }
    }

    fun restoreSharedJars(bundleRoot: File, pluginLibDir: File) {
        check(pluginLibDir.isDirectory) { "Jugg plugin lib directory is missing: $pluginLibDir" }
        val sharedJars = pluginLibDir.listFiles().orEmpty().asList().jarFilesByHash()
        readManifestFile(bundleRoot.resolve(MANIFEST_FILE)).declaredFiles().forEach { (path, hash) ->
            val target = bundleRoot.resolve(path)
            if (target.isFile) return@forEach
            val source = sharedJars[hash]
                ?: throw IllegalStateException("Standalone Bundle JAR is unavailable: $path, sha256=$hash")
            target.parentFile.mkdirs()
            val temp = target.parentFile.resolve("${target.name}.${UUID.randomUUID()}.tmp")
            try {
                source.copyTo(temp)
                check(temp.sha256() == hash) { "Restored standalone Bundle JAR failed verification: $path" }
                moveAtomically(temp, target)
            } finally {
                temp.delete()
            }
        }
    }

    private fun readBundleManifest(bundle: File): EmbeddedStandaloneManifest = ZipFile(bundle).use { zip ->
        val entry = zip.getEntry(MANIFEST_FILE) ?: error("Jugg standalone Bundle manifest is missing")
        zip.getInputStream(entry).reader().use { Gson().fromJson(it, EmbeddedStandaloneManifest::class.java) }
    }

    private fun readManifestFile(file: File): EmbeddedStandaloneManifest {
        check(file.isFile) { "Jugg standalone Bundle manifest is missing: $file" }
        return Gson().fromJson(file.readText(), EmbeddedStandaloneManifest::class.java)
    }

    private fun EmbeddedStandaloneManifest.declaredFiles(): List<Pair<String, String>> {
        val runtime = jarFileNames.map { name -> "jars/${validateFileName(name)}" to jarSha256.getValue(name) }
        val bootstrap = bootstrapFileNames.map { name ->
            "bootstrap/${validateFileName(name)}" to bootstrapSha256.getValue(name)
        }
        return runtime + bootstrap
    }

    private fun Collection<File>.jarFilesByHash(): Map<String, File> {
        return filter {
            it.isFile && it.extension == "jar" && !Files.isSymbolicLink(it.toPath())
        }.sortedBy(File::getName).associateBy { it.sha256() }
    }

    private fun validateFileName(name: String): String {
        check(name.isNotBlank() && name == File(name).name && !name.contains("..")) {
            "Invalid standalone Bundle file name: $name"
        }
        return name
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private data class EmbeddedStandaloneManifest(
    val jarFileNames: List<String>,
    val jarSha256: Map<String, String>,
    val bootstrapFileNames: List<String>,
    val bootstrapSha256: Map<String, String>,
)
